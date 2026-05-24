package com.codepilot.module.review.assembler;

import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.review.entity.ReviewIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewIssueAssemblerTest {

    private final ReviewIssueAssembler assembler = new ReviewIssueAssembler();

    @Test
    void shouldMapAiIssuesAndApplyDefaults() {
        AiReviewIssue issue = new AiReviewIssue();
        issue.setLineNumber(42);
        issue.setIssueType("SECURITY");
        issue.setIssueTypeZh("安全");
        issue.setSeverity("high");
        issue.setTitle("Potential leak");
        issue.setDescription("Token is printed.");
        issue.setSuggestion("Do not print secrets.");
        issue.setSource("tool");
        issue.setRuleReference("SEC-1");

        List<ReviewIssue> reviewIssues = assembler.toReviewIssues(
                9L,
                "src/main/java/Demo.java",
                new AiReviewResult(List.of(issue), null)
        );

        assertThat(reviewIssues).hasSize(1);
        ReviewIssue reviewIssue = reviewIssues.getFirst();
        assertThat(reviewIssue.getTaskId()).isEqualTo(9L);
        assertThat(reviewIssue.getFilePath()).isEqualTo("src/main/java/Demo.java");
        assertThat(reviewIssue.getLineNumber()).isEqualTo(42);
        assertThat(reviewIssue.getIssueType()).isEqualTo("SECURITY");
        assertThat(reviewIssue.getIssueTypeZh()).isEqualTo("安全");
        assertThat(reviewIssue.getSeverity()).isEqualTo("HIGH");
        assertThat(reviewIssue.getTitle()).isEqualTo("Potential leak");
        assertThat(reviewIssue.getDescription()).isEqualTo("Token is printed.");
        assertThat(reviewIssue.getSuggestion()).isEqualTo("Do not print secrets.");
        assertThat(reviewIssue.getSource()).isEqualTo("TOOL");
        assertThat(reviewIssue.getRuleReference()).isEqualTo("SEC-1");
        assertThat(reviewIssue.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldForceIssuesToReviewedFileScopeWhenDefaultPathIsAvailable() {
        AiReviewIssue issue = new AiReviewIssue();
        issue.setFilePath("src/main/java/Other.java");
        issue.setSource("scanner");
        issue.setIssueType("SOMETHING_ELSE");
        issue.setSeverity("CRITICAL");
        issue.setTitle("Fallback title");
        issue.setDescription("Fallback description with enough detail.");
        issue.setSuggestion("Fallback suggestion with enough detail.");

        List<ReviewIssue> reviewIssues = assembler.toReviewIssues(
                1L,
                "src/main/java/Demo.java",
                new AiReviewResult(List.of(issue), null)
        );

        assertThat(reviewIssues.getFirst().getFilePath()).isEqualTo("src/main/java/Demo.java");
        assertThat(reviewIssues.getFirst().getIssueType()).isEqualTo("BUG_RISK");
        assertThat(reviewIssues.getFirst().getSeverity()).isEqualTo("LOW");
        assertThat(reviewIssues.getFirst().getSource()).isEqualTo("LLM");
    }

    @Test
    void shouldFallbackToModelFilePathWhenDefaultPathIsMissing() {
        AiReviewIssue issue = new AiReviewIssue();
        issue.setFilePath("src/main/java/Other.java");
        issue.setIssueType("BUG_RISK");
        issue.setSeverity("MEDIUM");
        issue.setTitle("Potential null path bug");
        issue.setDescription("The changed path can be null when the request is malformed.");
        issue.setSuggestion("Guard null input before using the changed path.");

        List<ReviewIssue> reviewIssues = assembler.toReviewIssues(
                1L,
                null,
                new AiReviewResult(List.of(issue), null)
        );

        assertThat(reviewIssues.getFirst().getFilePath()).isEqualTo("src/main/java/Other.java");
    }

    @Test
    void shouldDropUnpublishableLlmIssuesBeforePersistence() {
        AiReviewIssue blankTitle = issue("BUG_RISK", "HIGH", " ");
        AiReviewIssue weakNit = issue("STYLE", "LOW", "nit");
        weakNit.setDescription("This is only a vague formatting note.");
        weakNit.setSuggestion("Maybe polish it.");
        AiReviewIssue duplicateText = issue("BUG_RISK", "MEDIUM", "Duplicate text");
        duplicateText.setDescription("The same text appears in both fields.");
        duplicateText.setSuggestion("The same text appears in both fields.");
        AiReviewIssue valid = issue("SECURITY", "HIGH", "Potential token leak");
        valid.setDescription("The new log message can expose authentication token values.");
        valid.setSuggestion("Remove the token from the log message or redact it before logging.");

        List<ReviewIssue> reviewIssues = assembler.toReviewIssues(
                1L,
                "src/main/java/Demo.java",
                new AiReviewResult(List.of(blankTitle, weakNit, duplicateText, valid), null)
        );

        assertThat(reviewIssues)
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.getIssueType()).isEqualTo("SECURITY");
                    assertThat(issue.getTitle()).isEqualTo("Potential token leak");
                });
    }

    @Test
    void shouldKeepToolIssuesWhenTheyArePublishable() {
        AiReviewIssue toolIssue = issue("SQL_RISK", "HIGH", "SQL concatenation");
        toolIssue.setSource("TOOL");
        toolIssue.setDescription("The added SQL statement concatenates request input into a query.");
        toolIssue.setSuggestion("Use parameter binding instead of string concatenation.");

        List<ReviewIssue> reviewIssues = assembler.toReviewIssues(
                1L,
                "src/main/java/Demo.java",
                new AiReviewResult(List.of(toolIssue), null)
        );

        assertThat(reviewIssues)
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.getIssueType()).isEqualTo("SQL_RISK");
                    assertThat(issue.getSource()).isEqualTo("TOOL");
                });
    }

    @Test
    void shouldSanitizeAndTruncateIssueTextBeforePersistence() {
        AiReviewIssue issue = issue("SECURITY", "HIGH", "Token leak");
        issue.setTitle("Authorization: Bearer abcdefghijklmnop");
        issue.setDescription("password=\"super-secret\" " + "x".repeat(2100));
        issue.setSuggestion("Remove token abcdefghijklmnop from logs and rotate it.");
        issue.setRuleReference("rule-" + "r".repeat(300));

        List<ReviewIssue> reviewIssues = assembler.toReviewIssues(
                1L,
                "src/main/java/Demo.java",
                new AiReviewResult(List.of(issue), null)
        );

        ReviewIssue reviewIssue = reviewIssues.getFirst();
        assertThat(reviewIssue.getTitle()).contains("[REDACTED]");
        assertThat(reviewIssue.getDescription()).contains("[REDACTED]");
        assertThat(reviewIssue.getDescription()).hasSizeLessThanOrEqualTo(2000);
        assertThat(reviewIssue.getRuleReference()).hasSizeLessThanOrEqualTo(255);
    }

    @Test
    void shouldCalculateWorstRiskLevel() {
        assertThat(assembler.calculateRiskLevel(List.of(issue("LOW"), issue("HIGH"), issue("MEDIUM"))))
                .isEqualTo("HIGH");
        assertThat(assembler.calculateRiskLevel(List.of(issue("LOW")))).isEqualTo("LOW");
        assertThat(assembler.calculateRiskLevel(List.of())).isEqualTo("PASS");
    }

    private ReviewIssue issue(String severity) {
        ReviewIssue reviewIssue = new ReviewIssue();
        reviewIssue.setSeverity(severity);
        return reviewIssue;
    }

    private AiReviewIssue issue(String issueType, String severity, String title) {
        AiReviewIssue issue = new AiReviewIssue();
        issue.setIssueType(issueType);
        issue.setIssueTypeZh("风险");
        issue.setSeverity(severity);
        issue.setTitle(title);
        issue.setDescription("Description with enough useful context.");
        issue.setSuggestion("Suggestion with useful action.");
        issue.setSource("LLM");
        return issue;
    }
}
