package com.codepilot.module.agent.review;

import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewResultMergerTest {

    private final ReviewResultMerger merger = new ReviewResultMerger(new ReviewIssueDeduplicator());

    @Test
    void shouldMergeLlmAndDeterministicIssuesWithDedupe() {
        AiReviewIssue llmIssue = issue("BUG_RISK", "HIGH", "same");
        AiReviewIssue duplicateToolIssue = issue("BUG_RISK", "HIGH", "same");
        AiReviewIssue toolIssue = issue("SQL_RISK", "HIGH", "sql");
        AiReviewResult llmResult = result("model summary", llmIssue);
        AiReviewResult deterministicResult = result("tool summary", duplicateToolIssue, toolIssue);

        AiReviewResult merged = merger.merge(llmResult, deterministicResult);

        assertThat(merged.getSummary()).isEqualTo("model summary");
        assertThat(merged.getIssues())
                .extracting(AiReviewIssue::getIssueType)
                .containsExactly("BUG_RISK", "SQL_RISK");
    }

    @Test
    void shouldUseDeterministicSummaryWhenPrimarySummaryIsBlank() {
        AiReviewResult llmResult = result(" ", issue("BUG_RISK", "LOW", "bug"));
        AiReviewResult deterministicResult = result("tool summary", issue("SQL_RISK", "HIGH", "sql"));

        AiReviewResult merged = merger.merge(llmResult, deterministicResult);

        assertThat(merged.getSummary()).isEqualTo("tool summary");
        assertThat(merged.getIssues()).hasSize(2);
    }

    @Test
    void shouldMergeIntoEmptyResultWhenPrimaryIsMissing() {
        AiReviewResult deterministicResult = result("tool summary", issue("SQL_RISK", "HIGH", "sql"));

        AiReviewResult merged = merger.merge(null, deterministicResult);

        assertThat(merged.getSummary()).isEqualTo("tool summary");
        assertThat(merged.getIssues()).singleElement()
                .satisfies(issue -> assertThat(issue.getIssueType()).isEqualTo("SQL_RISK"));
    }

    private static AiReviewResult result(String summary, AiReviewIssue... issues) {
        AiReviewResult result = new AiReviewResult();
        result.setSummary(summary);
        result.setIssues(List.of(issues));
        return result;
    }

    private static AiReviewIssue issue(String issueType, String severity, String title) {
        AiReviewIssue issue = new AiReviewIssue();
        issue.setFilePath("src/main/java/Demo.java");
        issue.setIssueType(issueType);
        issue.setSeverity(severity);
        issue.setTitle(title);
        issue.setDescription("description");
        return issue;
    }
}
