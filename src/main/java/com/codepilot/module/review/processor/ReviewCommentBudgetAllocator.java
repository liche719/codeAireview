package com.codepilot.module.review.processor;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewIssue;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class ReviewCommentBudgetAllocator {

    private final ReviewProperties reviewProperties;

    public ReviewCommentBudgetAllocator(ReviewProperties reviewProperties) {
        this.reviewProperties = reviewProperties;
    }

    public List<ReviewIssue> allocateSummaryFindings(List<ReviewIssue> rankedIssues) {
        return allocate(rankedIssues, summaryBudget(), this::isSummaryEligible);
    }

    public List<ReviewIssue> allocateInlineFindings(List<ReviewIssue> rankedIssues, int inlineBudget) {
        return allocate(rankedIssues, inlineBudget, this::isInlineEligible);
    }

    private List<ReviewIssue> allocate(List<ReviewIssue> rankedIssues, int budget, FindingFilter filter) {
        if (rankedIssues == null || rankedIssues.isEmpty() || budget <= 0) {
            return List.of();
        }
        List<ReviewIssue> selected = new ArrayList<>();
        List<ReviewIssue> ordered = rankedIssues.stream()
                .filter(issue -> issue != null)
                .sorted(Comparator
                        .comparingInt(this::score).reversed()
                        .thenComparing(issue -> nullToEmpty(issue.getFilePath()))
                        .thenComparing(issue -> issue.getLineNumber() == null ? Integer.MAX_VALUE : issue.getLineNumber()))
                .toList();
        for (ReviewIssue issue : ordered) {
            if (issue == null || !filter.accept(issue)) {
                continue;
            }
            selected.add(issue);
            if (selected.size() >= budget) {
                break;
            }
        }
        return selected.stream()
                .sorted(Comparator
                        .comparingInt(this::score)
                        .reversed()
                        .thenComparing(issue -> nullToEmpty(issue.getFilePath()))
                        .thenComparing(issue -> issue.getLineNumber() == null ? Integer.MAX_VALUE : issue.getLineNumber()))
                .toList();
    }

    private boolean isSummaryEligible(ReviewIssue issue) {
        return isPublishable(issue);
    }

    private boolean isInlineEligible(ReviewIssue issue) {
        return isPublishable(issue) && "INLINE".equals(normalize(issue.getCommentChannel()));
    }

    private boolean isPublishable(ReviewIssue issue) {
        return issue != null && !"SUPPRESS".equals(normalize(issue.getPublishDecision()));
    }

    private int summaryBudget() {
        int configured = reviewProperties == null ? 0 : reviewProperties.getMaxSummaryFindings();
        return Math.max(0, configured);
    }

    private int score(ReviewIssue issue) {
        return issue == null || issue.getFinalScore() == null ? Integer.MIN_VALUE : issue.getFinalScore();
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private interface FindingFilter {
        boolean accept(ReviewIssue issue);
    }
}
