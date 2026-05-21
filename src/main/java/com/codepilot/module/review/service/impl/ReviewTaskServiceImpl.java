package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.enums.ReviewTaskStatus;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.git.dto.GithubPrInfo;
import com.codepilot.module.git.parser.GithubPrUrlParser;
import com.codepilot.module.git.policy.GithubRepositoryPolicy;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.planner.ReviewFilePlanner;
import com.codepilot.module.review.publisher.ReviewCommentPublisher;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.ReviewIssueService;
import com.codepilot.module.review.service.ReviewTaskService;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewTaskServiceImpl extends ServiceImpl<ReviewTaskMapper, ReviewTask> implements ReviewTaskService {

    private final GithubPrUrlParser githubPrUrlParser;

    private final GithubClient githubClient;

    private final ReviewFileService reviewFileService;

    private final ReviewIssueService reviewIssueService;

    private final AiReviewService aiReviewService;

    private final ReviewTaskProducer reviewTaskProducer;

    private final ReviewFilePlanner reviewFilePlanner;

    private final ReviewIssueAssembler reviewIssueAssembler;

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

        ReviewTask task = new ReviewTask();
        task.setRepoOwner(prInfo.getOwner());
        task.setRepoName(prInfo.getRepo());
        task.setPrNumber(prInfo.getPullNumber());
        task.setPrUrl(prUrl.trim());
        task.setTitle(title);
        task.setHeadSha(StringUtils.hasText(headSha) ? headSha.trim() : null);
        task.setReviewCommentMode(normalizeReviewCommentMode(reviewCommentMode).name());
        task.setStatus(ReviewTaskStatus.PENDING.name());
        task.setTotalFiles(0);
        task.setTotalIssues(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        save(task);
        sendTaskMessageAfterCommit(task.getId());

        return new ReviewCreateResponse(task.getId(), task.getStatus());
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
            var changedFiles = githubClient.listPullRequestFiles(
                    task.getRepoOwner(),
                    task.getRepoName(),
                    task.getPrNumber()
            );
            List<ReviewFile> reviewFiles = reviewFilePlanner.plan(taskId, changedFiles);

            reviewFileService.remove(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReviewFile>()
                    .eq(ReviewFile::getTaskId, taskId));
            reviewIssueService.remove(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReviewIssue>()
                    .eq(ReviewIssue::getTaskId, taskId));
            reviewFileService.saveBatch(reviewFiles);

            List<String> allChangedFiles = reviewFiles.stream()
                    .map(ReviewFile::getFilePath)
                    .filter(StringUtils::hasText)
                    .toList();
            List<ReviewIssue> reviewIssues = new ArrayList<>();
            for (ReviewFile reviewFile : reviewFiles) {
                if (!Boolean.TRUE.equals(reviewFile.getSkipped())) {
                    reviewIssues.addAll(reviewFileWithAi(taskId, reviewFile, allChangedFiles));
                }
            }

            if (!reviewIssues.isEmpty()) {
                reviewIssueService.saveBatch(reviewIssues);
            }

            task.setStatus(ReviewTaskStatus.SUCCESS.name());
            task.setTotalFiles(changedFiles.size());
            task.setTotalIssues(reviewIssues.size());
            task.setRiskLevel(reviewIssueAssembler.calculateRiskLevel(reviewIssues));
            task.setErrorMessage(null);
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            updateById(task);
            log.info("Review task processed successfully, taskId={}, totalFiles={}, totalIssues={}",
                    taskId, changedFiles.size(), reviewIssues.size());
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

    private List<ReviewIssue> reviewFileWithAi(Long taskId, ReviewFile reviewFile, List<String> allChangedFiles) {
        try {
            AiReviewResult aiReviewResult = aiReviewService.reviewFile(
                    taskId,
                    reviewFile.getFilePath(),
                    reviewFile.getPatch(),
                    allChangedFiles
            );
            return reviewIssueAssembler.toReviewIssues(taskId, reviewFile.getFilePath(), aiReviewResult);
        } catch (Exception exception) {
            throw new IllegalStateException("AI review failed for file " + reviewFile.getFilePath(), exception);
        }
    }

    private ReviewCommentMode normalizeReviewCommentMode(ReviewCommentMode reviewCommentMode) {
        return reviewCommentMode == null ? ReviewCommentMode.SUMMARY_ONLY : reviewCommentMode;
    }

    private String sanitizedErrorMessage(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        String sanitized = SensitiveDataSanitizer.redactAndTruncate(message, 2000);
        return StringUtils.hasText(sanitized)
                ? sanitized
                : (exception == null ? "unknown error" : exception.getClass().getSimpleName());
    }
}
