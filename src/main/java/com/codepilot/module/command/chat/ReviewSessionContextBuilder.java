package com.codepilot.module.command.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.enums.ReviewTaskStatus;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.processor.ReviewFindingRanker;
import com.codepilot.module.review.report.ReviewIssueEvidenceFormatter;
import com.codepilot.module.review.service.ReviewIssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ReviewSessionContextBuilder {

    private static final int MAX_FINDINGS = 8;

    private static final int MAX_CONTEXT_CHARS = 6000;

    private static final int MAX_TITLE_CHARS = 180;

    private static final int MAX_DESCRIPTION_CHARS = 500;

    private static final int MAX_SUGGESTION_CHARS = 400;

    private static final int MAX_EVIDENCE_CHARS = 260;

    private final ReviewTaskMapper reviewTaskMapper;

    private final ReviewIssueService reviewIssueService;

    private final ReviewFindingRanker reviewFindingRanker;

    private final GithubClient githubClient;

    public String build(String owner, String repo, Integer pullNumber) {
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo) || pullNumber == null) {
            return "No stored review context is available because the PR identity is incomplete.";
        }

        ReviewTask successfulTask = findLatestTask(owner, repo, pullNumber, ReviewTaskStatus.SUCCESS.name());
        if (successfulTask == null) {
            return noSuccessfulReviewContext(owner, repo, pullNumber);
        }

        String currentHeadSha = currentHeadSha(owner, repo, pullNumber);
        String freshness = freshness(successfulTask.getHeadSha(), currentHeadSha);
        List<ReviewIssue> rankedIssues = reviewFindingRanker.orderForPublish(loadIssues(successfulTask.getId()));
        StringBuilder context = new StringBuilder();
        appendLine(context, "Latest stored PR review session:");
        appendLine(context, "- taskId: " + successfulTask.getId());
        appendLine(context, "- pr: " + owner + "/" + repo + "#" + pullNumber);
        appendLine(context, "- title: " + safe(successfulTask.getTitle(), MAX_TITLE_CHARS, "N/A"));
        appendLine(context, "- reviewedHeadSha: " + safe(shortSha(successfulTask.getHeadSha()), 64, "N/A"));
        appendLine(context, "- currentHeadSha: " + safe(shortSha(currentHeadSha), 64, "N/A"));
        appendLine(context, "- reviewFreshness: " + freshness);
        appendLine(context, "- status: " + safe(successfulTask.getStatus(), 40, "N/A"));
        appendLine(context, "- finishedAt: " + safeTime(successfulTask.getFinishedAt()));
        appendLine(context, "- totals: files=" + defaultNumber(successfulTask.getTotalFiles())
                + ", issues=" + defaultNumber(successfulTask.getTotalIssues())
                + ", risk=" + safe(successfulTask.getRiskLevel(), 40, "N/A"));
        appendLine(context, "- note: This is stored review evidence. If reviewFreshness=STALE, do not present findings as current; ask the user to run @x-pilotx review again.");
        appendLine(context, "");

        appendFindingSummary(context, rankedIssues);
        appendFindings(context, rankedIssues);
        return truncate(context.toString());
    }

    private String currentHeadSha(String owner, String repo, Integer pullNumber) {
        if (githubClient == null) {
            return null;
        }
        try {
            GithubPullRequestDetail detail = githubClient.getPullRequestDetail(owner, repo, pullNumber);
            return detail == null ? null : detail.getHeadSha();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String freshness(String reviewedHeadSha, String currentHeadSha) {
        if (!StringUtils.hasText(reviewedHeadSha) || !StringUtils.hasText(currentHeadSha)) {
            return "UNKNOWN";
        }
        return reviewedHeadSha.trim().equalsIgnoreCase(currentHeadSha.trim()) ? "FRESH" : "STALE";
    }

    private String noSuccessfulReviewContext(String owner, String repo, Integer pullNumber) {
        ReviewTask latestTask = findLatestTask(owner, repo, pullNumber, null);
        StringBuilder context = new StringBuilder();
        appendLine(context, "No successful stored review result is available for this PR yet.");
        appendLine(context, "- pr: " + owner + "/" + repo + "#" + pullNumber);
        if (latestTask != null) {
            appendLine(context, "- latestTaskId: " + latestTask.getId());
            appendLine(context, "- latestStatus: " + safe(latestTask.getStatus(), 40, "N/A"));
            appendLine(context, "- latestHeadSha: " + safe(shortSha(latestTask.getHeadSha()), 64, "N/A"));
            appendLine(context, "- latestUpdatedAt: " + safeTime(latestTask.getUpdatedAt()));
        }
        appendLine(context, "- guidance: If the user asks about review findings or evidence, explain that no completed review is available and ask them to run @x-pilotx review.");
        return truncate(context.toString());
    }

    private ReviewTask findLatestTask(String owner, String repo, Integer pullNumber, String status) {
        LambdaQueryWrapper<ReviewTask> query = new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getRepoOwner, owner)
                .eq(ReviewTask::getRepoName, repo)
                .eq(ReviewTask::getPrNumber, pullNumber);
        if (StringUtils.hasText(status)) {
            query.eq(ReviewTask::getStatus, status);
        }
        query.orderByDesc(ReviewTask::getFinishedAt)
                .orderByDesc(ReviewTask::getUpdatedAt)
                .orderByDesc(ReviewTask::getId)
                .last("LIMIT 1");
        List<ReviewTask> tasks = reviewTaskMapper.selectList(query);
        return tasks == null || tasks.isEmpty() ? null : tasks.get(0);
    }

    private List<ReviewIssue> loadIssues(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        List<ReviewIssue> issues = reviewIssueService.list(new LambdaQueryWrapper<ReviewIssue>()
                .eq(ReviewIssue::getTaskId, taskId)
                .orderByAsc(ReviewIssue::getId));
        return issues == null ? List.of() : issues;
    }

    private void appendFindingSummary(StringBuilder context, List<ReviewIssue> rankedIssues) {
        appendLine(context, "Finding summary:");
        appendLine(context, "- totalStoredFindings: " + rankedIssues.size());
        appendLine(context, "- publishableFindings: " + countByDecision(rankedIssues, "PUBLISH"));
        appendLine(context, "- suppressedFindings: " + countByDecision(rankedIssues, "SUPPRESS"));
        Map<String, Integer> suppressionReasons = suppressionReasons(rankedIssues);
        if (!suppressionReasons.isEmpty()) {
            appendLine(context, "- suppressionReasons: " + suppressionReasons);
        }
        appendLine(context, "");
    }

    private void appendFindings(StringBuilder context, List<ReviewIssue> rankedIssues) {
        if (rankedIssues.isEmpty()) {
            appendLine(context, "Top stored findings: none.");
            return;
        }
        appendLine(context, "Top stored findings, ordered by persisted publish decision and score:");
        int count = 0;
        for (ReviewIssue issue : rankedIssues) {
            if (issue == null) {
                continue;
            }
            count++;
            appendIssue(context, count, issue);
            if (count >= MAX_FINDINGS || context.length() >= MAX_CONTEXT_CHARS) {
                break;
            }
        }
        int hidden = Math.max(0, rankedIssues.size() - count);
        if (hidden > 0) {
            appendLine(context, "- ... " + hidden + " more stored findings omitted by chat context budget.");
        }
    }

    private void appendIssue(StringBuilder context, int index, ReviewIssue issue) {
        String location = safe(issue.getFilePath(), 240, "N/A")
                + ":"
                + (issue.getLineNumber() == null ? "N/A" : issue.getLineNumber());
        appendLine(context, index + ". decision=" + safe(issue.getPublishDecision(), 40, "N/A")
                + ", channel=" + safe(issue.getCommentChannel(), 40, "N/A")
                + ", score=" + (issue.getFinalScore() == null ? "N/A" : issue.getFinalScore())
                + ", severity=" + safe(issue.getSeverity(), 40, "N/A")
                + ", type=" + safe(issueType(issue), 80, "N/A")
                + ", location=" + location);
        appendLine(context, "   title: " + safe(issue.getTitle(), MAX_TITLE_CHARS, "N/A"));
        appendLine(context, "   description: " + safe(issue.getDescription(), MAX_DESCRIPTION_CHARS, "N/A"));
        appendLine(context, "   suggestion: " + safe(issue.getSuggestion(), MAX_SUGGESTION_CHARS, "N/A"));
        String evidence = ReviewIssueEvidenceFormatter.compactTrace(issue);
        if (StringUtils.hasText(evidence)) {
            appendLine(context, "   evidence: " + safe(evidence, MAX_EVIDENCE_CHARS, "N/A"));
        }
        if (StringUtils.hasText(issue.getSuppressionReason())) {
            appendLine(context, "   suppressionReason: " + safe(issue.getSuppressionReason(), 180, "N/A"));
        }
    }

    private long countByDecision(List<ReviewIssue> issues, String decision) {
        String normalizedDecision = normalize(decision);
        return issues.stream()
                .filter(issue -> normalizedDecision.equals(normalize(issue == null ? null : issue.getPublishDecision())))
                .count();
    }

    private Map<String, Integer> suppressionReasons(List<ReviewIssue> issues) {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (ReviewIssue issue : issues) {
            if (issue == null || !"SUPPRESS".equals(normalize(issue.getPublishDecision()))) {
                continue;
            }
            String reason = safe(issue.getSuppressionReason(), 120, "unspecified");
            reasons.merge(reason, 1, Integer::sum);
        }
        return reasons;
    }

    private String issueType(ReviewIssue issue) {
        if (issue == null) {
            return null;
        }
        if (StringUtils.hasText(issue.getIssueTypeZh())) {
            return safe(issue.getIssueType(), 40, "UNKNOWN") + "/" + issue.getIssueTypeZh();
        }
        return issue.getIssueType();
    }

    private void appendLine(StringBuilder builder, String line) {
        builder.append(line).append('\n');
    }

    private String safe(String value, int maxLength, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String compact = SensitiveDataSanitizer.redactAndTruncate(value, Math.max(0, maxLength))
                .replaceAll("(?i)https?://\\S+", "[URL_REDACTED]")
                .replace("`", "'")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return StringUtils.hasText(compact) ? compact : fallback;
    }

    private String truncate(String value) {
        return SensitiveDataSanitizer.truncatePreservingRedactionMarker(value, MAX_CONTEXT_CHARS);
    }

    private String safeTime(LocalDateTime value) {
        return value == null ? "N/A" : value.toString();
    }

    private int defaultNumber(Integer value) {
        return value == null ? 0 : value;
    }

    private String shortSha(String headSha) {
        if (!StringUtils.hasText(headSha)) {
            return null;
        }
        String trimmed = headSha.trim();
        return trimmed.length() <= 12 ? trimmed : trimmed.substring(0, 12);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}
