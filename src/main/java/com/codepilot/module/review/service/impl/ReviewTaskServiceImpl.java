package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.creator.ReviewTaskCreationResult;
import com.codepilot.module.review.creator.ReviewTaskCreator;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.processor.ReviewTaskProcessingResult;
import com.codepilot.module.review.processor.ReviewTaskProcessor;
import com.codepilot.module.review.publisher.ReviewCommentPublisher;
import com.codepilot.module.review.service.ReviewTaskService;
import com.codepilot.module.review.state.ReviewTaskStateManager;
import com.codepilot.task.ReviewTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewTaskServiceImpl extends ServiceImpl<ReviewTaskMapper, ReviewTask> implements ReviewTaskService {

    private final GithubClient githubClient;

    private final ReviewTaskProducer reviewTaskProducer;

    private final ReviewTaskCreator reviewTaskCreator;

    private final ReviewTaskProcessor reviewTaskProcessor;

    private final ReviewCommentPublisher reviewCommentPublisher;

    private final ReviewTaskStateManager reviewTaskStateManager;

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
        ReviewTaskCreationResult creationResult = reviewTaskCreator.create(prUrl, title, reviewCommentMode, headSha);
        if (creationResult.created()) {
            sendTaskMessageAfterCommit(creationResult.taskId());
        }
        return new ReviewCreateResponse(creationResult.taskId(), creationResult.status());
    }

    @Override
    public void processTask(Long taskId) {
        ReviewTask task = getById(taskId);
        if (task == null) {
            throw new BusinessException("review task not found, taskId=" + taskId);
        }

        reviewTaskStateManager.markRunning(task);

        try {
            refreshTaskHeadSha(task);
            ReviewTaskProcessingResult processingResult = reviewTaskProcessor.process(task);

            reviewTaskStateManager.markSuccess(
                    task,
                    processingResult.totalFiles(),
                    processingResult.totalIssues(),
                    processingResult.riskLevel()
            );
            log.info("Review task processed successfully, taskId={}, totalFiles={}, totalIssues={}",
                    taskId, processingResult.totalFiles(), processingResult.totalIssues());
            reviewCommentPublisher.publish(task);
        } catch (Exception exception) {
            String errorMessage = sanitizedErrorMessage(exception);
            if (isFinalRetryAttempt()) {
                reviewTaskStateManager.markFailed(task, errorMessage);
                log.error("Review task failed, taskId={}, {}, message={}",
                        taskId, retryDetail(exception), errorMessage);
            } else {
                reviewTaskStateManager.markRetrying(task, errorMessage);
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
        reviewTaskStateManager.updateHeadSha(task, headSha);
    }

    private String sanitizedErrorMessage(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        String sanitized = SensitiveDataSanitizer.redactAndTruncate(message, 2000);
        return StringUtils.hasText(sanitized)
                ? sanitized
                : (exception == null ? "unknown error" : exception.getClass().getSimpleName());
    }
}
