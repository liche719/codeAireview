package com.codepilot.module.review.processor;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.diff.DiffLineMapper;
import com.codepilot.module.review.diff.DiffLineMapping;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.planner.ReviewPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewIssuePatchVerifier {

    private static final int MAX_RULE_REFERENCE_LENGTH = 255;

    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{2,}|[\\u4e00-\\u9fa5]{2,}");

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "this", "that", "with", "from", "into", "when", "where",
            "will", "would", "could", "should", "issue", "risk", "change", "changed", "file",
            "code", "method", "class", "value", "data", "null", "true", "false", "return",
            "private", "public", "protected", "static", "final", "void", "string", "integer"
    );

    private final DiffLineMapper diffLineMapper;

    public List<ReviewIssue> keepVerified(
            String reviewedFilePath,
            String patch,
            ReviewContext reviewContext,
            List<ReviewIssue> issues
    ) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        PatchEvidence patchEvidence = PatchEvidence.from(patch);
        if (!patchEvidence.hasHunk()) {
            return issues;
        }

        ReviewPlanEvidence planEvidence = ReviewPlanEvidence.from(reviewedFilePath, reviewContext);
        List<ReviewIssue> verifiedIssues = new ArrayList<>();
        int droppedCount = 0;
        for (ReviewIssue issue : issues) {
            VerificationResult result = verifyIssue(reviewedFilePath, patch, patchEvidence, planEvidence, issue);
            if (result.keep()) {
                annotateIssue(issue, result);
                verifiedIssues.add(issue);
            } else {
                droppedCount++;
                log.debug("Drop ungrounded AI review issue, filePath={}, issueType={}, severity={}, title={}",
                        issue == null ? null : issue.getFilePath(),
                        issue == null ? null : issue.getIssueType(),
                        issue == null ? null : issue.getSeverity(),
                        issue == null ? null : issue.getTitle());
            }
        }
        if (droppedCount > 0) {
            log.info("Patch verification dropped ungrounded AI review issue(s), filePath={}, droppedCount={}, keptCount={}",
                    reviewedFilePath, droppedCount, verifiedIssues.size());
        }
        return verifiedIssues;
    }

    private VerificationResult verifyIssue(
            String reviewedFilePath,
            String patch,
            PatchEvidence patchEvidence,
            ReviewPlanEvidence planEvidence,
            ReviewIssue issue
    ) {
        if (issue == null) {
            return VerificationResult.drop();
        }
        String source = normalize(issue.getSource());
        if ("TOOL".equals(source) || "SYSTEM".equals(source)) {
            return VerificationResult.keep("DETERMINISTIC_SOURCE", "deterministic source");
        }
        if (!StringUtils.hasText(reviewedFilePath)
                || !reviewedFilePath.equals(issue.getFilePath())) {
            return VerificationResult.drop();
        }

        DiffLineMapping mapping = issue.getLineNumber() == null
                ? DiffLineMapping.notCommentable()
                : diffLineMapper.map(issue.getFilePath(), patch, issue.getLineNumber());
        if (mapping.commentable()) {
            return VerificationResult.keep(
                    "PATCH_LINE",
                    "changed line " + mapping.line() + " on " + mapping.side()
            );
        }

        Set<String> issueTokens = tokens(issueText(issue));
        int patchOverlap = overlapCount(issueTokens, patchEvidence.tokens());
        if (patchOverlap >= 2) {
            return VerificationResult.keep("PATCH_TEXT", "matched changed patch tokens");
        }

        int planOverlap = overlapCount(issueTokens, planEvidence.tokens());
        if (planEvidence.alignsWithIssue(issue) && planOverlap >= 1) {
            return VerificationResult.keep("REVIEW_PLAN", "matched deterministic review plan risk");
        }

        if (isHighSignalIssueType(issue) && planEvidence.alignsWithIssue(issue) && patchEvidence.pathRiskAligned(issue)) {
            return VerificationResult.keep("PATCH_RISK_AREA", "matched changed file risk area");
        }

        return VerificationResult.drop();
    }

    private void annotateIssue(ReviewIssue issue, VerificationResult result) {
        if (issue == null || !"LLM".equals(normalize(issue.getSource())) || !"KEEP".equals(result.action())) {
            return;
        }
        String evidence = "PATCH_VERIFIED:" + result.reasonCode();
        issue.setRuleReference(appendBounded(issue.getRuleReference(), evidence, MAX_RULE_REFERENCE_LENGTH));
        String descriptionEvidence = "Evidence: " + result.explanation() + ".";
        if (!containsIgnoreCase(issue.getDescription(), "Evidence:")) {
            issue.setDescription(appendBounded(issue.getDescription(), descriptionEvidence, MAX_DESCRIPTION_LENGTH));
        }
    }

    private boolean isHighSignalIssueType(ReviewIssue issue) {
        return switch (normalize(issue.getIssueType())) {
            case "SECURITY", "SQL_RISK", "TEST_MISSING", "EXCEPTION_HANDLING", "LOGGING" -> true;
            default -> false;
        };
    }

    private String issueText(ReviewIssue issue) {
        return String.join(" ",
                nullToEmpty(issue.getIssueType()),
                nullToEmpty(issue.getTitle()),
                nullToEmpty(issue.getDescription()),
                nullToEmpty(issue.getSuggestion()),
                nullToEmpty(issue.getRuleReference()));
    }

    private int overlapCount(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String token : left) {
            if (right.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private static Set<String> tokens(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String appendBounded(String existing, String addition, int limit) {
        String merged = StringUtils.hasText(existing)
                ? existing.trim() + " | " + addition
                : addition;
        if (merged.length() <= limit) {
            return merged;
        }
        return merged.substring(0, Math.max(0, limit - 3)).trim() + "...";
    }

    private boolean containsIgnoreCase(String text, String needle) {
        return StringUtils.hasText(text)
                && StringUtils.hasText(needle)
                && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record VerificationResult(String action, String reasonCode, String explanation) {
        private static VerificationResult keep(String reasonCode, String explanation) {
            return new VerificationResult("KEEP", reasonCode, explanation);
        }

        private static VerificationResult drop() {
            return new VerificationResult("DROP", null, null);
        }

        private boolean keep() {
            return "KEEP".equals(action);
        }
    }

    private record PatchEvidence(Set<String> tokens, boolean hasHunk) {
        private static PatchEvidence from(String patch) {
            if (!StringUtils.hasText(patch)) {
                return new PatchEvidence(Set.of(), false);
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
                    patchTokens.addAll(ReviewIssuePatchVerifier.tokens(line.substring(1)));
                }
            }
            return new PatchEvidence(patchTokens, hasHunk);
        }

        private boolean pathRiskAligned(ReviewIssue issue) {
            String filePath = issue == null ? null : issue.getFilePath();
            String issueType = issue == null ? "" : normalizeStatic(issue.getIssueType());
            return switch (issueType) {
                case "SECURITY" -> ReviewFileClassifier.isSecuritySensitivePath(filePath)
                        || containsAny(tokens, "auth", "security", "permission", "token", "secret", "credential");
                case "SQL_RISK" -> ReviewFileClassifier.isDatabasePath(filePath)
                        || containsAny(tokens, "select", "update", "delete", "insert", "alter", "drop", "sql");
                case "TEST_MISSING" -> !ReviewFileClassifier.isTestPath(filePath);
                case "EXCEPTION_HANDLING" -> containsAny(tokens, "throw", "catch", "exception", "error");
                case "LOGGING" -> containsAny(tokens, "log", "logger", "warn", "error", "debug", "info");
                default -> false;
            };
        }
    }

    private record ReviewPlanEvidence(Set<String> tokens, Set<String> changeTypes, Set<String> riskTypes) {
        private static ReviewPlanEvidence from(String reviewedFilePath, ReviewContext reviewContext) {
            if (reviewContext == null || reviewContext.reviewPlan() == null || reviewContext.reviewPlan().isEmpty()) {
                return new ReviewPlanEvidence(Set.of(), Set.of(), Set.of());
            }
            ReviewPlan reviewPlan = reviewContext.reviewPlan();
            Set<String> planTokens = new LinkedHashSet<>();
            Set<String> changeTypes = new LinkedHashSet<>(reviewPlan.changeTypes());
            Set<String> riskTypes = new LinkedHashSet<>();

            reviewPlan.riskAreas().forEach(riskArea -> {
                riskTypes.add(riskArea.type());
                planTokens.addAll(ReviewIssuePatchVerifier.tokens(riskArea.type() + " " + riskArea.reason()));
            });
            reviewPlan.priorityFiles().stream()
                    .filter(priorityFile -> samePath(priorityFile.filePath(), reviewedFilePath))
                    .forEach(priorityFile -> planTokens.addAll(ReviewIssuePatchVerifier.tokens(String.join(" ", priorityFile.reasons()))));
            reviewPlan.fileFocuses().stream()
                    .filter(fileFocus -> samePath(fileFocus.filePath(), reviewedFilePath))
                    .forEach(fileFocus -> planTokens.addAll(ReviewIssuePatchVerifier.tokens(String.join(" ",
                            String.join(" ", fileFocus.focuses()),
                            String.join(" ", fileFocus.verificationHints()),
                            String.join(" ", fileFocus.relatedFiles())))));
            reviewPlan.crossFileFocuses().stream()
                    .filter(crossFileFocus -> crossFileFocus.files().stream()
                            .anyMatch(filePath -> samePath(filePath, reviewedFilePath)))
                    .forEach(crossFileFocus -> planTokens.addAll(ReviewIssuePatchVerifier.tokens(
                            crossFileFocus.type() + " " + crossFileFocus.reason() + " " + crossFileFocus.verificationHint()
                    )));
            planTokens.addAll(ReviewIssuePatchVerifier.tokens(String.join(" ", reviewPlan.verificationHints())));
            planTokens.addAll(ReviewIssuePatchVerifier.tokens(String.join(" ", reviewPlan.plannerWarnings())));
            return new ReviewPlanEvidence(planTokens, changeTypes, riskTypes);
        }

        private boolean alignsWithIssue(ReviewIssue issue) {
            String issueType = issue == null ? "" : normalizeStatic(issue.getIssueType());
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

    private static boolean containsAny(Set<String> values, String... needles) {
        for (String needle : needles) {
            if (values.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeStatic(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}
