package com.codepilot.module.review.planner;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.graph.RepositoryGraphSnapshot;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;

class ReviewPlanQualityEstimator {

    List<String> plannerWarnings(
            List<ReviewContext.FileSummary> fileSummaries,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            List<ReviewContext.ReviewSignal> reviewSignals,
            boolean requiresRepoContext,
            List<ReviewContext.RelatedPatchExcerpt> relatedPatchExcerpts,
            List<ReviewContext.RepoSourceExcerpt> repoSourceExcerpts,
            RepositoryGraphSnapshot graphSnapshot
    ) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        long skippedCount = fileSummaries.stream()
                .filter(fileSummary -> !fileSummary.reviewable())
                .count();
        long sourceFileCount = fileSummaries.stream()
                .filter(ReviewContext.FileSummary::reviewable)
                .map(ReviewContext.FileSummary::filePath)
                .filter(ReviewFileClassifier::isSourcePath)
                .count();
        long semanticSourceCount = semanticFileContexts.stream()
                .filter(this::hasSemanticContent)
                .count();

        if (skippedCount > 0) {
            warnings.add(skippedCount + " changed file(s) were skipped; review completeness may be limited.");
        }
        if (sourceFileCount > 0 && semanticSourceCount == 0) {
            warnings.add("No semantic symbols were extracted for reviewable source files.");
        }
        if (reviewSignals.stream().anyMatch(signal -> "LARGE_PR".equalsIgnoreCase(signal.type()))) {
            warnings.add("Large PR detected; prioritize high-confidence findings and cross-file side effects.");
        }
        if (relatedPatchExcerpts.stream().anyMatch(ReviewContext.RelatedPatchExcerpt::truncated)) {
            warnings.add("Some related changed-file patch excerpts were truncated.");
        }
        if (repoSourceExcerpts.stream().anyMatch(ReviewContext.RepoSourceExcerpt::truncated)) {
            warnings.add("Some repository source excerpts were truncated.");
        }
        if (requiresRepoContext && repoSourceExcerpts.isEmpty()) {
            warnings.add("Planner detected cross-file risk but no repository source excerpts were available.");
        }
        if (graphSnapshot == null || graphSnapshot.isEmpty()) {
            warnings.add("Repository graph snapshot was empty; symbol-aware retrieval is limited.");
        }
        return List.copyOf(warnings);
    }

    double confidence(
            List<ReviewContext.FileSummary> fileSummaries,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            List<ReviewContext.RepoRelationshipHint> relationshipHints,
            List<ReviewContext.ReviewSignal> reviewSignals,
            boolean requiresRepoContext,
            List<ReviewContext.RepoSourceExcerpt> repoSourceExcerpts,
            ReviewContext.ReviewImpactPlan impactPlan,
            List<ReviewContext.LinkedIssueContext> linkedIssueContexts,
            RepositoryGraphSnapshot graphSnapshot
    ) {
        long reviewableCount = fileSummaries.stream().filter(ReviewContext.FileSummary::reviewable).count();
        long skippedCount = fileSummaries.size() - reviewableCount;
        long sourceFileCount = fileSummaries.stream()
                .filter(ReviewContext.FileSummary::reviewable)
                .map(ReviewContext.FileSummary::filePath)
                .filter(ReviewFileClassifier::isSourcePath)
                .count();
        long semanticSourceCount = semanticFileContexts.stream()
                .filter(this::hasSemanticContent)
                .count();
        double semanticCoverage = sourceFileCount == 0 ? 1.0 : Math.min(1.0, (double) semanticSourceCount / sourceFileCount);
        double skippedRatio = fileSummaries.isEmpty() ? 0.0 : (double) skippedCount / fileSummaries.size();

        double score = 0.45;
        if (reviewableCount > 0) {
            score += 0.10;
        }
        if (semanticCoverage >= 0.6) {
            score += 0.20;
        } else if (semanticCoverage > 0) {
            score += 0.10;
        }
        if (!relationshipHints.isEmpty()) {
            score += 0.15;
        }
        if (!impactPlan.isEmpty()) {
            score += 0.10;
        }
        if (!linkedIssueContexts.isEmpty()) {
            score += 0.05;
        }
        if (graphSnapshot != null && !graphSnapshot.isEmpty()) {
            score += 0.10;
            if (!graphSnapshot.focusSymbols().isEmpty()) {
                score += 0.05;
            }
            if (!graphSnapshot.edges().isEmpty()) {
                score += 0.05;
            }
        }
        if (requiresRepoContext && !repoSourceExcerpts.isEmpty()) {
            score += 0.10;
        }
        if (skippedRatio > 0.3) {
            score -= 0.20;
        }
        if (sourceFileCount > 0 && semanticSourceCount == 0) {
            score -= 0.15;
        }
        if (reviewSignals.stream().anyMatch(signal -> "LARGE_PR".equalsIgnoreCase(signal.type()))) {
            score -= 0.10;
        }
        return score;
    }

    private boolean hasSemanticContent(ReviewContext.SemanticFileContext context) {
        return context != null && (StringUtils.hasText(context.packageName())
                || !context.declaredSymbols().isEmpty()
                || !context.changedMethods().isEmpty()
                || !context.annotations().isEmpty()
                || !context.imports().isEmpty()
                || !context.apiRoutes().isEmpty());
    }
}
