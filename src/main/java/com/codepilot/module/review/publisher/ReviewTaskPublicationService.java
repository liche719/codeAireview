package com.codepilot.module.review.publisher;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.gate.ReviewGateEvaluator;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.service.ReviewIssueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ReviewTaskPublicationService {

    private final ReviewTaskMapper reviewTaskMapper;

    private final ReviewCommentPublisher reviewCommentPublisher;

    private final ReviewProperties reviewProperties;

    private final MeterRegistry meterRegistry;

    /**
     * 用 {@code ObjectProvider} 注入：本类在若干测试里是直接 new 出来的，
     * 缺依赖时应当只是"不判定门禁"，而不是构造失败。
     */
    private final ObjectProvider<ReviewGateEvaluator> reviewGateEvaluatorProvider;

    private final ObjectProvider<GithubClient> githubClientProvider;

    private final ObjectProvider<ReviewIssueService> reviewIssueServiceProvider;

    /**
     * 不带门禁依赖的便捷构造：手工 new 的场景（测试）不需要门禁，
     * 此时整段门禁逻辑被跳过，行为与引入 R17 之前一致。
     *
     * <p>必须配 {@code @Autowired} 标在 Lombok 生成的全参构造上：本类有多个构造器，
     * Spring 无法自行选择，否则会在启动时以"找不到无参构造器"失败。</p>
     */
    public ReviewTaskPublicationService(
            ReviewTaskMapper reviewTaskMapper,
            ReviewCommentPublisher reviewCommentPublisher,
            ReviewProperties reviewProperties,
            MeterRegistry meterRegistry
    ) {
        this(reviewTaskMapper, reviewCommentPublisher, reviewProperties, meterRegistry, null, null, null);
    }

    public void publish(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        if (reviewTaskMapper.claimPublication(
                taskId,
                now,
                now.minus(reviewProperties.getOutboxLockTimeout())
        ) == 0) {
            return;
        }

        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }

        ReviewCommentPublishResult result = reviewCommentPublisher.publish(task);
        // 门禁在评论发布之后判定：两者是独立维度（R17 的核心设计），
        // 且门禁的任何失败都不允许影响审查任务自身的状态。
        applyGate(task);
        if (result.published()) {
            reviewTaskMapper.finishPublication(taskId, "PUBLISHED", null, null, LocalDateTime.now());
            meterRegistry.counter("codepilot.review.github.publish", "result", "success").increment();
            return;
        }
        if (result.skipped()) {
            reviewTaskMapper.finishPublication(taskId, "SKIPPED", null, null, LocalDateTime.now());
            meterRegistry.counter("codepilot.review.github.publish", "result", "skipped").increment();
            return;
        }

        String error = SensitiveDataSanitizer.redactAndTruncate(result.errorMessage(), 2000);
        int attemptCount = task.getPublishAttemptCount() == null ? 1 : task.getPublishAttemptCount();
        if (attemptCount >= Math.max(1, reviewProperties.getPublicationMaxAttempts())) {
            reviewTaskMapper.finishPublication(taskId, "FAILED_FINAL", error, null, LocalDateTime.now());
            meterRegistry.counter("codepilot.review.github.publish", "result", "failed").increment();
            log.error("GitHub review publication exhausted retries, taskId={}, attempt={}, message={}",
                    taskId, attemptCount, error);
            return;
        }
        Duration delay = retryDelay(task.getPublishAttemptCount());
        LocalDateTime retryAt = LocalDateTime.now().plus(delay);
        reviewTaskMapper.finishPublication(taskId, "FAILED", error, retryAt, LocalDateTime.now());
        meterRegistry.counter("codepilot.review.github.publish", "result", "retry").increment();
        log.warn("GitHub review publication scheduled for retry, taskId={}, retryAt={}, message={}",
                taskId, retryAt, error);
    }

    /**
     * 门禁：评估 → 写 commit status → 落库判定结果（R17）。
     *
     * <h2>为什么整段包在 try/catch 里</h2>
     * 门禁是<b>旁路增强</b>。它的失败（缺 commit status 权限、限流、网络抖动）绝不能
     * 让已经成功发布的审查变成失败——那会把"可选的门禁"变成"审查的又一个失败点"。
     *
     * <p>而且门禁未启用时<b>一次 GitHub 调用都不发</b>，保证默认配置下行为与引入本能力之前一致。</p>
     */
    private void applyGate(ReviewTask task) {
        try {
            ReviewGateEvaluator reviewGateEvaluator = reviewGateEvaluatorProvider == null
                    ? null : reviewGateEvaluatorProvider.getIfAvailable();
            GithubClient githubClient = githubClientProvider == null ? null : githubClientProvider.getIfAvailable();
            if (reviewGateEvaluator == null || githubClient == null || task == null) {
                return;
            }
            ReviewGateEvaluator.GateDecision decision = reviewGateEvaluator.evaluate(
                    new ReviewGateEvaluator.ReviewTaskFacts(loadIssues(task.getId())));
            if (!decision.writeStatus()) {
                return;
            }
            githubClient.createCommitStatus(
                    task.getRepoOwner(),
                    task.getRepoName(),
                    task.getHeadSha(),
                    decision.commitStatus(),
                    reviewProperties.getGateContextName(),
                    decision.reason()
            );
            reviewTaskMapper.recordGateDecision(task.getId(), decision.decision(), decision.reason(), LocalDateTime.now());
            meterRegistry.counter("codepilot.review.gate", "decision", decision.decision()).increment();
        } catch (Exception exception) {
            log.warn("Review gate skipped because it could not be applied, taskId={}, errorType={}, message={}",
                    task == null ? null : task.getId(),
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    private java.util.List<ReviewIssue> loadIssues(Long taskId) {
        ReviewIssueService reviewIssueService = reviewIssueServiceProvider == null
                ? null : reviewIssueServiceProvider.getIfAvailable();
        if (reviewIssueService == null || taskId == null) {
            return java.util.List.of();
        }
        return reviewIssueService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReviewIssue>()
                        .eq(ReviewIssue::getTaskId, taskId));
    }

    private Duration retryDelay(Integer attemptCount) {        int attempt = attemptCount == null ? 1 : Math.max(1, attemptCount);
        long initialMillis = Math.max(1000L, reviewProperties.getOutboxRetryInitialDelay().toMillis());
        long maxMillis = Math.max(initialMillis, reviewProperties.getOutboxRetryMaxDelay().toMillis());
        int exponent = Math.min(attempt - 1, 20);
        long multiplier = 1L << exponent;
        long delay = initialMillis > Long.MAX_VALUE / multiplier ? maxMillis : initialMillis * multiplier;
        return Duration.ofMillis(Math.min(delay, maxMillis));
    }
}
