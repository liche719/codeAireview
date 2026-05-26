package com.codepilot.module.review.processor;

import com.codepilot.module.review.entity.ReviewIssue;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ReviewFindingRanker {

    private static final int MIN_PUBLISH_SCORE = 30;

    private static final int MIN_INLINE_SCORE = 60;

    public List<ReviewIssue> rank(List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        List<RankedCandidate> candidates = new ArrayList<>();
        int originalIndex = 0;
        for (ReviewIssue issue : issues) {
            if (issue == null) {
                continue;
            }
            candidates.add(new RankedCandidate(issue, score(issue), duplicateFingerprint(issue), originalIndex++));
        }
        Map<String, RankedCandidate> duplicateWinners = duplicateWinners(candidates);
        List<ReviewIssue> ranked = new ArrayList<>();
        for (RankedCandidate candidate : candidates) {
            RankedCandidate winner = duplicateWinners.get(candidate.fingerprint());
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
        return sortForPublish(ranked);
    }

    public List<ReviewIssue> publishable(List<ReviewIssue> issues) {
        return orderForPublish(issues).stream()
                .filter(issue -> !"SUPPRESS".equals(normalize(issue.getPublishDecision())))
                .toList();
    }

    public List<ReviewIssue> orderForPublish(List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        List<ReviewIssue> presentIssues = issues.stream()
                .filter(issue -> issue != null)
                .toList();
        if (hasPersistedRanking(presentIssues)) {
            return sortForPublish(presentIssues);
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

    private int score(ReviewIssue issue) {
        int score = severityScore(issue) + sourceScore(issue) + issueTypeScore(issue) + groundingScore(issue);
        if ("SYSTEM".equals(normalize(issue.getSource()))) {
            score -= 10;
        }
        if ("STYLE".equals(normalize(issue.getIssueType())) && "LOW".equals(normalize(issue.getSeverity()))) {
            score -= 25;
        }
        if (!StringUtils.hasText(issue.getDescription()) || issue.getDescription().length() < 20) {
            score -= 10;
        }
        if (!StringUtils.hasText(issue.getSuggestion()) || issue.getSuggestion().length() < 10) {
            score -= 10;
        }
        if (issue.getLineNumber() == null && !"SYSTEM".equals(normalize(issue.getSource()))) {
            score -= 10;
        }
        return score;
    }

    private int severityScore(ReviewIssue issue) {
        return switch (normalize(issue == null ? null : issue.getSeverity())) {
            case "HIGH" -> 60;
            case "MEDIUM" -> 40;
            case "LOW" -> 20;
            default -> 10;
        };
    }

    private int sourceScore(ReviewIssue issue) {
        return switch (normalize(issue == null ? null : issue.getSource())) {
            case "TOOL" -> 30;
            case "SYSTEM" -> 20;
            case "LLM" -> 12;
            default -> 8;
        };
    }

    private int issueTypeScore(ReviewIssue issue) {
        return switch (normalize(issue == null ? null : issue.getIssueType())) {
            case "SECURITY" -> 24;
            case "SQL_RISK" -> 22;
            case "BUG_RISK" -> 18;
            case "EXCEPTION_HANDLING" -> 14;
            case "PERFORMANCE" -> 12;
            case "TEST_MISSING" -> 10;
            case "AI_REVIEW_FAILED" -> 8;
            case "LOGGING" -> 6;
            case "STYLE" -> -5;
            default -> 0;
        };
    }

    private int groundingScore(ReviewIssue issue) {
        String ruleReference = normalize(issue == null ? null : issue.getRuleReference());
        if (ruleReference.contains("PATCH_VERIFIED:PATCH_LINE")) {
            return 18;
        }
        if (ruleReference.contains("PATCH_VERIFIED:PATCH_TEXT")) {
            return 14;
        }
        if (ruleReference.contains("PATCH_VERIFIED:REVIEW_PLAN")) {
            return 10;
        }
        if (ruleReference.contains("PATCH_VERIFIED:PATCH_RISK_AREA")) {
            return 8;
        }
        if ("TOOL".equals(normalize(issue == null ? null : issue.getSource()))) {
            return 8;
        }
        return 0;
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

    private Map<String, RankedCandidate> duplicateWinners(List<RankedCandidate> candidates) {
        Map<String, RankedCandidate> winners = new HashMap<>();
        for (RankedCandidate candidate : candidates) {
            winners.merge(candidate.fingerprint(), candidate, this::betterDuplicateWinner);
        }
        return winners;
    }

    private RankedCandidate betterDuplicateWinner(RankedCandidate left, RankedCandidate right) {
        if (left.score() != right.score()) {
            return left.score() > right.score() ? left : right;
        }
        int leftSeverity = severityPriority(left.issue());
        int rightSeverity = severityPriority(right.issue());
        if (leftSeverity != rightSeverity) {
            return leftSeverity < rightSeverity ? left : right;
        }
        int leftSource = sourcePriority(left.issue());
        int rightSource = sourcePriority(right.issue());
        if (leftSource != rightSource) {
            return leftSource < rightSource ? left : right;
        }
        return left.originalIndex() <= right.originalIndex() ? left : right;
    }

    private boolean hasPersistedRanking(List<ReviewIssue> issues) {
        boolean hasRankedSignal = false;
        for (ReviewIssue issue : issues) {
            if (issue == null
                    || issue.getFinalScore() == null
                    || !StringUtils.hasText(issue.getPublishDecision())
                    || !StringUtils.hasText(issue.getCommentChannel())) {
                return false;
            }
            if (issue.getFinalScore() != 0
                    || !"PUBLISH".equals(normalize(issue.getPublishDecision()))
                    || !"INLINE".equals(normalize(issue.getCommentChannel()))
                    || StringUtils.hasText(issue.getSuppressionReason())) {
                hasRankedSignal = true;
            }
        }
        return hasRankedSignal;
    }

    private List<ReviewIssue> sortForPublish(List<ReviewIssue> issues) {
        return issues.stream()
                .sorted(Comparator
                        .comparingInt(this::sortDecisionPriority)
                        .thenComparing((ReviewIssue issue) -> issue.getFinalScore() == null ? Integer.MIN_VALUE : issue.getFinalScore(), Comparator.reverseOrder())
                        .thenComparingInt(this::severityPriority)
                        .thenComparingInt(this::sourcePriority)
                        .thenComparing(issue -> nullToEmpty(issue.getFilePath()))
                        .thenComparing(issue -> issue.getLineNumber() == null ? Integer.MAX_VALUE : issue.getLineNumber())
                        .thenComparing(issue -> normalize(issue.getIssueType()))
                        .thenComparing(issue -> normalize(issue.getTitle()))
                        .thenComparing(issue -> issue.getId() == null ? Long.MAX_VALUE : issue.getId()))
                .toList();
    }

    private int sortDecisionPriority(ReviewIssue issue) {
        return switch (normalize(issue == null ? null : issue.getPublishDecision())) {
            case "PUBLISH" -> 0;
            case "SUMMARY_ONLY" -> 1;
            case "SUPPRESS" -> 2;
            default -> 3;
        };
    }

    private int severityPriority(ReviewIssue issue) {
        return switch (normalize(issue == null ? null : issue.getSeverity())) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            case "LOW" -> 2;
            default -> 3;
        };
    }

    private int sourcePriority(ReviewIssue issue) {
        return switch (normalize(issue == null ? null : issue.getSource())) {
            case "TOOL" -> 0;
            case "SYSTEM" -> 1;
            case "LLM" -> 2;
            default -> 3;
        };
    }

    private String duplicateFingerprint(ReviewIssue issue) {
        return nullToEmpty(issue == null ? null : issue.getFilePath())
                + ":"
                + (issue == null ? null : issue.getLineNumber())
                + ":"
                + normalize(issue == null ? null : issue.getIssueType())
                + ":"
                + normalize(issue == null ? null : issue.getTitle());
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record RankedCandidate(ReviewIssue issue, int score, String fingerprint, int originalIndex) {
    }
}
