package com.codepilot.module.review.report;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.processor.ReviewCommentBudgetAllocator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewReportFormatterTest {

    private static final String COMMENT_MARKER = "<!-- codepilot-ai-review:liche719/codeAireview -->";

    private final ReviewReportFormatter formatter = new ReviewReportFormatter(
            COMMENT_MARKER,
            new ReviewCommentBudgetAllocator(new ReviewProperties())
    );

    @Test
    void shouldGeneratePassReportWhenIssuesAreEmpty() {
        String markdown = formatter.formatMarkdown(reviewTask("PASS"), List.of());

        assertThat(markdown).contains(COMMENT_MARKER);
        assertThat(markdown).contains("未发现问题");
    }

    @Test
    void shouldUseRankedIssuesAndSuppressionAwareBudget() {
        List<ReviewIssue> issues = List.of(
                issue("LOW", "STYLE", "低价值问题", "low issue", 10, "SUPPRESS", "NONE"),
                issue("HIGH", "SECURITY", "高价值问题", "high issue", 90, "PUBLISH", "INLINE"),
                issue("MEDIUM", "BUG_RISK", "中价值问题", "medium issue", 60, "PUBLISH", "SUMMARY")
        );

        String markdown = formatter.formatMarkdown(reviewTask("HIGH"), issues);

        assertThat(markdown).contains("#### 1. [高] 高价值问题");
        assertThat(markdown).contains("#### 2. [中] 中价值问题");
        assertThat(markdown).doesNotContain("low issue");
        assertThat(markdown).contains("另外还有 1 条问题未展示");
        assertThat(markdown).contains("其中 1 条已被抑制");
    }

    @Test
    void shouldLimitVisibleIssuesThroughBudgetAllocator() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMaxSummaryFindings(2);
        ReviewReportFormatter limitedFormatter = new ReviewReportFormatter(
                COMMENT_MARKER,
                new ReviewCommentBudgetAllocator(properties)
        );

        List<ReviewIssue> issues = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            issues.add(issue("HIGH", "BUG_RISK", "问题" + i, "issue " + i, 80 - i, "PUBLISH", "SUMMARY"));
        }

        String markdown = limitedFormatter.formatMarkdown(reviewTask("HIGH"), issues);

        assertThat(markdown).contains("#### 1. [高] 问题1");
        assertThat(markdown).contains("#### 2. [高] 问题2");
        assertThat(markdown).doesNotContain("问题3");
        assertThat(markdown).contains("另外还有 1 条问题未展示");
    }

    private ReviewTask reviewTask(String riskLevel) {
        ReviewTask task = new ReviewTask();
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(123);
        task.setRiskLevel(riskLevel);
        return task;
    }

    private ReviewIssue issue(
            String severity,
            String issueType,
            String issueTypeZh,
            String title,
            int score,
            String publishDecision,
            String commentChannel
    ) {
        ReviewIssue issue = new ReviewIssue();
        issue.setFilePath("src/main/java/Demo.java");
        issue.setLineNumber(42);
        issue.setIssueType(issueType);
        issue.setIssueTypeZh(issueTypeZh);
        issue.setSeverity(severity);
        issue.setTitle(title);
        issue.setDescription("description with enough detail");
        issue.setSuggestion("suggestion with enough detail");
        issue.setSource("LLM");
        issue.setFinalScore(score);
        issue.setPublishDecision(publishDecision);
        issue.setCommentChannel(commentChannel);
        return issue;
    }
}
