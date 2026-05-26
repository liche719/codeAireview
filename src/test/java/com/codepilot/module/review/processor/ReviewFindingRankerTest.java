package com.codepilot.module.review.processor;

import com.codepilot.module.review.entity.ReviewIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewFindingRankerTest {

    private final ReviewFindingRanker ranker = new ReviewFindingRanker();

    @Test
    void shouldRankHighValueToolSecurityFindingFirst() {
        ReviewIssue low = issue("LOW", "STYLE", "LLM", null, "style");
        ReviewIssue high = issue("HIGH", "SECURITY", "TOOL", "PATCH_VERIFIED:PATCH_LINE", "security");

        List<ReviewIssue> ranked = ranker.rank(List.of(low, high));

        assertThat(ranked).extracting(ReviewIssue::getTitle).containsExactly("security", "style");
        assertThat(ranked.getFirst().getFinalScore()).isGreaterThan(ranked.get(1).getFinalScore());
        assertThat(ranked.getFirst().getPublishDecision()).isEqualTo("PUBLISH");
        assertThat(ranked.getFirst().getCommentChannel()).isEqualTo("INLINE");
    }

    @Test
    void shouldSuppressLowValueStyleFinding() {
        ReviewIssue issue = issue("LOW", "STYLE", "LLM", null, "style");

        List<ReviewIssue> ranked = ranker.rank(List.of(issue));

        assertThat(ranked.getFirst().getPublishDecision()).isEqualTo("SUPPRESS");
        assertThat(ranked.getFirst().getSuppressionReason()).isEqualTo("below publish score threshold");
        assertThat(ranked.getFirst().getCommentChannel()).isEqualTo("NONE");
    }

    @Test
    void shouldSuppressDuplicateFindingByFingerprint() {
        ReviewIssue first = issue("HIGH", "SECURITY", "TOOL", "PATCH_VERIFIED:PATCH_LINE", "security");
        ReviewIssue duplicate = issue("HIGH", "SECURITY", "LLM", null, "security");
        duplicate.setFilePath(first.getFilePath());
        duplicate.setLineNumber(first.getLineNumber());

        List<ReviewIssue> ranked = ranker.rank(List.of(first, duplicate));

        assertThat(ranked.get(1).getPublishDecision()).isEqualTo("SUPPRESS");
        assertThat(ranked.get(1).getSuppressionReason()).isEqualTo("duplicate finding");
    }

    private ReviewIssue issue(String severity, String issueType, String source, String ruleReference, String title) {
        ReviewIssue issue = new ReviewIssue();
        issue.setFilePath("src/main/java/Demo.java");
        issue.setLineNumber(10);
        issue.setSeverity(severity);
        issue.setIssueType(issueType);
        issue.setSource(source);
        issue.setRuleReference(ruleReference);
        issue.setTitle(title);
        issue.setDescription("This is a sufficiently detailed description of the issue.");
        issue.setSuggestion("Use a safer implementation.");
        return issue;
    }
}
