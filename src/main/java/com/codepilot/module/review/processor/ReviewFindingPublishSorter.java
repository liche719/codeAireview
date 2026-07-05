package com.codepilot.module.review.processor;

import com.codepilot.module.review.entity.ReviewIssue;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

class ReviewFindingPublishSorter {

    private final ReviewFindingScoreCalculator scoreCalculator;

    ReviewFindingPublishSorter(ReviewFindingScoreCalculator scoreCalculator) {
        this.scoreCalculator = scoreCalculator;
    }

    boolean hasPersistedRanking(List<ReviewIssue> issues) {
        boolean hasRankedSignal = false;
        for (ReviewIssue issue : issues) {
            if (issue == null
                    || issue.getFinalScore() == null
                    || !StringUtils.hasText(issue.getPublishDecision())
                    || !StringUtils.hasText(issue.getCommentChannel())) {
                return false;
            }
            if (issue.getFinalScore() != 0
                    || !"PUBLISH".equals(scoreCalculator.normalize(issue.getPublishDecision()))
                    || !"INLINE".equals(scoreCalculator.normalize(issue.getCommentChannel()))
                    || StringUtils.hasText(issue.getSuppressionReason())) {
                hasRankedSignal = true;
            }
        }
        return hasRankedSignal;
    }

    List<ReviewIssue> sortForPublish(List<ReviewIssue> issues) {
        return issues.stream()
                .sorted(Comparator
                        .comparingInt(this::sortDecisionPriority)
                        .thenComparing((ReviewIssue issue) -> issue.getFinalScore() == null
                                ? Integer.MIN_VALUE
                                : issue.getFinalScore(), Comparator.reverseOrder())
                        .thenComparingInt(scoreCalculator::severityPriority)
                        .thenComparingInt(scoreCalculator::sourcePriority)
                        .thenComparing(issue -> nullToEmpty(issue.getFilePath()))
                        .thenComparing(issue -> issue.getLineNumber() == null ? Integer.MAX_VALUE : issue.getLineNumber())
                        .thenComparing(issue -> scoreCalculator.normalize(issue.getIssueType()))
                        .thenComparing(issue -> scoreCalculator.normalize(issue.getTitle()))
                        .thenComparing(issue -> issue.getId() == null ? Long.MAX_VALUE : issue.getId()))
                .toList();
    }

    private int sortDecisionPriority(ReviewIssue issue) {
        return switch (scoreCalculator.normalize(issue == null ? null : issue.getPublishDecision())) {
            case "PUBLISH" -> 0;
            case "SUMMARY_ONLY" -> 1;
            case "SUPPRESS" -> 2;
            default -> 3;
        };
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
