package com.codepilot.module.review.processor;

import com.codepilot.module.review.entity.ReviewIssue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ReviewFindingRanker {

    private static final int MIN_PUBLISH_SCORE = 30;

    private static final int MIN_INLINE_SCORE = 60;

    private final ReviewFindingScoreCalculator scoreCalculator = new ReviewFindingScoreCalculator();

    private final ReviewFindingDuplicateResolver duplicateResolver =
            new ReviewFindingDuplicateResolver(scoreCalculator);

    private final ReviewFindingPublishSorter publishSorter =
            new ReviewFindingPublishSorter(scoreCalculator);

    public List<ReviewIssue> rank(List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        List<ReviewFindingRankedCandidate> candidates = new ArrayList<>();
        int originalIndex = 0;
        for (ReviewIssue issue : issues) {
            if (issue == null) {
                continue;
            }
            candidates.add(new ReviewFindingRankedCandidate(
                    issue,
                    scoreCalculator.score(issue),
                    duplicateResolver.fingerprint(issue),
                    originalIndex++
            ));
        }
        Map<String, ReviewFindingRankedCandidate> duplicateWinners = duplicateResolver.duplicateWinners(candidates);
        List<ReviewIssue> ranked = new ArrayList<>();
        for (ReviewFindingRankedCandidate candidate : candidates) {
            ReviewFindingRankedCandidate winner = duplicateWinners.get(candidate.fingerprint());
            if (winner != null && winner != candidate) {
                applyDecision(candidate.issue(), candidate.score() - 20, "SUPPRESS", "duplicate finding", "NONE");
            } else {
                applyDecision(
                        candidate.issue(),
                        candidate.score(),
                        publishDecision(candidate.score()),
                        suppressionReason(candidate.score()),
                        commentChannel(candidate.score(), candidate.issue())
                );
            }
            ranked.add(candidate.issue());
        }
        return publishSorter.sortForPublish(ranked);
    }

    public List<ReviewIssue> publishable(List<ReviewIssue> issues) {
        return orderForPublish(issues).stream()
                .filter(issue -> !"SUPPRESS".equals(scoreCalculator.normalize(issue.getPublishDecision())))
                .toList();
    }

    public List<ReviewIssue> orderForPublish(List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        List<ReviewIssue> presentIssues = issues.stream()
                .filter(issue -> issue != null)
                .toList();
        if (publishSorter.hasPersistedRanking(presentIssues)) {
            return publishSorter.sortForPublish(presentIssues);
        }
        return rank(presentIssues);
    }

    private void applyDecision(
            ReviewIssue issue,
            int finalScore,
            String publishDecision,
            String suppressionReason,
            String commentChannel
    ) {
        issue.setFinalScore(Math.max(0, finalScore));
        issue.setPublishDecision(publishDecision);
        issue.setSuppressionReason(suppressionReason);
        issue.setCommentChannel(commentChannel);
    }

    private String publishDecision(int score) {
        return score >= MIN_PUBLISH_SCORE ? "PUBLISH" : "SUPPRESS";
    }

    private String suppressionReason(int score) {
        return score >= MIN_PUBLISH_SCORE ? null : "below publish score threshold";
    }

    private String commentChannel(int score, ReviewIssue issue) {
        if (score < MIN_PUBLISH_SCORE) {
            return "NONE";
        }
        if (issue == null || issue.getLineNumber() == null) {
            return "SUMMARY";
        }
        return score >= MIN_INLINE_SCORE ? "INLINE" : "SUMMARY";
    }
}
