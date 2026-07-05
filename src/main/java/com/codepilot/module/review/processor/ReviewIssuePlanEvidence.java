package com.codepilot.module.review.processor;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.planner.ReviewPlan;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

record ReviewIssuePlanEvidence(Set<String> tokens, Set<String> changeTypes, Set<String> riskTypes) {

    static ReviewIssuePlanEvidence from(String reviewedFilePath, ReviewContext reviewContext) {
        if (reviewContext == null || reviewContext.reviewPlan() == null || reviewContext.reviewPlan().isEmpty()) {
            return new ReviewIssuePlanEvidence(Set.of(), Set.of(), Set.of());
        }
        ReviewPlan reviewPlan = reviewContext.reviewPlan();
        Set<String> planTokens = new LinkedHashSet<>();
        Set<String> changeTypes = new LinkedHashSet<>(reviewPlan.changeTypes());
        Set<String> riskTypes = new LinkedHashSet<>();

        reviewPlan.riskAreas().forEach(riskArea -> {
            riskTypes.add(riskArea.type());
            planTokens.addAll(ReviewIssueTextTokens.tokens(riskArea.type() + " " + riskArea.reason()));
        });
        reviewPlan.priorityFiles().stream()
                .filter(priorityFile -> samePath(priorityFile.filePath(), reviewedFilePath))
                .forEach(priorityFile -> planTokens.addAll(ReviewIssueTextTokens.tokens(String.join(" ", priorityFile.reasons()))));
        reviewPlan.fileFocuses().stream()
                .filter(fileFocus -> samePath(fileFocus.filePath(), reviewedFilePath))
                .forEach(fileFocus -> planTokens.addAll(ReviewIssueTextTokens.tokens(String.join(" ",
                        String.join(" ", fileFocus.focuses()),
                        String.join(" ", fileFocus.verificationHints()),
                        String.join(" ", fileFocus.relatedFiles())))));
        reviewPlan.crossFileFocuses().stream()
                .filter(crossFileFocus -> crossFileFocus.files().stream()
                        .anyMatch(filePath -> samePath(filePath, reviewedFilePath)))
                .forEach(crossFileFocus -> planTokens.addAll(ReviewIssueTextTokens.tokens(
                        crossFileFocus.type() + " " + crossFileFocus.reason() + " " + crossFileFocus.verificationHint()
                )));
        planTokens.addAll(ReviewIssueTextTokens.tokens(String.join(" ", reviewPlan.verificationHints())));
        planTokens.addAll(ReviewIssueTextTokens.tokens(String.join(" ", reviewPlan.plannerWarnings())));
        return new ReviewIssuePlanEvidence(planTokens, changeTypes, riskTypes);
    }

    boolean alignsWithIssue(ReviewIssue issue) {
        String issueType = issue == null ? "" : ReviewIssueTextTokens.normalizeUpper(issue.getIssueType());
        return switch (issueType) {
            case "SECURITY" -> containsRisk("security") || containsChange("security");
            case "SQL_RISK" -> containsRisk("database") || containsChange("database");
            case "TEST_MISSING" -> containsRisk("test") || containsChange("test");
            case "PERFORMANCE" -> containsRisk("performance") || tokens.contains("performance");
            case "EXCEPTION_HANDLING" -> tokens.contains("exception") || tokens.contains("error");
            case "LOGGING" -> tokens.contains("logging") || tokens.contains("logger");
            default -> !tokens.isEmpty();
        };
    }

    private boolean containsRisk(String needle) {
        return riskTypes.stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(needle));
    }

    private boolean containsChange(String needle) {
        return changeTypes.stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static boolean samePath(String left, String right) {
        return ReviewFileClassifier.normalizePath(left).equals(ReviewFileClassifier.normalizePath(right));
    }
}
