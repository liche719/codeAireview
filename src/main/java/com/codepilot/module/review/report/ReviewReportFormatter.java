package com.codepilot.module.review.report;

import com.codepilot.common.util.MarkdownSanitizer;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class ReviewReportFormatter {

    public static final String DEFAULT_COMMENT_MARKER = "<!-- codepilot-ai-review:liche719/codeAireview -->";

    private static final int MAX_VISIBLE_ISSUES = 20;

    private static final int MAX_TEXT_LENGTH = 500;

    private final String commentMarker;

    public ReviewReportFormatter(
            @Value("${codepilot.github.comment-marker:}") String commentMarker
    ) {
        this.commentMarker = StringUtils.hasText(commentMarker)
                ? commentMarker
                : DEFAULT_COMMENT_MARKER;
    }

    public String getCommentMarker() {
        return commentMarker;
    }

    public String formatMarkdown(ReviewTask task, List<ReviewIssue> issues) {
        List<ReviewIssue> sortedIssues = (issues == null ? List.<ReviewIssue>of() : issues).stream()
                .sorted(Comparator.comparingInt(issue -> severityRank(issue.getSeverity())))
                .toList();

        StringBuilder markdown = new StringBuilder();
        markdown.append(commentMarker).append("\n\n");

        if (sortedIssues.isEmpty()) {
            markdown.append("未发现问题。\n");
            return markdown.toString();
        }

        int visibleCount = Math.min(sortedIssues.size(), MAX_VISIBLE_ISSUES);
        for (int i = 0; i < visibleCount; i++) {
            appendIssue(markdown, i + 1, sortedIssues.get(i));
        }
        if (sortedIssues.size() > MAX_VISIBLE_ISSUES) {
            markdown.append("另外还有 ")
                    .append(sortedIssues.size() - MAX_VISIBLE_ISSUES)
                    .append(" 条问题未展示。\n");
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
        markdown.append("- **行号**: ").append(issue.getLineNumber() == null ? "无" : issue.getLineNumber()).append("\n");
        markdown.append("- **描述**: ").append(sanitizeIssueText(issue.getDescription())).append("\n");
        markdown.append("- **建议**: ").append(sanitizeIssueText(issue.getSuggestion())).append("\n\n");
        markdown.append("---\n\n");
    }

    private int severityRank(String severity) {
        return switch (normalizeSeverity(severity)) {
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }

    private String normalizeSeverity(String severity) {
        return StringUtils.hasText(severity) ? severity.trim().toUpperCase(Locale.ROOT) : "LOW";
    }

    private String displaySeverity(String severity) {
        return switch (normalizeSeverity(severity)) {
            case "HIGH" -> "高";
            case "MEDIUM" -> "中";
            case "LOW" -> "低";
            default -> "低";
        };
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
        return MarkdownSanitizer.sanitizeInlineText(content, MAX_TEXT_LENGTH, "无");
    }

    private String sanitizeCodeSpan(String content) {
        if (!StringUtils.hasText(content)) {
            return "无";
        }
        return content.replace('`', '\'').replaceAll("\\s+", " ").trim();
    }
}
