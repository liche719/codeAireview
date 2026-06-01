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

    @Test
    void shouldKeepToolFindingForSameLineSqlConcatenationDuplicate() {
        AiReviewIssue llmIssue = issue("SQL_RISK", "HIGH", "SQL risk");
        llmIssue.setLineNumber(6);
        llmIssue.setSource("LLM");
        llmIssue.setDescription("The query is built with string concatenation and can lead to SQL injection.");
        AiReviewIssue toolIssue = issue("SQL_RISK", "HIGH", "SQL string concatenation");
        toolIssue.setLineNumber(6);
        toolIssue.setSource("TOOL");
        toolIssue.setRuleReference("SQL_RISK_RULE");
        toolIssue.setDescription("Diff appears to construct SQL through string concatenation.");

        AiReviewResult merged = merger.merge(result("model summary", llmIssue), result("tool summary", toolIssue));

        assertThat(merged.getIssues()).singleElement()
                .satisfies(issue -> {
                    assertThat(issue.getSource()).isEqualTo("TOOL");
                    assertThat(issue.getTitle()).isEqualTo("SQL string concatenation");
                });
    }

    @Test
    void shouldKeepDifferentSqlSubtypesOnSameLine() {
        AiReviewIssue concatIssue = issue("SQL_RISK", "HIGH", "SQL string concatenation");
        concatIssue.setLineNumber(6);
        concatIssue.setSource("TOOL");
        concatIssue.setDescription("Diff appears to construct SQL through string concatenation.");
        AiReviewIssue selectAllIssue = issue("SQL_RISK", "LOW", "SQL query uses SELECT *");
        selectAllIssue.setLineNumber(6);
        selectAllIssue.setSource("TOOL");
        selectAllIssue.setDescription("Diff contains SELECT *.");

        AiReviewResult merged = merger.merge(null, result("tool summary", concatIssue, selectAllIssue));

        assertThat(merged.getIssues())
                .extracting(AiReviewIssue::getTitle)
                .containsExactly("SQL string concatenation", "SQL query uses SELECT *");
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
