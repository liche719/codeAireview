package com.codepilot.module.review.context;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ReviewImpactPlanner {

    private static final int CHANGE_TYPE_LIMIT = 8;

    private static final int IMPACT_AREA_LIMIT = 10;

    private static final int PRIORITY_FOCUS_LIMIT = 10;

    private static final int VERIFICATION_HINT_LIMIT = 10;

    public ReviewContext.ReviewImpactPlan plan(
            List<ReviewContext.FileSummary> fileSummaries,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints,
            List<ReviewContext.ReviewSignal> reviewSignals
    ) {
        Set<String> changeTypes = new LinkedHashSet<>();
        Set<String> impactAreas = new LinkedHashSet<>();
        Set<String> priorityFocuses = new LinkedHashSet<>();
        Set<String> verificationHints = new LinkedHashSet<>();

        collectFromFiles(fileSummaries, changeTypes, impactAreas, verificationHints);
        collectFromSemanticContexts(semanticFileContexts, changeTypes, impactAreas, priorityFocuses);
        collectFromRelationships(repoRelationshipHints, impactAreas, priorityFocuses, verificationHints);
        collectFromSignals(reviewSignals, changeTypes, impactAreas, priorityFocuses, verificationHints);

        if (!changeTypes.isEmpty() || !impactAreas.isEmpty()) {
            priorityFocuses.add("Review the patch as an impact set, not only as isolated file edits.");
        }

        return new ReviewContext.ReviewImpactPlan(
                limit(changeTypes, CHANGE_TYPE_LIMIT),
                limit(impactAreas, IMPACT_AREA_LIMIT),
                limit(priorityFocuses, PRIORITY_FOCUS_LIMIT),
                limit(verificationHints, VERIFICATION_HINT_LIMIT)
        );
    }

    private void collectFromFiles(
            List<ReviewContext.FileSummary> fileSummaries,
            Set<String> changeTypes,
            Set<String> impactAreas,
            Set<String> verificationHints
    ) {
        if (fileSummaries == null || fileSummaries.isEmpty()) {
            return;
        }
        boolean hasProductionCode = false;
        boolean hasTestCode = false;
        boolean hasConfig = false;
        boolean hasDependency = false;
        boolean hasDatabase = false;
        boolean hasCi = false;

        for (ReviewContext.FileSummary fileSummary : fileSummaries) {
            if (fileSummary == null || !StringUtils.hasText(fileSummary.filePath())) {
                continue;
            }
            String path = ReviewFileClassifier.normalizePath(fileSummary.filePath());
            if (!fileSummary.reviewable()) {
                verificationHints.add(skippedFileHint(fileSummary));
            }
            hasProductionCode = hasProductionCode || ReviewFileClassifier.isProductionCodePath(path);
            hasTestCode = hasTestCode || ReviewFileClassifier.isTestPath(path);
            hasConfig = hasConfig || ReviewFileClassifier.isConfigurationPath(path);
            hasDependency = hasDependency || ReviewFileClassifier.isDependencyManifestPath(path);
            hasDatabase = hasDatabase || ReviewFileClassifier.isDatabasePath(path);
            hasCi = hasCi || path.startsWith(".github/workflows/");
        }

        if (hasProductionCode) {
            changeTypes.add("production-code-change");
            impactAreas.add("runtime behavior");
        }
        if (hasTestCode) {
            changeTypes.add("test-change");
            impactAreas.add("test coverage");
        }
        if (hasConfig) {
            changeTypes.add("configuration-change");
            impactAreas.add("deployment/runtime configuration");
            verificationHints.add("Check whether config defaults are safe across environments.");
        }
        if (hasDependency) {
            changeTypes.add("dependency-or-build-change");
            impactAreas.add("build reproducibility and supply chain");
            verificationHints.add("Check dependency compatibility and build reproducibility.");
        }
        if (hasDatabase) {
            changeTypes.add("database-change");
            impactAreas.add("data compatibility and rollback safety");
            verificationHints.add("Check migration ordering, rollback strategy, and destructive SQL.");
        }
        if (hasCi) {
            changeTypes.add("ci-workflow-change");
            impactAreas.add("CI/CD execution permissions");
            verificationHints.add("Check workflow permissions, secret exposure, and untrusted code execution.");
        }
    }

    private void collectFromSemanticContexts(
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            Set<String> changeTypes,
            Set<String> impactAreas,
            Set<String> priorityFocuses
    ) {
        if (semanticFileContexts == null || semanticFileContexts.isEmpty()) {
            return;
        }
        for (ReviewContext.SemanticFileContext context : semanticFileContexts) {
            if (context == null) {
                continue;
            }
            if (!context.apiRoutes().isEmpty()) {
                changeTypes.add("public-api-change");
                impactAreas.add("API contract and clients");
                priorityFocuses.add("Validate auth, backward compatibility, and response contract for changed routes.");
            }
            if (!context.annotations().isEmpty()
                    && context.annotations().stream().anyMatch(this::isSecurityAnnotation)) {
                changeTypes.add("security-boundary-change");
                impactAreas.add("authorization and authentication boundary");
                priorityFocuses.add("Check whether the security boundary changed unintentionally.");
            }
            if (!context.imports().isEmpty()) {
                priorityFocuses.add("Use changed imports to reason about cross-file contract impact.");
            }
        }
    }

    private void collectFromRelationships(
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints,
            Set<String> impactAreas,
            Set<String> priorityFocuses,
            Set<String> verificationHints
    ) {
        if (repoRelationshipHints == null || repoRelationshipHints.isEmpty()) {
            return;
        }
        for (ReviewContext.RepoRelationshipHint hint : repoRelationshipHints) {
            if (hint == null || !StringUtils.hasText(hint.type())) {
                continue;
            }
            switch (hint.type().trim().toUpperCase(Locale.ROOT)) {
                case "IMPORT_TARGET" -> {
                    impactAreas.add("cross-file API compatibility");
                    priorityFocuses.add("Inspect changed importer/importee pairs for broken contracts.");
                }
                case "SOURCE_TEST_PAIR" -> {
                    impactAreas.add("test coverage alignment");
                    verificationHints.add("Check whether changed tests assert the changed production behavior.");
                }
                case "LAYERED_COMPONENT" -> {
                    impactAreas.add("layer boundary and responsibility drift");
                    priorityFocuses.add("Check whether controller/service/repository layer responsibilities stayed separated.");
                }
                case "SAME_PACKAGE" -> impactAreas.add("package-level coupling");
                case "SHARED_IMPORT" -> impactAreas.add("shared dependency behavior");
                default -> impactAreas.add("related changed files");
            }
        }
    }

    private void collectFromSignals(
            List<ReviewContext.ReviewSignal> reviewSignals,
            Set<String> changeTypes,
            Set<String> impactAreas,
            Set<String> priorityFocuses,
            Set<String> verificationHints
    ) {
        if (reviewSignals == null || reviewSignals.isEmpty()) {
            return;
        }
        for (ReviewContext.ReviewSignal signal : reviewSignals) {
            if (signal == null || !StringUtils.hasText(signal.type())) {
                continue;
            }
            switch (signal.type().trim().toUpperCase(Locale.ROOT)) {
                case "LARGE_PR" -> {
                    changeTypes.add("large-review-scope");
                    priorityFocuses.add("Prioritize high-confidence findings and cross-file side effects.");
                    verificationHints.add("Avoid low-confidence style comments in large PRs.");
                }
                case "SKIPPED_FILES" -> verificationHints.add("Skipped files may hide generated/config impact; mention uncertainty if relevant.");
                case "MISSING_TEST_CHANGE" -> {
                    impactAreas.add("untested production behavior");
                    priorityFocuses.add("Look for behavior changes that lack matching tests.");
                    verificationHints.add("Prefer concrete missing-test findings over generic test advice.");
                }
                case "DATABASE_CHANGE" -> {
                    changeTypes.add("database-change");
                    impactAreas.add("data compatibility and rollback safety");
                }
                case "SECURITY_SENSITIVE_CHANGE" -> {
                    changeTypes.add("security-sensitive-change");
                    impactAreas.add("auth/secrets/permission boundary");
                    priorityFocuses.add("Prioritize exploitable security regressions over cosmetic issues.");
                }
                case "CONFIG_CHANGE" -> {
                    changeTypes.add("configuration-change");
                    impactAreas.add("deployment/runtime configuration");
                }
                case "DEPENDENCY_CHANGE" -> {
                    changeTypes.add("dependency-or-build-change");
                    impactAreas.add("supply-chain and compatibility risk");
                }
                case "PUBLIC_API_CHANGE" -> {
                    changeTypes.add("public-api-change");
                    impactAreas.add("API contract and clients");
                }
                default -> {
                    if (StringUtils.hasText(signal.message())) {
                        priorityFocuses.add(signal.message());
                    }
                }
            }
        }
    }

    private String skippedFileHint(ReviewContext.FileSummary fileSummary) {
        String reason = StringUtils.hasText(fileSummary.skipReason())
                ? " (" + fileSummary.skipReason().trim() + ")"
                : "";
        return "Account for skipped file '" + fileSummary.filePath()
                + "'" + reason + " when judging review completeness.";
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

    private List<String> limit(Set<String> values, int limit) {
        return values.stream()
                .filter(StringUtils::hasText)
                .limit(limit)
                .toList();
    }
}
