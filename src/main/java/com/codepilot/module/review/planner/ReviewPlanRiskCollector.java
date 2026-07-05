package com.codepilot.module.review.planner;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.context.ReviewContext;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class ReviewPlanRiskCollector {

    RiskProfile collect(
            ReviewContext.ReviewImpactPlan reviewImpactPlan,
            List<ReviewContext.FileSummary> fileSummaries,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints,
            List<ReviewContext.ReviewSignal> reviewSignals,
            List<ReviewContext.LinkedIssueContext> linkedIssueContexts
    ) {
        LinkedHashSet<String> changeTypes = new LinkedHashSet<>(reviewImpactPlan.changeTypes());
        LinkedHashMap<String, ReviewPlan.RiskArea> riskAreas = new LinkedHashMap<>();
        collectSignalRisks(reviewSignals, changeTypes, riskAreas);
        collectFileRisks(fileSummaries, semanticFileContexts, changeTypes, riskAreas);
        collectRelationshipRisks(repoRelationshipHints, riskAreas);
        collectLinkedIssueRisks(linkedIssueContexts, changeTypes, riskAreas);
        return new RiskProfile(List.copyOf(changeTypes), List.copyOf(riskAreas.values()));
    }

    private void collectSignalRisks(
            List<ReviewContext.ReviewSignal> reviewSignals,
            Set<String> changeTypes,
            Map<String, ReviewPlan.RiskArea> riskAreas
    ) {
        for (ReviewContext.ReviewSignal signal : reviewSignals) {
            String type = upper(signal.type());
            switch (type) {
                case "DATABASE_CHANGE" -> {
                    changeTypes.add("database-change");
                    addRiskArea(riskAreas, "database-safety", signal.severity(), signal.message());
                }
                case "SECURITY_SENSITIVE_CHANGE" -> {
                    changeTypes.add("security-sensitive-change");
                    addRiskArea(riskAreas, "security-boundary", "HIGH", signal.message());
                }
                case "CONFIG_CHANGE" -> {
                    changeTypes.add("configuration-change");
                    addRiskArea(riskAreas, "runtime-configuration", signal.severity(), signal.message());
                }
                case "DEPENDENCY_CHANGE" -> {
                    changeTypes.add("dependency-or-build-change");
                    addRiskArea(riskAreas, "supply-chain-compatibility", signal.severity(), signal.message());
                }
                case "PUBLIC_API_CHANGE" -> {
                    changeTypes.add("public-api-change");
                    addRiskArea(riskAreas, "api-contract", "HIGH", signal.message());
                }
                case "MISSING_TEST_CHANGE" -> addRiskArea(
                        riskAreas,
                        "test-coverage-gap",
                        signal.severity(),
                        signal.message()
                );
                case "LARGE_PR" -> addRiskArea(
                        riskAreas,
                        "large-review-scope",
                        signal.severity(),
                        signal.message()
                );
                case "SKIPPED_FILES" -> addRiskArea(
                        riskAreas,
                        "review-completeness",
                        signal.severity(),
                        signal.message()
                );
                default -> {
                    if (StringUtils.hasText(signal.message())) {
                        addRiskArea(riskAreas, lowerOrUnknown(signal.type()), signal.severity(), signal.message());
                    }
                }
            }
        }
    }

    private void collectFileRisks(
            List<ReviewContext.FileSummary> fileSummaries,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            Set<String> changeTypes,
            Map<String, ReviewPlan.RiskArea> riskAreas
    ) {
        for (ReviewContext.FileSummary fileSummary : fileSummaries) {
            String path = fileSummary.filePath();
            if (!fileSummary.reviewable()) {
                continue;
            }
            if (ReviewFileClassifier.isDatabasePath(path)) {
                changeTypes.add("database-change");
                addRiskArea(riskAreas, "database-safety", "HIGH", "Database or migration file changed.");
            }
            if (ReviewFileClassifier.isSecuritySensitivePath(path)) {
                changeTypes.add("security-sensitive-change");
                addRiskArea(riskAreas, "security-boundary", "HIGH", "Security-sensitive path changed.");
            }
            if (ReviewFileClassifier.isPublicApiPath(path)) {
                changeTypes.add("public-api-change");
                addRiskArea(riskAreas, "api-contract", "HIGH", "Public API surface path changed.");
            }
            if (ReviewFileClassifier.isConfigurationPath(path)) {
                changeTypes.add("configuration-change");
                addRiskArea(riskAreas, "runtime-configuration", "MEDIUM", "Configuration path changed.");
            }
            if (ReviewFileClassifier.isDependencyManifestPath(path)) {
                changeTypes.add("dependency-or-build-change");
                addRiskArea(riskAreas, "supply-chain-compatibility", "MEDIUM", "Dependency or build manifest changed.");
            }
        }

        for (ReviewContext.SemanticFileContext context : semanticFileContexts) {
            if (!context.apiRoutes().isEmpty()) {
                changeTypes.add("public-api-change");
                addRiskArea(riskAreas, "api-contract", "HIGH", "Changed API route metadata was detected.");
            }
            if (context.annotations().stream().anyMatch(this::isSecurityAnnotation)) {
                changeTypes.add("security-boundary-change");
                addRiskArea(riskAreas, "security-boundary", "HIGH", "Changed security annotation was detected.");
            }
        }
    }

    private void collectRelationshipRisks(
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints,
            Map<String, ReviewPlan.RiskArea> riskAreas
    ) {
        for (ReviewContext.RepoRelationshipHint hint : repoRelationshipHints) {
            switch (upper(hint.type())) {
                case "IMPORT_TARGET" -> addRiskArea(
                        riskAreas,
                        "cross-file-api-compatibility",
                        "MEDIUM",
                        "Changed files include importer/importee relationship."
                );
                case "SOURCE_TEST_PAIR" -> addRiskArea(
                        riskAreas,
                        "test-coverage-alignment",
                        "MEDIUM",
                        "Source and matching test changed together."
                );
                case "LAYERED_COMPONENT" -> addRiskArea(
                        riskAreas,
                        "layer-boundary-drift",
                        "MEDIUM",
                        "Layered components in the same domain changed together."
                );
                default -> {
                    if (StringUtils.hasText(hint.type())) {
                        addRiskArea(riskAreas, "related-changed-files", "LOW", hint.reason());
                    }
                }
            }
        }
    }

    private void collectLinkedIssueRisks(
            List<ReviewContext.LinkedIssueContext> linkedIssueContexts,
            Set<String> changeTypes,
            Map<String, ReviewPlan.RiskArea> riskAreas
    ) {
        if (linkedIssueContexts.isEmpty()) {
            return;
        }
        changeTypes.add("issue-driven-change");
        addRiskArea(
                riskAreas,
                "task-requirement-alignment",
                "MEDIUM",
                "PR links issue context; review should verify the patch matches the stated task, not only local diff mechanics."
        );
        String joinedTitles = linkedIssueContexts.stream()
                .map(ReviewContext.LinkedIssueContext::title)
                .filter(StringUtils::hasText)
                .map(title -> title.toLowerCase(Locale.ROOT))
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        if (containsAny(joinedTitles, "bug", "fix", "regression", "crash", "incorrect", "错误", "修复", "缺陷", "回归")) {
            changeTypes.add("bugfix");
            addRiskArea(
                    riskAreas,
                    "bugfix-regression",
                    "MEDIUM",
                    "Linked issue title indicates bugfix/regression context."
            );
        }
        if (containsAny(joinedTitles, "security", "auth", "permission", "漏洞", "权限", "认证", "安全")) {
            changeTypes.add("security-sensitive-change");
            addRiskArea(
                    riskAreas,
                    "security-boundary",
                    "HIGH",
                    "Linked issue title indicates security or permission context."
            );
        }
    }

    private void addRiskArea(
            Map<String, ReviewPlan.RiskArea> riskAreas,
            String type,
            String severity,
            String reason
    ) {
        if (!StringUtils.hasText(type) || !StringUtils.hasText(reason)) {
            return;
        }
        riskAreas.putIfAbsent(
                type,
                new ReviewPlan.RiskArea(type, StringUtils.hasText(severity) ? severity : "MEDIUM", reason)
        );
    }

    private boolean isSecurityAnnotation(String annotation) {
        if (!StringUtils.hasText(annotation)) {
            return false;
        }
        String normalized = annotation.toLowerCase(Locale.ROOT);
        return normalized.contains("preauthorize")
                || normalized.contains("secured")
                || normalized.contains("rolesallowed")
                || normalized.contains("permitall")
                || normalized.contains("authenticated");
    }

    private boolean containsAny(String content, String... needles) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        for (String needle : needles) {
            if (content.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String lowerOrUnknown(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "unknown";
    }

    record RiskProfile(List<String> changeTypes, List<ReviewPlan.RiskArea> riskAreas) {
    }
}
