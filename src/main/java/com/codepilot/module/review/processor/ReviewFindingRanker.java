package com.codepilot.module.review.processor;

import com.codepilot.module.review.entity.ReviewIssue;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ReviewFindingRanker {

    private static final int MIN_PUBLISH_SCORE = 30;

    private static final int MIN_INLINE_SCORE = 60;

    public List<ReviewIssue> rank(List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        List<ReviewIssue> ranked = new ArrayList<>();
        Set<String> seenFingerprints = new HashSet<>();
        for (ReviewIssue issue : issues) {
            if (issue == null) {
                continue;
            }
            int score = score(issue);
            String fingerprint = duplicateFingerprint(issue);
            if (!seenFingerprints.add(fingerprint)) {
                applyDecision(issue, score - 20, "SUPPRESS", "duplicate finding", "NONE");
                ranked.add(issue);
                continue;
            }
            applyDecision(issue, score, publishDecision(score), suppressionReason(score), commentChannel(score, issue));
            ranked.add(issue);
        }
        return ranked.stream()
                .sorted(Comparator
                        .comparingInt(this::sortDecisionPriority)
                        .thenComparing((ReviewIssue issue) -> issue.getFinalScore() == null ? Integer.MIN_VALUE : issue.getFinalScore(), Comparator.reverseOrder())
                        .thenComparingInt(this::severityPriority)
                        .thenComparingInt(this::sourcePriority))
                .toList();
    }

    public List<ReviewIssue> publishable(List<ReviewIssue> issues) {
        return rank(issues).stream()
                .filter(issue -> !"SUPPRESS".equals(normalize(issue.getPublishDecision())))
                .toList();
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
}
