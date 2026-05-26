package com.codepilot.module.review.processor;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewCommentBudgetAllocatorTest {

    @Test
    void shouldAllocateSummaryFindingsByBudget() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMaxSummaryFindings(1);
        ReviewCommentBudgetAllocator allocator = new ReviewCommentBudgetAllocator(properties);

        ReviewIssue high = issue("HIGH", "SECURITY", "PUBLISH", "INLINE", 90, "high");
        ReviewIssue low = issue("LOW", "STYLE", "PUBLISH", "SUMMARY", 10, "low");

        List<ReviewIssue> selected = allocator.allocateSummaryFindings(List.of(low, high));

        assertThat(selected).hasSize(1);
        assertThat(selected.getFirst().getTitle()).isEqualTo("high");
    }

    @Test
    void shouldAllocateInlineOnlyInlineEligibleFindings() {
        ReviewCommentBudgetAllocator allocator = new ReviewCommentBudgetAllocator(new ReviewProperties());

        ReviewIssue inlineIssue = issue("HIGH", "SECURITY", "PUBLISH", "INLINE", 90, "inline");
        ReviewIssue summaryOnlyIssue = issue("MEDIUM", "BUG_RISK", "PUBLISH", "SUMMARY", 70, "summary");

        List<ReviewIssue> selected = allocator.allocateInlineFindings(List.of(summaryOnlyIssue, inlineIssue), 10);

        assertThat(selected).extracting(ReviewIssue::getTitle).containsExactly("inline");
    }

    private ReviewIssue issue(
            String severity,
            String issueType,
            String publishDecision,
            String commentChannel,
            int score,
            String title
    ) {
        ReviewIssue issue = new ReviewIssue();
        issue.setSeverity(severity);
        issue.setIssueType(issueType);
        issue.setPublishDecision(publishDecision);
        issue.setCommentChannel(commentChannel);
        issue.setFinalScore(score);
        issue.setFilePath("src/main/java/Demo.java");
        issue.setLineNumber(10);
        issue.setTitle(title);
        issue.setDescription("description with enough length");
        issue.setSuggestion("suggestion with enough length");
        return issue;
    }
}
