package com.codepilot.module.review.report;

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

    public String formatMarkdown(ReviewTask task, List<ReviewIssue> issues) {
        List<ReviewIssue> sortedIssues = (issues == null ? List.<ReviewIssue>of() : issues).stream()
                .sorted(Comparator.comparingInt(issue -> severityRank(issue.getSeverity())))
                .toList();

        StringBuilder markdown = new StringBuilder();
        markdown.append(commentMarker).append("\n\n");
        markdown.append("## CodePilot AI 审查报告\n\n");

        if (sortedIssues.isEmpty()) {
            markdown.append("未发现问题。\n");
            return markdown.toString();
        }

        markdown.append("### 问题列表\n\n");
        int visibleCount = Math.min(sortedIssues.size(), MAX_VISIBLE_ISSUES);
        for (int i = 0; i < visibleCount; i++) {
            appendIssue(markdown, i + 1, sortedIssues.get(i));
        }
        if (sortedIssues.size() > MAX_VISIBLE_ISSUES) {
            markdown.append("发现的问题较多，但这里只展示前 ")
                    .append(MAX_VISIBLE_ISSUES)
                    .append(" 条。剩余问题数：")
                    .append(sortedIssues.size() - MAX_VISIBLE_ISSUES)
                    .append("。\n");
        }

        return markdown.toString();
    }

    private void appendIssue(StringBuilder markdown, int index, ReviewIssue issue) {
        markdown.append("#### ")
                .append(index)
                .append(". [")
                .append(normalizeSeverity(issue.getSeverity()))
                .append("] ")
                .append(nullToDash(issue.getIssueType()))
                .append("\n\n");
        markdown.append("- **文件**: `").append(nullToDash(issue.getFilePath())).append("`\n");
        markdown.append("- **行号**: ").append(issue.getLineNumber() == null ? "无" : issue.getLineNumber()).append("\n");
        markdown.append("- **描述**: ").append(truncate(issue.getDescription())).append("\n");
        markdown.append("- **建议**: ").append(truncate(issue.getSuggestion())).append("\n\n");
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

    private String truncate(String content) {
        if (!StringUtils.hasText(content)) {
            return "无";
        }
        String compact = content.replaceAll("\\s+", " ").trim();
        if (compact.length() <= MAX_TEXT_LENGTH) {
            return compact;
        }
        return compact.substring(0, MAX_TEXT_LENGTH) + "...";
    }

    private String nullToDash(String content) {
        return StringUtils.hasText(content) ? content : "无";
    }
}
