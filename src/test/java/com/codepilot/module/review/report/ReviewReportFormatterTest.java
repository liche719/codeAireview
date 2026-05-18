package com.codepilot.module.review.report;

import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewReportFormatterTest {

    private final ReviewReportFormatter formatter = new ReviewReportFormatter("<!-- codepilot-ai-review -->");

    @Test
    void shouldGeneratePassReportWhenIssuesAreEmpty() {
        String markdown = formatter.formatMarkdown(reviewTask("PASS"), List.of());

        assertThat(markdown).contains("<!-- codepilot-ai-review -->");
        assertThat(markdown).contains("CodePilot AI Review Report");
        assertThat(markdown).contains("**Risk Level**: PASS");
        assertThat(markdown).contains("No issues found");
    }

    @Test
    void shouldSortIssuesBySeverity() {
        List<ReviewIssue> issues = List.of(
                issue("LOW", "STYLE", "low issue"),
                issue("HIGH", "SQL_RISK", "high issue"),
                issue("MEDIUM", "SECURITY", "medium issue")
        );

        String markdown = formatter.formatMarkdown(reviewTask("HIGH"), issues);

        assertThat(markdown).contains("#### 1. [HIGH] SQL_RISK - high issue");
        assertThat(markdown).contains("#### 2. [MEDIUM] SECURITY - medium issue");
        assertThat(markdown).contains("#### 3. [LOW] STYLE - low issue");
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

        assertThat(markdown).contains("#### 20. [LOW] STYLE - issue 20");
        assertThat(markdown).doesNotContain("#### 21. [LOW] STYLE - issue 21");
        assertThat(markdown).contains("Remaining issues: 1");
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
