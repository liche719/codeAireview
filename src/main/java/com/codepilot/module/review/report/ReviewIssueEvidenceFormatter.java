package com.codepilot.module.review.report;

import com.codepilot.module.review.entity.ReviewIssue;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReviewIssueEvidenceFormatter {

    private static final Pattern PATCH_VERIFIED_PATTERN =
            Pattern.compile("\\bPATCH_VERIFIED:([A-Z_]+)\\b", Pattern.CASE_INSENSITIVE);

    private ReviewIssueEvidenceFormatter() {
    }

    public static String compactTrace(ReviewIssue issue) {
        if (issue == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(issue.getSource())) {
            parts.add("source=" + issue.getSource().trim().toUpperCase(Locale.ROOT));
        }
        String ruleReference = primaryRuleReference(issue.getRuleReference());
        if (StringUtils.hasText(ruleReference)) {
            parts.add("rule=" + ruleReference);
        }
        String grounding = groundingLabel(issue.getRuleReference());
        if (StringUtils.hasText(grounding)) {
            parts.add("grounding=" + grounding);
        }
        return String.join(", ", parts);
    }

    static String primaryRuleReference(String ruleReference) {
        if (!StringUtils.hasText(ruleReference)) {
            return "";
        }
        for (String part : ruleReference.split("\\|")) {
            String normalized = part.trim();
            if (StringUtils.hasText(normalized)
                    && !normalized.toUpperCase(Locale.ROOT).startsWith("PATCH_VERIFIED:")) {
                return normalized;
            }
        }
        return "";
    }

    static String groundingLabel(String ruleReference) {
        if (!StringUtils.hasText(ruleReference)) {
            return "";
        }
        Matcher matcher = PATCH_VERIFIED_PATTERN.matcher(ruleReference);
        if (!matcher.find()) {
            return "";
        }
        return switch (matcher.group(1).toUpperCase(Locale.ROOT)) {
            case "PATCH_LINE" -> "changed diff line";
            case "PATCH_TEXT" -> "changed patch tokens";
            case "REVIEW_PLAN" -> "semantic review plan";
            case "PATCH_RISK_AREA" -> "changed file risk area";
            case "DETERMINISTIC_SOURCE" -> "deterministic rule";
            default -> matcher.group(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }
}
