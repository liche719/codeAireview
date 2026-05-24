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

        List<ReviewIssue> reviewIssues = assembler.toReviewIssues(
                1L,
                "src/main/java/Demo.java",
                new AiReviewResult(List.of(issue), null)
        );

        assertThat(reviewIssues.getFirst().getFilePath()).isEqualTo("src/main/java/Demo.java");
        assertThat(reviewIssues.getFirst().getSeverity()).isEqualTo("LOW");
        assertThat(reviewIssues.getFirst().getSource()).isEqualTo("LLM");
    }

    @Test
    void shouldFallbackToModelFilePathWhenDefaultPathIsMissing() {
        AiReviewIssue issue = new AiReviewIssue();
        issue.setFilePath("src/main/java/Other.java");

        List<ReviewIssue> reviewIssues = assembler.toReviewIssues(
                1L,
                null,
                new AiReviewResult(List.of(issue), null)
        );

        assertThat(reviewIssues.getFirst().getFilePath()).isEqualTo("src/main/java/Other.java");
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
}
