package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.common.util.MarkdownSanitizer;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.diff.DiffLineMapper;
import com.codepilot.module.review.diff.DiffLineMapping;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.processor.ReviewCommentBudgetAllocator;
import com.codepilot.module.review.processor.ReviewFindingRanker;
import com.codepilot.module.review.report.ReviewIssueEvidenceFormatter;
import com.codepilot.module.review.service.GitHubInlineCommentResult;
import com.codepilot.module.review.service.GitHubInlineCommentService;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.ReviewIssueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GitHubInlineCommentServiceImpl implements GitHubInlineCommentService {

    private static final int MAX_TEXT_LENGTH = 500;

    private static final String INLINE_MARKER = "<!-- codepilot-inline-review -->";

    private static final Pattern INLINE_FINGERPRINT_PATTERN =
            Pattern.compile("<!--\\s*codepilot-inline-review:([a-f0-9]{16,64})\\s*-->", Pattern.CASE_INSENSITIVE);

    private final ReviewTaskMapper reviewTaskMapper;

    private final ReviewIssueService reviewIssueService;

    private final ReviewFileService reviewFileService;

    private final GithubClient githubClient;

    private final DiffLineMapper diffLineMapper;

    private final ReviewCommentBudgetAllocator reviewCommentBudgetAllocator;

    private final ReviewFindingRanker reviewFindingRanker;

    private final boolean inlineCommentEnabled;

    private final int inlineCommentMaxPerTask;

    private final String githubToken;

    public GitHubInlineCommentServiceImpl(
            ReviewTaskMapper reviewTaskMapper,
            ReviewIssueService reviewIssueService,
            ReviewFileService reviewFileService,
            GithubClient githubClient,
            DiffLineMapper diffLineMapper,
            ReviewCommentBudgetAllocator reviewCommentBudgetAllocator,
            ReviewFindingRanker reviewFindingRanker,
            @Value("${codepilot.github.inline-comment-enabled:false}") boolean inlineCommentEnabled,
            @Value("${codepilot.github.inline-comment-max-per-task:10}") int inlineCommentMaxPerTask,
            @Value("${codepilot.github.token:}") String githubToken
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewIssueService = reviewIssueService;
        this.reviewFileService = reviewFileService;
        this.githubClient = githubClient;
        this.diffLineMapper = diffLineMapper;
        this.reviewCommentBudgetAllocator = reviewCommentBudgetAllocator;
        this.reviewFindingRanker = reviewFindingRanker;
        this.inlineCommentEnabled = inlineCommentEnabled;
        this.inlineCommentMaxPerTask = inlineCommentMaxPerTask;
        this.githubToken = githubToken;
    }

    @Override
    public GitHubInlineCommentResult commentInlineIssues(Long taskId) {
        int successCount = 0;
        int failedCount = 0;
        int skippedCount = 0;

        try {
            if (!inlineCommentEnabled) {
                log.info("Skip GitHub PR inline comments because inline comment is disabled, taskId={}", taskId);
                return new GitHubInlineCommentResult(0, 0, 0);
            }
            if (!StringUtils.hasText(githubToken)) {
                log.warn("Skip GitHub PR inline comments because GitHub token is missing, taskId={}", taskId);
                return new GitHubInlineCommentResult(0, 0, 0);
            }
            if (inlineCommentMaxPerTask <= 0) {
                log.info("Skip GitHub PR inline comments because max per task is not positive, taskId={}, maxPerTask={}",
                        taskId, inlineCommentMaxPerTask);
                return new GitHubInlineCommentResult(0, 0, 0);
            }

            ReviewTask task = reviewTaskMapper.selectById(taskId);
            if (task == null) {
                log.warn("Skip GitHub PR inline comments because review task was not found, taskId={}", taskId);
                return new GitHubInlineCommentResult(0, 0, 0);
            }

            List<ReviewIssue> issues = reviewIssueService.list(new LambdaQueryWrapper<ReviewIssue>()
                    .eq(ReviewIssue::getTaskId, taskId));
            if (issues.isEmpty()) {
                log.info("Skip GitHub PR inline comments because there are no issues, taskId={}", taskId);
                return new GitHubInlineCommentResult(0, 0, 0);
            }

            Map<String, ReviewFile> reviewFileByPath = reviewFileByPath(taskId);
            GithubPullRequestDetail detail = githubClient.getPullRequestDetail(
                    task.getRepoOwner(),
                    task.getRepoName(),
                    task.getPrNumber()
            );
            String headSha = detail.getHeadSha();
            if (!StringUtils.hasText(headSha)) {
                log.warn("Skip GitHub PR inline comments because PR head sha is missing, taskId={}", taskId);
                return new GitHubInlineCommentResult(0, 0, 0);
            }
            Set<String> existingFingerprints = existingInlineCommentFingerprints(task);

            Set<String> sentIssueKeys = new HashSet<>();
            List<ReviewIssue> rankedIssues = reviewFindingRanker.orderForPublish(issues);
            List<ReviewIssue> inlineIssues = reviewCommentBudgetAllocator.allocateInlineFindings(rankedIssues, inlineCommentMaxPerTask);
            skippedCount += Math.max(0, rankedIssues.size() - inlineIssues.size());
            for (ReviewIssue issue : inlineIssues) {
                String issueKey = issueKey(issue);
                if (!sentIssueKeys.add(issueKey)) {
                    skippedCount++;
                    continue;
                }

                ReviewFile reviewFile = StringUtils.hasText(issue.getFilePath())
                        ? reviewFileByPath.get(issue.getFilePath())
                        : null;
                if (reviewFile == null || !StringUtils.hasText(reviewFile.getPatch()) || issue.getLineNumber() == null) {
                    skippedCount++;
                    continue;
                }

                DiffLineMapping mapping = diffLineMapper.map(issue.getFilePath(), reviewFile.getPatch(), issue.getLineNumber());
                if (!mapping.commentable()) {
                    skippedCount++;
                    continue;
                }

                String fingerprint = issueFingerprint(task, headSha, issue);
                if (existingFingerprints.contains(fingerprint)) {
                    skippedCount++;
                    continue;
                }

                try {
                    githubClient.createPullRequestInlineComment(
                            task.getRepoOwner(),
                            task.getRepoName(),
                            task.getPrNumber(),
                            headSha,
                            issue.getFilePath(),
                            mapping.line(),
                            mapping.side(),
                            buildInlineCommentBody(issue, fingerprint)
                    );
                    existingFingerprints.add(fingerprint);
                    successCount++;
                } catch (Exception exception) {
                    failedCount++;
                    log.warn("GitHub PR inline comment failed but ignored, taskId={}, filePath={}, line={}, errorType={}, message={}",
                            taskId, issue.getFilePath(), issue.getLineNumber(),
                            exception.getClass().getSimpleName(), SensitiveDataSanitizer.redact(exception.getMessage()));
                }
            }
        } catch (Exception exception) {
            failedCount++;
            log.warn("GitHub PR inline comments failed but ignored, taskId={}, errorType={}, message={}",
                    taskId, exception.getClass().getSimpleName(), SensitiveDataSanitizer.redact(exception.getMessage()));
        } finally {
            log.info("GitHub PR inline comments completed, taskId={}, successCount={}, failedCount={}, skippedCount={}, maxPerTask={}",
                    taskId, successCount, failedCount, skippedCount, inlineCommentMaxPerTask);
        }
        return new GitHubInlineCommentResult(successCount, failedCount, skippedCount);
    }

    private Map<String, ReviewFile> reviewFileByPath(Long taskId) {
        List<ReviewFile> reviewFiles = reviewFileService.list(new LambdaQueryWrapper<ReviewFile>()
                .eq(ReviewFile::getTaskId, taskId));
        Map<String, ReviewFile> result = new HashMap<>();
        for (ReviewFile reviewFile : reviewFiles) {
            if (StringUtils.hasText(reviewFile.getFilePath())) {
                result.putIfAbsent(reviewFile.getFilePath(), reviewFile);
            }
        }
        return result;
    }

    private Set<String> existingInlineCommentFingerprints(ReviewTask task) {
        try {
            List<GithubIssueComment> comments = githubClient.listPullRequestReviewComments(
                    task.getRepoOwner(),
                    task.getRepoName(),
                    task.getPrNumber()
            );
            if (comments == null || comments.isEmpty()) {
                return new HashSet<>();
            }
            Set<String> fingerprints = new HashSet<>();
            for (GithubIssueComment comment : comments) {
                if (comment == null || !StringUtils.hasText(comment.getBody())) {
                    continue;
                }
                Matcher matcher = INLINE_FINGERPRINT_PATTERN.matcher(comment.getBody());
                while (matcher.find()) {
                    fingerprints.add(matcher.group(1).toLowerCase());
                }
            }
            return fingerprints;
        } catch (Exception exception) {
            log.warn("Failed to list existing GitHub PR inline comments, continue without cross-task dedup, owner={}, repo={}, pullNumber={}, errorType={}, message={}",
                    task.getRepoOwner(), task.getRepoName(), task.getPrNumber(),
                    exception.getClass().getSimpleName(), SensitiveDataSanitizer.redact(exception.getMessage()));
            return new HashSet<>();
        }
    }

    private String issueKey(ReviewIssue issue) {
        return nullToDash(issue.getFilePath())
                + ":"
                + issue.getLineNumber()
                + ":"
                + nullToDash(issue.getIssueType());
    }

    private String issueFingerprint(ReviewTask task, String headSha, ReviewIssue issue) {
        String rawFingerprint = nullToDash(task.getRepoOwner())
                + "/"
                + nullToDash(task.getRepoName())
                + ":"
                + task.getPrNumber()
                + ":"
                + nullToDash(headSha)
                + ":"
                + issueKey(issue);
        return sha256Hex(rawFingerprint);
    }

    private String buildInlineCommentBody(ReviewIssue issue, String fingerprint) {
        StringBuilder body = new StringBuilder();
        body.append(INLINE_MARKER).append("\n\n");
        body.append("<!-- codepilot-inline-review:").append(fingerprint).append(" -->").append("\n\n");
        body.append("Description:\n");
        body.append(sanitizeIssueText(issue.getDescription())).append("\n\n");
        String evidenceTrace = ReviewIssueEvidenceFormatter.compactTrace(issue);
        if (StringUtils.hasText(evidenceTrace)) {
            body.append("Evidence:\n");
            body.append(sanitizeIssueText(evidenceTrace)).append("\n\n");
        }
        body.append("Suggestion:\n");
        body.append(sanitizeIssueText(issue.getSuggestion())).append("\n");
        return body.toString();
    }

    private String sanitizeIssueText(String content) {
        return MarkdownSanitizer.sanitizeInlineText(content, MAX_TEXT_LENGTH, "N/A");
    }

    private String nullToDash(String content) {
        return StringUtils.hasText(content) ? content : "N/A";
    }

    private String sha256Hex(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
