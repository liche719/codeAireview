package com.codepilot.module.review.planner;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.graph.RepositoryGraphSnapshot;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class ReviewPlanFileFocusBuilder {

    List<ReviewPlan.FileFocus> fileFocuses(
            List<ReviewContext.FileSummary> fileSummaries,
            Map<String, ReviewFile> reviewFileByPath,
            Map<String, ReviewContext.SemanticFileContext> semanticByPath,
            Map<String, List<ReviewContext.RepoRelationshipHint>> relationshipsByPath,
            Map<String, List<String>> relatedFilesByPath,
            RepositoryGraphSnapshot graphSnapshot
    ) {
        List<ReviewPlan.FileFocus> focuses = new ArrayList<>();
        for (ReviewContext.FileSummary fileSummary : fileSummaries) {
            if (!fileSummary.reviewable()) {
                continue;
            }
            focuses.add(fileFocus(
                    fileSummary,
                    reviewFileByPath,
                    semanticByPath,
                    relationshipsByPath,
                    relatedFilesByPath,
                    graphSnapshot
            ));
        }
        return focuses;
    }

    private ReviewPlan.FileFocus fileFocus(
            ReviewContext.FileSummary fileSummary,
            Map<String, ReviewFile> reviewFileByPath,
            Map<String, ReviewContext.SemanticFileContext> semanticByPath,
            Map<String, List<ReviewContext.RepoRelationshipHint>> relationshipsByPath,
            Map<String, List<String>> relatedFilesByPath,
            RepositoryGraphSnapshot graphSnapshot
    ) {
        String path = fileSummary.filePath();
        String normalizedPath = ReviewFileClassifier.normalizePath(path);
        ReviewContext.SemanticFileContext semanticContext = semanticByPath.get(normalizedPath);
        ReviewFile reviewFile = reviewFileByPath.get(normalizedPath);
        String patch = reviewFile == null || reviewFile.getPatch() == null
                ? ""
                : reviewFile.getPatch().toLowerCase(Locale.ROOT);
        RepositoryGraphSnapshot.GraphNode graphNode = graphSnapshot == null ? null : graphSnapshot.nodeFor(path).orElse(null);
        LinkedHashSet<String> focusItems = new LinkedHashSet<>();
        LinkedHashSet<String> hints = new LinkedHashSet<>();

        addSemanticFocus(semanticContext, focusItems, hints);
        addGraphFocus(path, graphSnapshot, graphNode, focusItems, hints);
        addPathFocus(path, focusItems, hints);
        addPatchFocus(patch, focusItems, hints);
        addRelationshipFocus(normalizedPath, relationshipsByPath, focusItems, hints);
        addFallbackFocus(path, focusItems);

        return new ReviewPlan.FileFocus(
                path,
                List.copyOf(focusItems),
                List.copyOf(hints),
                relatedFilesByPath.getOrDefault(normalizedPath, List.of())
        );
    }

    private void addSemanticFocus(
            ReviewContext.SemanticFileContext semanticContext,
            Set<String> focusItems,
            Set<String> hints
    ) {
        if (semanticContext == null) {
            return;
        }
        if (!semanticContext.apiRoutes().isEmpty()) {
            focusItems.add("Validate changed API route contract, auth boundary, and client compatibility: "
                    + String.join(", ", semanticContext.apiRoutes()));
            hints.add("Prefer concrete API compatibility findings over generic route advice.");
        }
        if (!semanticContext.changedMethods().isEmpty()) {
            focusItems.add("Review changed method behavior: " + String.join(", ", semanticContext.changedMethods()));
        }
        if (semanticContext.annotations().stream().anyMatch(this::isSecurityAnnotation)) {
            focusItems.add("Check changed security annotations for unintended permission boundary changes.");
            hints.add("Tie any auth finding to the changed annotation or route.");
        }
        if (!semanticContext.imports().isEmpty()) {
            focusItems.add("Use changed imports to reason about dependency and cross-file contract impact.");
        }
    }

    private void addGraphFocus(
            String path,
            RepositoryGraphSnapshot graphSnapshot,
            RepositoryGraphSnapshot.GraphNode graphNode,
            Set<String> focusItems,
            Set<String> hints
    ) {
        if (graphNode == null) {
            return;
        }
        if (!graphNode.symbols().isEmpty()) {
            focusItems.add("Trace repository graph symbols: " + joinLimited(graphNode.symbols(), 4));
        }
        if (!graphNode.methods().isEmpty()) {
            focusItems.add("Trace repository graph methods: " + joinLimited(graphNode.methods(), 4));
        }
        if (!graphNode.routes().isEmpty()) {
            focusItems.add("Trace repository graph routes: " + joinLimited(graphNode.routes(), 4));
        }
        List<String> graphRelatedFiles = graphSnapshot.relatedFilesFor(path);
        if (!graphRelatedFiles.isEmpty()) {
            focusItems.add("Review repository graph neighbors: " + joinLimited(graphRelatedFiles, 4));
            hints.add("Use graph neighbors to validate cross-file impact and symbol propagation.");
        }
    }

    private void addPathFocus(String path, Set<String> focuses, Set<String> hints) {
        if (ReviewFileClassifier.isSecuritySensitivePath(path)) {
            focuses.add("Prioritize exploitable auth, permission, secret, or credential regressions.");
            hints.add("Do not emit cosmetic findings before security-sensitive findings.");
        }
        if (ReviewFileClassifier.isDatabasePath(path)) {
            focuses.add("Check migration ordering, destructive SQL, data compatibility, and rollback safety.");
            hints.add("Flag irreversible data changes only when grounded in the changed SQL.");
        }
        if (ReviewFileClassifier.isConfigurationPath(path)) {
            focuses.add("Check environment-specific defaults, unsafe flags, and deployment behavior changes.");
        }
        if (ReviewFileClassifier.isDependencyManifestPath(path)) {
            focuses.add("Check dependency compatibility, supply-chain risk, and build reproducibility.");
        }
    }

    private void addPatchFocus(String patch, Set<String> focuses, Set<String> hints) {
        if (!StringUtils.hasText(patch)) {
            return;
        }
        if (containsAny(patch, "password", "secret", "token", "apikey", "api_key", "credential")) {
            focuses.add("Check for newly introduced secrets, unsafe credential handling, or sensitive logging.");
            hints.add("Secret findings must cite the changed line or changed assignment.");
        }
        if (containsAny(patch, "select ", "update ", "delete ", "insert ", "alter table", "drop table")) {
            focuses.add("Check SQL injection, destructive queries, and transaction/rollback safety.");
        }
        if (containsAny(patch, "synchronized", "lock", "completablefuture", "thread", "executor", "async")) {
            focuses.add("Check concurrency, async ordering, and shared-state safety.");
        }
    }

    private void addRelationshipFocus(
            String normalizedPath,
            Map<String, List<ReviewContext.RepoRelationshipHint>> relationshipsByPath,
            Set<String> focusItems,
            Set<String> hints
    ) {
        List<ReviewContext.RepoRelationshipHint> relationshipHints =
                relationshipsByPath.getOrDefault(normalizedPath, List.of());
        if (!relationshipHints.isEmpty()) {
            focusItems.add("Review this file with its related changed files instead of as an isolated patch.");
            hints.add("Check whether related changed files keep caller/callee, source/test, or layer contracts aligned.");
        }
    }

    private void addFallbackFocus(String path, Set<String> focusItems) {
        if (focusItems.isEmpty() && ReviewFileClassifier.isProductionCodePath(path)) {
            focusItems.add("Review runtime behavior changes and side effects in this production file.");
        }
        if (focusItems.isEmpty() && ReviewFileClassifier.isTestPath(path)) {
            focusItems.add("Check whether the changed test asserts the changed production behavior.");
        }
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

    private String joinLimited(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .limit(limit)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }
}
