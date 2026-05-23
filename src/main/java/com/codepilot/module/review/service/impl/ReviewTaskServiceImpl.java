package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.enums.ReviewTaskStatus;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.git.dto.GithubPrInfo;
import com.codepilot.module.git.parser.GithubPrUrlParser;
import com.codepilot.module.git.policy.GithubRepositoryPolicy;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.processor.ReviewTaskProcessingResult;
import com.codepilot.module.review.processor.ReviewTaskProcessor;
import com.codepilot.module.review.publisher.ReviewCommentPublisher;
import com.codepilot.module.review.service.ReviewTaskService;
import com.codepilot.task.ReviewTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewTaskServiceImpl extends ServiceImpl<ReviewTaskMapper, ReviewTask> implements ReviewTaskService {

    private final GithubPrUrlParser githubPrUrlParser;

    private final GithubClient githubClient;

    private final ReviewTaskProducer reviewTaskProducer;

    private final ReviewTaskProcessor reviewTaskProcessor;

    private final ReviewCommentPublisher reviewCommentPublisher;

    private final GithubRepositoryPolicy githubRepositoryPolicy;

    @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}")
    private int rabbitRetryMaxAttempts = 3;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCreateResponse createTask(String prUrl) {
        return createTask(prUrl, null, ReviewCommentMode.SUMMARY_ONLY, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCreateResponse createTask(String prUrl, String title) {
        return createTask(prUrl, title, ReviewCommentMode.SUMMARY_ONLY, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCreateResponse createTask(String prUrl, String title, ReviewCommentMode reviewCommentMode) {
        return createTask(prUrl, title, reviewCommentMode, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCreateResponse createTask(String prUrl, String title, ReviewCommentMode reviewCommentMode, String headSha) {
        GithubPrInfo prInfo = githubPrUrlParser.parse(prUrl);
        githubRepositoryPolicy.assertAllowed(prInfo.getOwner(), prInfo.getRepo());
        ReviewCommentMode normalizedReviewCommentMode = normalizeReviewCommentMode(reviewCommentMode);
        String normalizedHeadSha = normalizeHeadSha(headSha);

        ReviewTask reusableTask = findReusableTask(prInfo, normalizedReviewCommentMode, normalizedHeadSha);
        if (reusableTask != null) {
            log.info("Reuse existing review task, taskId={}, owner={}, repo={}, pullNumber={}, headSha={}, status={}, commentMode={}",
                    reusableTask.getId(),
                    prInfo.getOwner(),
                    prInfo.getRepo(),
                    prInfo.getPullNumber(),
                    normalizedHeadSha,
                    reusableTask.getStatus(),
                    normalizedReviewCommentMode);
            return new ReviewCreateResponse(reusableTask.getId(), reusableTask.getStatus());
        }

        ReviewTask task = new ReviewTask();
        task.setRepoOwner(prInfo.getOwner());
        task.setRepoName(prInfo.getRepo());
        task.setPrNumber(prInfo.getPullNumber());
        task.setPrUrl(prUrl.trim());
        task.setTitle(title);
        task.setHeadSha(normalizedHeadSha);
        task.setReviewCommentMode(normalizedReviewCommentMode.name());
        task.setStatus(ReviewTaskStatus.PENDING.name());
        task.setTotalFiles(0);
        task.setTotalIssues(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        try {
            save(task);
        } catch (DuplicateKeyException exception) {
            ReviewTask concurrentlyCreatedTask = findReusableTask(prInfo, normalizedReviewCommentMode, normalizedHeadSha);
            if (concurrentlyCreatedTask != null) {
                log.info("Reuse concurrently created review task, taskId={}, owner={}, repo={}, pullNumber={}, headSha={}, status={}, commentMode={}",
                        concurrentlyCreatedTask.getId(),
                        prInfo.getOwner(),
                        prInfo.getRepo(),
                        prInfo.getPullNumber(),
                        normalizedHeadSha,
                        concurrentlyCreatedTask.getStatus(),
                        normalizedReviewCommentMode);
                return new ReviewCreateResponse(concurrentlyCreatedTask.getId(), concurrentlyCreatedTask.getStatus());
            }
            throw exception;
        }
        sendTaskMessageAfterCommit(task.getId());

        return new ReviewCreateResponse(task.getId(), task.getStatus());
    }

    private ReviewTask findReusableTask(GithubPrInfo prInfo, ReviewCommentMode reviewCommentMode, String headSha) {
        if (prInfo == null || !StringUtils.hasText(headSha)) {
            return null;
        }
        List<ReviewTask> tasks = list(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getRepoOwner, prInfo.getOwner())
                .eq(ReviewTask::getRepoName, prInfo.getRepo())
                .eq(ReviewTask::getPrNumber, prInfo.getPullNumber())
                .eq(ReviewTask::getHeadSha, headSha)
                .eq(ReviewTask::getReviewCommentMode, reviewCommentMode.name())
                .in(ReviewTask::getStatus, List.of(
                        ReviewTaskStatus.PENDING.name(),
                        ReviewTaskStatus.RUNNING.name(),
                        ReviewTaskStatus.SUCCESS.name()
                ))
                .orderByDesc(ReviewTask::getId)
                .last("LIMIT 1"));
        return tasks == null || tasks.isEmpty() ? null : tasks.getFirst();
    }

    @Override
    public void processTask(Long taskId) {
        ReviewTask task = getById(taskId);
        if (task == null) {
            throw new BusinessException("review task not found, taskId=" + taskId);
        }

        markTaskRunning(task);

        try {
            refreshTaskHeadSha(task);
            ReviewTaskProcessingResult processingResult = reviewTaskProcessor.process(task);

            task.setStatus(ReviewTaskStatus.SUCCESS.name());
            task.setTotalFiles(processingResult.totalFiles());
            task.setTotalIssues(processingResult.totalIssues());
            task.setRiskLevel(processingResult.riskLevel());
            task.setErrorMessage(null);
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            updateById(task);
            log.info("Review task processed successfully, taskId={}, totalFiles={}, totalIssues={}",
                    taskId, processingResult.totalFiles(), processingResult.totalIssues());
            reviewCommentPublisher.publish(task);
        } catch (Exception exception) {
            String errorMessage = sanitizedErrorMessage(exception);
            if (isFinalRetryAttempt()) {
                markTaskFailed(task, errorMessage);
                log.error("Review task failed, taskId={}, {}, message={}",
                        taskId, retryDetail(exception), errorMessage);
            } else {
                markTaskRetrying(task, errorMessage);
                log.warn("Review task failed and will be retried, taskId={}, {}, message={}",
                        taskId, retryDetail(exception), errorMessage);
            }
            throw new IllegalStateException("review task failed, taskId=" + taskId
                    + ", errorType=" + exception.getClass().getSimpleName());
        }
    }

    private void sendTaskMessageAfterCommit(Long taskId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            reviewTaskProducer.send(taskId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reviewTaskProducer.send(taskId);
            }
        });
    }

    private void markTaskRunning(ReviewTask task) {
        task.setStatus(ReviewTaskStatus.RUNNING.name());
        task.setStartedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        updateById(task);
    }

    private void markTaskRetrying(ReviewTask task, String errorMessage) {
        task.setStatus(ReviewTaskStatus.RUNNING.name());
        task.setErrorMessage(errorMessage);
        task.setUpdatedAt(LocalDateTime.now());
        updateById(task);
    }

    private void markTaskFailed(ReviewTask task, String errorMessage) {
        task.setStatus(ReviewTaskStatus.FAILED.name());
        task.setErrorMessage(errorMessage);
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        updateById(task);
    }

    private boolean isFinalRetryAttempt() {
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        if (retryContext == null) {
            return true;
        }
        int maxAttempts = Math.max(1, rabbitRetryMaxAttempts);
        return retryContext.getRetryCount() >= maxAttempts - 1;
    }

    private String retryDetail(Exception exception) {
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        String attempt = retryContext == null
                ? "attempt=1/1"
                : "attempt=" + (retryContext.getRetryCount() + 1) + "/" + Math.max(1, rabbitRetryMaxAttempts);
        return attempt + ", errorType=" + exception.getClass().getSimpleName();
    }

    private void refreshTaskHeadSha(ReviewTask task) {
        GithubPullRequestDetail detail = githubClient.getPullRequestDetail(
                task.getRepoOwner(),
                task.getRepoName(),
                task.getPrNumber()
        );
        if (!StringUtils.hasText(detail.getHeadSha())) {
            return;
        }
        String headSha = detail.getHeadSha().trim();
        if (headSha.equals(task.getHeadSha())) {
            return;
        }
        task.setHeadSha(headSha);
        task.setUpdatedAt(LocalDateTime.now());
        updateById(task);
    }

    private ReviewCommentMode normalizeReviewCommentMode(ReviewCommentMode reviewCommentMode) {
        return reviewCommentMode == null ? ReviewCommentMode.SUMMARY_ONLY : reviewCommentMode;
    }

    private String normalizeHeadSha(String headSha) {
        return StringUtils.hasText(headSha) ? headSha.trim() : null;
    }

    private String sanitizedErrorMessage(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        String sanitized = SensitiveDataSanitizer.redactAndTruncate(message, 2000);
        return StringUtils.hasText(sanitized)
                ? sanitized
                : (exception == null ? "unknown error" : exception.getClass().getSimpleName());
    }
}
