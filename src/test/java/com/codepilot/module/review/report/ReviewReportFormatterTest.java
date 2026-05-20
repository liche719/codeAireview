package com.codepilot.module.review.report;

import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewReportFormatterTest {

    private static final String COMMENT_MARKER = "<!-- codepilot-ai-review:liche719/codeAireview -->";

    private final ReviewReportFormatter formatter = new ReviewReportFormatter(COMMENT_MARKER);

    @Test
    void shouldGeneratePassReportWhenIssuesAreEmpty() {
        String markdown = formatter.formatMarkdown(reviewTask("PASS"), List.of());

        assertThat(markdown).contains(COMMENT_MARKER);
        assertThat(markdown).contains("CodePilot AI 审查报告");
        assertThat(markdown).contains("未发现问题");
    }

    @Test
    void shouldSortIssuesBySeverity() {
        List<ReviewIssue> issues = List.of(
                issue("LOW", "STYLE", "low issue"),
                issue("HIGH", "SQL_RISK", "high issue"),
                issue("MEDIUM", "SECURITY", "medium issue")
        );

        String markdown = formatter.formatMarkdown(reviewTask("HIGH"), issues);

        assertThat(markdown).contains("#### 1. [HIGH] SQL_RISK");
        assertThat(markdown).contains("#### 2. [MEDIUM] SECURITY");
        assertThat(markdown).contains("#### 3. [LOW] STYLE");
        assertThat(markdown.indexOf("[HIGH]")).isLessThan(markdown.indexOf("[MEDIUM]"));
        assertThat(markdown.indexOf("[MEDIUM]")).isLessThan(markdown.indexOf("[LOW]"));
    }

    @Test
    void shouldLimitVisibleIssuesToTwenty() {
        List<ReviewIssue> issues = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            issues.add(issue("LOW", "STYLE", "issue " + i));
        }

        String markdown = formatter.formatMarkdown(reviewTask("LOW"), issues);

        assertThat(markdown).contains("#### 20. [LOW] STYLE");
        assertThat(markdown).doesNotContain("#### 21. [LOW] STYLE");
        assertThat(markdown).contains("剩余问题数：1");
    }

    private ReviewTask reviewTask(String riskLevel) {
        ReviewTask task = new ReviewTask();
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(123);
        task.setRiskLevel(riskLevel);
        return task;
    }

    private ReviewIssue issue(String severity, String issueType, String title) {
        ReviewIssue issue = new ReviewIssue();
        issue.setFilePath("src/main/java/Demo.java");
        issue.setLineNumber(42);
        issue.setIssueType(issueType);
        issue.setSeverity(severity);
        issue.setTitle(title);
        issue.setDescription("description");
        issue.setSuggestion("suggestion");
        issue.setSource("LLM");
        return issue;
    }
}
