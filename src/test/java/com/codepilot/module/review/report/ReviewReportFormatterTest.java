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
        assertThat(markdown).contains("未发现问题");
        assertThat(markdown).doesNotContain("审查报告");
        assertThat(markdown).doesNotContain("问题列表");
    }

    @Test
    void shouldSortIssuesBySeverity() {
        List<ReviewIssue> issues = List.of(
                issue("LOW", "STYLE", "样式问题", "low issue"),
                issue("HIGH", "SQL_RISK", "数据库问题", "high issue"),
                issue("MEDIUM", "SECURITY", "权限问题", "medium issue")
        );

        String markdown = formatter.formatMarkdown(reviewTask("HIGH"), issues);

        assertThat(markdown).contains("#### 1. [高] 数据库问题");
        assertThat(markdown).contains("#### 2. [中] 权限问题");
        assertThat(markdown).contains("#### 3. [低] 样式问题");
        assertThat(markdown.indexOf("[高]")).isLessThan(markdown.indexOf("[中]"));
        assertThat(markdown.indexOf("[中]")).isLessThan(markdown.indexOf("[低]"));
        assertThat(markdown).doesNotContain("SQL 风险");
        assertThat(markdown).doesNotContain("安全风险");
        assertThat(markdown).doesNotContain("代码风格");
        assertThat(markdown).doesNotContain("审查报告");
        assertThat(markdown).doesNotContain("问题列表");
    }

    @Test
    void shouldFallbackToGenericLabelWhenIssueTypeZhIsMissing() {
        ReviewIssue issue = issue("HIGH", "SQL_RISK", null, "missing zh issue");

        String markdown = formatter.formatMarkdown(reviewTask("HIGH"), List.of(issue));

        assertThat(markdown).contains("#### 1. [高] 问题");
        assertThat(markdown).doesNotContain("SQL_RISK");
        assertThat(markdown).doesNotContain("SQL 风险");
    }

    @Test
    void shouldSanitizeUntrustedIssueMarkdown() {
        ReviewIssue issue = issue("HIGH", "SECURITY", "<!-- fake --> # injected", "malicious issue");
        issue.setFilePath("src/`Injected`.java");
        issue.setDescription("<!-- codepilot-ai-review:evil --> # fake heading [link](javascript:alert(1))");
        issue.setSuggestion("```shell\nrm -rf /\n```");

        String markdown = formatter.formatMarkdown(reviewTask("HIGH"), List.of(issue));

        assertThat(markdown).doesNotContain("<!-- codepilot-ai-review:evil -->");
        assertThat(markdown).doesNotContain("#### 1. [高] <!-- fake --> # injected");
        assertThat(markdown).contains("&lt;\\!\\-\\- codepilot\\-ai\\-review\\:evil \\-\\-&gt;");
        assertThat(markdown).contains("\\# fake heading");
        assertThat(markdown).contains("\\[link\\]\\(javascript\\:alert\\(1\\)\\)");
        assertThat(markdown).contains("\\`\\`\\`shell rm \\-rf / \\`\\`\\`");
        assertThat(markdown).contains("- **文件**: `src/'Injected'.java`");
    }

    @Test
    void shouldLimitVisibleIssuesToTwenty() {
        List<ReviewIssue> issues = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            issues.add(issue("LOW", "STYLE", "样式问题", "issue " + i));
        }

        String markdown = formatter.formatMarkdown(reviewTask("LOW"), issues);

        assertThat(markdown).contains("#### 20. [低] 样式问题");
        assertThat(markdown).doesNotContain("#### 21. [低] 样式问题");
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

    private ReviewIssue issue(String severity, String issueType, String issueTypeZh, String title) {
        ReviewIssue issue = new ReviewIssue();
        issue.setFilePath("src/main/java/Demo.java");
        issue.setLineNumber(42);
        issue.setIssueType(issueType);
        issue.setIssueTypeZh(issueTypeZh);
        issue.setSeverity(severity);
        issue.setTitle(title);
        issue.setDescription("description");
        issue.setSuggestion("suggestion");
        issue.setSource("LLM");
        return issue;
    }
}
