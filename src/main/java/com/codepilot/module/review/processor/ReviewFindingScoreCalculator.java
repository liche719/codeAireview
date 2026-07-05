package com.codepilot.module.review.processor;

import com.codepilot.module.review.entity.ReviewIssue;
import org.springframework.util.StringUtils;

import java.util.Locale;

class ReviewFindingScoreCalculator {

    int score(ReviewIssue issue) {
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

    int severityPriority(ReviewIssue issue) {
        return switch (normalize(issue == null ? null : issue.getSeverity())) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            case "LOW" -> 2;
            default -> 3;
        };
    }

    int sourcePriority(ReviewIssue issue) {
        return switch (normalize(issue == null ? null : issue.getSource())) {
            case "TOOL" -> 0;
            case "SYSTEM" -> 1;
            case "LLM" -> 2;
            default -> 3;
        };
    }

    String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
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
}
