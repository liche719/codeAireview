package com.codepilot.module.review.processor;

import com.codepilot.module.review.dedupe.ReviewIssueDuplicateKey;
import com.codepilot.module.review.entity.ReviewIssue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ReviewFindingDuplicateResolver {

    private final ReviewFindingScoreCalculator scoreCalculator;

    ReviewFindingDuplicateResolver(ReviewFindingScoreCalculator scoreCalculator) {
        this.scoreCalculator = scoreCalculator;
    }

    Map<String, ReviewFindingRankedCandidate> duplicateWinners(List<ReviewFindingRankedCandidate> candidates) {
        Map<String, ReviewFindingRankedCandidate> winners = new HashMap<>();
        for (ReviewFindingRankedCandidate candidate : candidates) {
            winners.merge(candidate.fingerprint(), candidate, this::betterDuplicateWinner);
        }
        return winners;
    }

    String fingerprint(ReviewIssue issue) {
        if (issue == null) {
            return ReviewIssueDuplicateKey.key(null, null, null, null, null, null, null);
        }
        return ReviewIssueDuplicateKey.key(
                issue.getFilePath(),
                issue.getLineNumber(),
                issue.getIssueType(),
                issue.getIssueTypeZh(),
                issue.getTitle(),
                issue.getDescription(),
                issue.getSuggestion()
        );
    }

    private ReviewFindingRankedCandidate betterDuplicateWinner(
            ReviewFindingRankedCandidate left,
            ReviewFindingRankedCandidate right
    ) {
        if (left.score() != right.score()) {
            return left.score() > right.score() ? left : right;
        }
        int leftSeverity = scoreCalculator.severityPriority(left.issue());
        int rightSeverity = scoreCalculator.severityPriority(right.issue());
        if (leftSeverity != rightSeverity) {
            return leftSeverity < rightSeverity ? left : right;
        }
        int leftSource = scoreCalculator.sourcePriority(left.issue());
        int rightSource = scoreCalculator.sourcePriority(right.issue());
        if (leftSource != rightSource) {
            return leftSource < rightSource ? left : right;
        }
        return left.originalIndex() <= right.originalIndex() ? left : right;
    }
}
