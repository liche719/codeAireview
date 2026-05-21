package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.enums.ReviewTaskStatus;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubPrInfo;
import com.codepilot.module.git.parser.GithubPrUrlParser;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
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

    private final ReviewProperties reviewProperties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCreateResponse createTask(String prUrl) {
        return createTask(prUrl, null, ReviewCommentMode.SUMMARY_ONLY);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCreateResponse createTask(String prUrl, String title) {
        return createTask(prUrl, title, ReviewCommentMode.SUMMARY_ONLY);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCreateResponse createTask(String prUrl, String title, ReviewCommentMode reviewCommentMode) {
        GithubPrInfo prInfo = githubPrUrlParser.parse(prUrl);

        ReviewTask task = new ReviewTask();
        task.setRepoOwner(prInfo.getOwner());
        task.setRepoName(prInfo.getRepo());
        task.setPrNumber(prInfo.getPullNumber());
        task.setPrUrl(prUrl.trim());
        task.setTitle(title);
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
            List<GithubChangedFile> changedFiles = githubClient.listPullRequestFiles(
                    task.getRepoOwner(),
                    task.getRepoName(),
                    task.getPrNumber()
            );
            List<ReviewFile> reviewFiles = toReviewFiles(taskId, changedFiles);
            applyReviewLimits(reviewFiles);

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

    private List<ReviewFile> toReviewFiles(Long taskId, List<GithubChangedFile> changedFiles) {
        return changedFiles.stream()
                .map(changedFile -> toReviewFile(taskId, changedFile))
                .toList();
    }

    private ReviewFile toReviewFile(Long taskId, GithubChangedFile changedFile) {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setTaskId(taskId);
        reviewFile.setFilePath(changedFile.getFilename());
        reviewFile.setChangeType(changedFile.getStatus());
        reviewFile.setPatch(changedFile.getPatch());
        reviewFile.setAdditions(changedFile.getAdditions());
        reviewFile.setDeletions(changedFile.getDeletions());
        boolean skipped = changedFile.getPatch() == null || changedFile.getPatch().isBlank();
        reviewFile.setSkipped(skipped);
        reviewFile.setSkipReason(skipped ? "patch is empty or file is binary/too large" : null);
        reviewFile.setCreatedAt(LocalDateTime.now());
        return reviewFile;
    }

    private void applyReviewLimits(List<ReviewFile> reviewFiles) {
        int reviewedFiles = 0;
        int totalPatchChars = 0;

        for (ReviewFile reviewFile : reviewFiles) {
            String basicSkipReason = getBasicSkipReason(reviewFile);
            if (basicSkipReason != null) {
                markSkipped(reviewFile, basicSkipReason);
                continue;
            }

            int patchLength = reviewFile.getPatch().length();
            if (isPositive(reviewProperties.getMaxPatchCharsPerFile())
                    && patchLength > reviewProperties.getMaxPatchCharsPerFile()) {
                markSkipped(reviewFile, "patch exceeds per-file review limit");
                continue;
            }

            if (isPositive(reviewProperties.getMaxFilesPerTask())
                    && reviewedFiles >= reviewProperties.getMaxFilesPerTask()) {
                markSkipped(reviewFile, "review file count limit exceeded");
                continue;
            }

            if (isPositive(reviewProperties.getMaxTotalPatchChars())
                    && totalPatchChars + patchLength > reviewProperties.getMaxTotalPatchChars()) {
                markSkipped(reviewFile, "review total patch length limit exceeded");
                continue;
            }

            reviewFile.setSkipped(false);
            reviewFile.setSkipReason(null);
            reviewedFiles++;
            totalPatchChars += patchLength;
        }

        long skippedCount = reviewFiles.stream()
                .filter(reviewFile -> Boolean.TRUE.equals(reviewFile.getSkipped()))
                .count();
        log.info("Review file limits applied, totalFiles={}, reviewedFiles={}, skippedFiles={}, maxFiles={}, maxPatchCharsPerFile={}, maxTotalPatchChars={}",
                reviewFiles.size(),
                reviewedFiles,
                skippedCount,
                reviewProperties.getMaxFilesPerTask(),
                reviewProperties.getMaxPatchCharsPerFile(),
                reviewProperties.getMaxTotalPatchChars());
    }

    private String getBasicSkipReason(ReviewFile reviewFile) {
        if (Boolean.TRUE.equals(reviewFile.getSkipped())) {
            return StringUtils.hasText(reviewFile.getSkipReason()) ? reviewFile.getSkipReason() : "file is already marked skipped";
        }
        if (!StringUtils.hasText(reviewFile.getPatch())) {
            return "patch is empty or file is binary/too large";
        }
        if (!StringUtils.hasText(reviewFile.getFilePath())) {
            return "file path is empty";
        }
        if (shouldSkipPath(reviewFile.getFilePath())) {
            return "file type or generated path skipped";
        }
        return null;
    }

    private void markSkipped(ReviewFile reviewFile, String reason) {
        reviewFile.setSkipped(true);
        reviewFile.setSkipReason(reason);
    }

    private boolean isPositive(int value) {
        return value > 0;
    }

    private boolean shouldSkipPath(String filePath) {
        String normalizedPath = filePath
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);

        return normalizedPath.endsWith(".lock")
                || normalizedPath.endsWith("package-lock.json")
                || normalizedPath.endsWith("yarn.lock")
                || normalizedPath.endsWith(".min.js")
                || normalizedPath.startsWith("dist/")
                || normalizedPath.contains("/dist/")
                || normalizedPath.startsWith("target/")
                || normalizedPath.contains("/target/")
                || normalizedPath.startsWith("build/")
                || normalizedPath.contains("/build/");
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
