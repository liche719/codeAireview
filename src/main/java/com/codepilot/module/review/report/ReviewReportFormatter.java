package com.codepilot.module.review.report;

import com.codepilot.common.util.MarkdownSanitizer;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.processor.ReviewCommentBudgetAllocator;
import com.codepilot.module.review.processor.ReviewFindingRanker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Component
public class ReviewReportFormatter {

    public static final String DEFAULT_COMMENT_MARKER = "<!-- codepilot-ai-review:liche719/codeAireview -->";

    private static final int MAX_TEXT_LENGTH = 500;

    private final String commentMarker;

    private final ReviewCommentBudgetAllocator reviewCommentBudgetAllocator;

    private final ReviewFindingRanker reviewFindingRanker;

    public ReviewReportFormatter(
            @Value("${codepilot.github.comment-marker:}") String commentMarker,
            ReviewCommentBudgetAllocator reviewCommentBudgetAllocator
    ) {
        this.commentMarker = StringUtils.hasText(commentMarker)
                ? commentMarker
                : DEFAULT_COMMENT_MARKER;
        this.reviewCommentBudgetAllocator = reviewCommentBudgetAllocator;
        this.reviewFindingRanker = new ReviewFindingRanker();
    }

    public String getCommentMarker() {
        return commentMarker;
    }

    public String formatMarkdown(ReviewTask task, List<ReviewIssue> issues) {
        List<ReviewIssue> rankedIssues = reviewFindingRanker.rank(issues);
        List<ReviewIssue> visibleIssues = reviewCommentBudgetAllocator.allocateSummaryFindings(rankedIssues);

        StringBuilder markdown = new StringBuilder();
        markdown.append(commentMarker).append("\n\n");

        if (visibleIssues.isEmpty()) {
            markdown.append("未发现问题。");
            return markdown.toString();
        }

        for (int i = 0; i < visibleIssues.size(); i++) {
            appendIssue(markdown, i + 1, visibleIssues.get(i));
        }

        int totalIssues = rankedIssues.size();
        if (totalIssues > visibleIssues.size()) {
            markdown.append("另外还有 ")
                    .append(totalIssues - visibleIssues.size())
                    .append(" 条问题未展示。\n");
            long suppressedCount = rankedIssues.stream()
                    .filter(issue -> issue != null && "SUPPRESS".equalsIgnoreCase(nullToEmpty(issue.getPublishDecision())))
                    .count();
            if (suppressedCount > 0) {
                markdown.append("其中 ")
                        .append(suppressedCount)
                        .append(" 条已被抑制。\n");
            }
        }

        return markdown.toString();
    }

    private void appendIssue(StringBuilder markdown, int index, ReviewIssue issue) {
        markdown.append("#### ")
                .append(index)
                .append(". [")
                .append(displaySeverity(issue.getSeverity()))
                .append("] ")
                .append(displayIssueType(issue))
                .append("\n\n");
        markdown.append("- **文件**: `").append(sanitizeCodeSpan(issue.getFilePath())).append("`\n");
        markdown.append("- **行号**: ").append(issue.getLineNumber() == null ? "N/A" : issue.getLineNumber()).append("\n");
        markdown.append("- **描述**: ").append(sanitizeIssueText(issue.getDescription())).append("\n");
        markdown.append("- **建议**: ").append(sanitizeIssueText(issue.getSuggestion())).append("\n\n");
        if (StringUtils.hasText(issue.getSuppressionReason())) {
            markdown.append("- **Decision**: suppressed because ")
                    .append(sanitizeIssueText(issue.getSuppressionReason()))
                    .append("\n\n");
        }
        String evidenceTrace = ReviewIssueEvidenceFormatter.compactTrace(issue);
        if (StringUtils.hasText(evidenceTrace)) {
            markdown.append("- **Evidence**: ").append(sanitizeIssueText(evidenceTrace)).append("\n\n");
        }
        markdown.append("---\n\n");
    }

    private String displaySeverity(String severity) {
        return switch (normalizeSeverity(severity)) {
            case "HIGH" -> "高";
            case "MEDIUM" -> "中";
            case "LOW" -> "低";
            default -> "低";
        };
    }

    private String normalizeSeverity(String severity) {
        return StringUtils.hasText(severity) ? severity.trim().toUpperCase(Locale.ROOT) : "LOW";
    }

    private String displayIssueType(ReviewIssue issue) {
        if (issue == null) {
            return "问题";
        }
        if (StringUtils.hasText(issue.getIssueTypeZh())) {
            return MarkdownSanitizer.sanitizeInlineText(issue.getIssueTypeZh(), 80, "问题");
        }
        return "问题";
    }

    private String sanitizeIssueText(String content) {
        return MarkdownSanitizer.sanitizeInlineText(content, MAX_TEXT_LENGTH, "N/A");
    }

    private String sanitizeCodeSpan(String content) {
        if (!StringUtils.hasText(content)) {
            return "N/A";
        }
        return content.replace('`', '\'').replaceAll("\\s+", " ").trim();
    }

    private String nullToEmpty(String content) {
        return content == null ? "" : content;
    }
}
