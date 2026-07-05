package com.codepilot.module.review.planner;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.graph.RepositoryGraphSnapshot;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class ReviewPlanPriorityFileScorer {

    private static final int PRIORITY_REASON_LIMIT = 3;

    List<ReviewPlan.PriorityFile> priorityFiles(
            List<ReviewContext.FileSummary> fileSummaries,
            Map<String, ReviewFile> reviewFileByPath,
            Map<String, ReviewContext.SemanticFileContext> semanticByPath,
            Map<String, List<ReviewContext.RepoRelationshipHint>> relationshipsByPath,
            RepositoryGraphSnapshot graphSnapshot
    ) {
        return fileSummaries.stream()
                .filter(ReviewContext.FileSummary::reviewable)
                .map(fileSummary -> scoredFile(
                        fileSummary,
                        reviewFileByPath.get(normalizePath(fileSummary.filePath())),
                        semanticByPath.get(normalizePath(fileSummary.filePath())),
                        relationshipsByPath.getOrDefault(normalizePath(fileSummary.filePath()), List.of()),
                        graphSnapshot
                ))
                .filter(scoredFile -> scoredFile.score() > 0)
                .sorted(Comparator.comparingInt(ScoredFile::score)
                        .reversed()
                        .thenComparing(scoredFile -> normalizePath(scoredFile.filePath())))
                .map(scoredFile -> new ReviewPlan.PriorityFile(
                        scoredFile.filePath(),
                        scoredFile.score(),
                        scoredFile.reasons()
                ))
                .toList();
    }

    private ScoredFile scoredFile(
            ReviewContext.FileSummary fileSummary,
            ReviewFile reviewFile,
            ReviewContext.SemanticFileContext semanticContext,
            List<ReviewContext.RepoRelationshipHint> relationshipHints,
            RepositoryGraphSnapshot graphSnapshot
    ) {
        String path = fileSummary.filePath();
        String patch = reviewFile == null || reviewFile.getPatch() == null
                ? ""
                : reviewFile.getPatch().toLowerCase(Locale.ROOT);
        int score = 0;
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        RepositoryGraphSnapshot.GraphNode graphNode = graphSnapshot == null ? null : graphSnapshot.nodeFor(path).orElse(null);

        if (ReviewFileClassifier.isSecuritySensitivePath(path)
                || containsAny(patch, "password", "secret", "token", "auth", "permission", "credential")) {
            score += 1000;
            reasons.add("security-sensitive path or patch keyword");
        }
        if (ReviewFileClassifier.isDatabasePath(path)
                || containsAny(patch, "select ", "update ", "delete ", "insert ", "alter table", "drop table")) {
            score += 900;
            reasons.add("database or SQL behavior change");
        }
        if (ReviewFileClassifier.isPublicApiPath(path)
                || semanticContext != null && !semanticContext.apiRoutes().isEmpty()) {
            score += 800;
            reasons.add("public API contract change");
        }
        if (ReviewFileClassifier.isConfigurationPath(path)) {
            score += 700;
            reasons.add("runtime configuration change");
        }
        if (ReviewFileClassifier.isDependencyManifestPath(path)) {
            score += 650;
            reasons.add("dependency or build manifest change");
        }
        if (ReviewFileClassifier.isProductionCodePath(path)) {
            score += 600;
            reasons.add("production code change");
        }
        if (ReviewFileClassifier.isTestPath(path)) {
            score += 350;
            reasons.add("test behavior change");
        }
        if (fileSummary.patchChars() >= 5_000) {
            score += 80;
            reasons.add("large per-file patch");
        }
        if (!relationshipHints.isEmpty()) {
            score += Math.min(200, relationshipHints.size() * 50);
            reasons.add("cross-file relationship detected");
        }
        if (semanticContext != null && !semanticContext.changedMethods().isEmpty()) {
            score += 60;
            reasons.add("changed method-level semantics");
        }
        if (graphNode != null) {
            if (graphNode.score() > 0) {
                score += Math.min(160, graphNode.score() / 10);
                reasons.add("repository graph score");
            }
            if (graphNode.degree() > 1) {
                score += Math.min(80, graphNode.degree() * 12);
                reasons.add("repository graph degree");
            }
        }
        if (graphSnapshot != null && graphSnapshot.focusFiles().stream().anyMatch(file -> normalizePath(file).equals(normalizePath(path)))) {
            score += 60;
            reasons.add("graph focus file");
        }
        if (ReviewFileClassifier.isDocumentationPath(path)) {
            score -= 250;
            reasons.add("documentation-only path");
        }

        return new ScoredFile(path, score, reasons.stream().limit(PRIORITY_REASON_LIMIT).toList());
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

    private String normalizePath(String path) {
        return ReviewFileClassifier.normalizePath(path);
    }

    private record ScoredFile(String filePath, int score, List<String> reasons) {
    }
}
