package com.codepilot.module.review.processor;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.entity.ReviewIssue;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

record ReviewIssuePatchEvidence(Set<String> tokens, boolean hasHunk) {

    static ReviewIssuePatchEvidence from(String patch) {
        if (!StringUtils.hasText(patch)) {
            return new ReviewIssuePatchEvidence(Set.of(), false);
        }
        boolean hasHunk = false;
        Set<String> patchTokens = new LinkedHashSet<>();
        for (String line : patch.split("\\R")) {
            if (line.startsWith("@@")) {
                hasHunk = true;
                continue;
            }
            if (line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }
            if (line.startsWith("+") || line.startsWith("-")) {
                patchTokens.addAll(ReviewIssueTextTokens.tokens(line.substring(1)));
            }
        }
        return new ReviewIssuePatchEvidence(patchTokens, hasHunk);
    }

    boolean pathRiskAligned(ReviewIssue issue) {
        String filePath = issue == null ? null : issue.getFilePath();
        String issueType = issue == null ? "" : ReviewIssueTextTokens.normalizeUpper(issue.getIssueType());
        return switch (issueType) {
            case "SECURITY" -> ReviewFileClassifier.isSecuritySensitivePath(filePath)
                    || ReviewIssueTextTokens.containsAny(tokens, "auth", "security", "permission", "token", "secret", "credential");
            case "SQL_RISK" -> ReviewFileClassifier.isDatabasePath(filePath)
                    || ReviewIssueTextTokens.containsAny(tokens, "select", "update", "delete", "insert", "alter", "drop", "sql");
            case "TEST_MISSING" -> !ReviewFileClassifier.isTestPath(filePath);
            case "EXCEPTION_HANDLING" -> ReviewIssueTextTokens.containsAny(tokens, "throw", "catch", "exception", "error");
            case "LOGGING" -> ReviewIssueTextTokens.containsAny(tokens, "log", "logger", "warn", "error", "debug", "info");
            default -> false;
        };
    }
}
