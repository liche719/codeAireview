package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.creator.ReviewTaskCreationResult;
import com.codepilot.module.review.creator.ReviewTaskCreator;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.failure.ReviewTaskFailureHandler;
import com.codepilot.module.review.failure.ReviewTaskFailureResult;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.processor.ReviewTaskProcessingResult;
import com.codepilot.module.review.processor.ReviewTaskProcessor;
import com.codepilot.module.review.publisher.ReviewCommentPublisher;
import com.codepilot.module.review.service.ReviewTaskService;
import com.codepilot.module.review.state.ReviewTaskStateManager;
import com.codepilot.task.ReviewTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final ReviewTaskFailureHandler reviewTaskFailureHandler;

    private final ReviewTaskProcessor reviewTaskProcessor;

    private final ReviewCommentPublisher reviewCommentPublisher;

    private final ReviewTaskStateManager reviewTaskStateManager;

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
            ReviewTaskFailureResult failureResult = reviewTaskFailureHandler.handle(task, exception);
            throw new IllegalStateException("review task failed, taskId=" + taskId
                    + ", errorType=" + failureResult.errorType());
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
}
