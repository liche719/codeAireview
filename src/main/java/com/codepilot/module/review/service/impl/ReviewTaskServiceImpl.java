package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.enums.ReviewTaskStatus;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.git.dto.GithubPrInfo;
import com.codepilot.module.git.parser.GithubPrUrlParser;
import com.codepilot.module.git.policy.GithubRepositoryPolicy;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.planner.ReviewFilePlanner;
import com.codepilot.module.review.service.GitHubInlineCommentResult;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.GitHubCommentService;
import com.codepilot.module.review.service.GitHubInlineCommentService;
import com.codepilot.module.review.service.ReviewIssueService;
import com.codepilot.module.review.service.ReviewTaskService;
import com.codepilot.task.ReviewTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewTaskServiceImpl extends ServiceImpl<ReviewTaskMapper, ReviewTask> implements ReviewTaskService {

    private final GithubPrUrlParser githubPrUrlParser;

    private final GithubClient githubClient;

    private final ReviewFileService reviewFileService;

    private final ReviewIssueService reviewIssueService;

    private final AiReviewService aiReviewService;

    private final GitHubCommentService githubCommentService;

    private final GitHubInlineCommentService gitHubInlineCommentService;

    private final ReviewTaskProducer reviewTaskProducer;

    private final ReviewFilePlanner reviewFilePlanner;

    private final GithubRepositoryPolicy githubRepositoryPolicy;

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
            task.setRiskLevel(calculateRiskLevel(reviewIssues));
            task.setErrorMessage(null);
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            updateById(task);
            log.info("Review task processed successfully, taskId={}, totalFiles={}, totalIssues={}",
                    taskId, changedFiles.size(), reviewIssues.size());
            commentReviewResult(task);
        } catch (Exception exception) {
            task.setStatus(ReviewTaskStatus.FAILED.name());
            task.setErrorMessage(exception.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            updateById(task);
            log.error("Review task failed, taskId={}", taskId, exception);
            throw new IllegalStateException("review task failed, taskId=" + taskId, exception);
        }
    }

    private void commentReviewResult(ReviewTask task) {
        ReviewCommentMode reviewCommentMode = ReviewCommentMode.fromValue(task.getReviewCommentMode());
        if (reviewCommentMode.isInlineOnly()) {
            GitHubInlineCommentResult result = null;
            try {
                result = gitHubInlineCommentService.commentInlineIssues(task.getId());
            } catch (Exception exception) {
                log.warn("GitHub PR inline comment failed unexpectedly, taskId={}", task.getId(), exception);
            }
            if (result == null || !result.hasSuccess()) {
                try {
                    githubCommentService.commentReviewResult(task.getId());
                } catch (Exception exception) {
                    log.warn("GitHub PR summary comment failed unexpectedly, taskId={}", task.getId(), exception);
                }
            }
            return;
        }
        try {
            githubCommentService.commentReviewResult(task.getId());
        } catch (Exception exception) {
            log.warn("GitHub PR summary comment failed unexpectedly, taskId={}", task.getId(), exception);
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
            return mapToReviewIssues(taskId, reviewFile.getFilePath(), aiReviewResult);
        } catch (Exception exception) {
            throw new IllegalStateException("AI review failed for file " + reviewFile.getFilePath(), exception);
        }
    }

    private List<ReviewIssue> mapToReviewIssues(Long taskId, String defaultFilePath, AiReviewResult aiReviewResult) {
        if (aiReviewResult == null || aiReviewResult.getIssues() == null || aiReviewResult.getIssues().isEmpty()) {
            return List.of();
        }

        List<ReviewIssue> reviewIssues = new ArrayList<>();
        for (AiReviewIssue issue : aiReviewResult.getIssues()) {
            ReviewIssue reviewIssue = new ReviewIssue();
            reviewIssue.setTaskId(taskId);
            reviewIssue.setFilePath(StringUtils.hasText(issue.getFilePath()) ? issue.getFilePath() : defaultFilePath);
            reviewIssue.setLineNumber(issue.getLineNumber());
            reviewIssue.setIssueType(issue.getIssueType());
            reviewIssue.setIssueTypeZh(issue.getIssueTypeZh());
            reviewIssue.setSeverity(normalizeSeverity(issue.getSeverity()));
            reviewIssue.setTitle(issue.getTitle());
            reviewIssue.setDescription(issue.getDescription());
            reviewIssue.setSuggestion(issue.getSuggestion());
            reviewIssue.setSource(normalizeSource(issue.getSource()));
            reviewIssue.setRuleReference(issue.getRuleReference());
            reviewIssue.setCreatedAt(LocalDateTime.now());
            reviewIssues.add(reviewIssue);
        }
        return reviewIssues;
    }

    private String normalizeSource(String source) {
        if (!StringUtils.hasText(source)) {
            return "LLM";
        }
        String normalizedSource = source.trim().toUpperCase(Locale.ROOT);
        return "TOOL".equals(normalizedSource) ? "TOOL" : "LLM";
    }

    private ReviewCommentMode normalizeReviewCommentMode(ReviewCommentMode reviewCommentMode) {
        return reviewCommentMode == null ? ReviewCommentMode.SUMMARY_ONLY : reviewCommentMode;
    }

    private String calculateRiskLevel(List<ReviewIssue> reviewIssues) {
        return reviewIssues.stream()
                .map(ReviewIssue::getSeverity)
                .filter(StringUtils::hasText)
                .map(this::normalizeSeverity)
                .min(Comparator.comparingInt(this::severityRank))
                .orElse("PASS");
    }

    private String normalizeSeverity(String severity) {
        if (!StringUtils.hasText(severity)) {
            return "LOW";
        }
        return severity.trim().toUpperCase(Locale.ROOT);
    }

    private int severityRank(String severity) {
        return switch (severity) {
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }
}
