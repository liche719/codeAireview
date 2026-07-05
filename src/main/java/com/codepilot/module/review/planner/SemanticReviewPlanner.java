package com.codepilot.module.review.planner;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.graph.RepositoryGraphSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class SemanticReviewPlanner {

    private final ReviewPlanRiskCollector riskCollector = new ReviewPlanRiskCollector();

    private final ReviewPlanPriorityFileScorer priorityFileScorer = new ReviewPlanPriorityFileScorer();

    private final ReviewPlanFileFocusBuilder fileFocusBuilder = new ReviewPlanFileFocusBuilder();

    private final ReviewPlanCrossFileFocusBuilder crossFileFocusBuilder = new ReviewPlanCrossFileFocusBuilder();

    private final ReviewPlanVerificationHintBuilder verificationHintBuilder = new ReviewPlanVerificationHintBuilder();

    private final ReviewPlanQualityEstimator qualityEstimator = new ReviewPlanQualityEstimator();

    public ReviewPlan plan(
            List<ReviewFile> reviewFiles,
            List<ReviewContext.FileSummary> fileSummaries,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints,
            ReviewContext.ReviewImpactPlan reviewImpactPlan,
            List<ReviewContext.RelatedPatchExcerpt> relatedPatchExcerpts,
            List<ReviewContext.RepoSourceExcerpt> repoSourceExcerpts,
            List<ReviewContext.ReviewSignal> reviewSignals
    ) {
        return plan(
                reviewFiles,
                fileSummaries,
                semanticFileContexts,
                repoRelationshipHints,
                reviewImpactPlan,
                RepositoryGraphSnapshot.empty(),
                relatedPatchExcerpts,
                repoSourceExcerpts,
                reviewSignals,
                List.of()
        );
    }

    public ReviewPlan plan(
            List<ReviewFile> reviewFiles,
            List<ReviewContext.FileSummary> fileSummaries,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints,
            ReviewContext.ReviewImpactPlan reviewImpactPlan,
            RepositoryGraphSnapshot repositoryGraphSnapshot,
            List<ReviewContext.RelatedPatchExcerpt> relatedPatchExcerpts,
            List<ReviewContext.RepoSourceExcerpt> repoSourceExcerpts,
            List<ReviewContext.ReviewSignal> reviewSignals
    ) {
        return plan(
                reviewFiles,
                fileSummaries,
                semanticFileContexts,
                repoRelationshipHints,
                reviewImpactPlan,
                repositoryGraphSnapshot,
                relatedPatchExcerpts,
                repoSourceExcerpts,
                reviewSignals,
                List.of()
        );
    }

    public ReviewPlan plan(
            List<ReviewFile> reviewFiles,
            List<ReviewContext.FileSummary> fileSummaries,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints,
            ReviewContext.ReviewImpactPlan reviewImpactPlan,
            RepositoryGraphSnapshot repositoryGraphSnapshot,
            List<ReviewContext.RelatedPatchExcerpt> relatedPatchExcerpts,
            List<ReviewContext.RepoSourceExcerpt> repoSourceExcerpts,
            List<ReviewContext.ReviewSignal> reviewSignals,
            List<ReviewContext.LinkedIssueContext> linkedIssueContexts
    ) {
        List<ReviewContext.FileSummary> safeFileSummaries = safeList(fileSummaries);
        List<ReviewContext.SemanticFileContext> safeSemanticContexts = safeList(semanticFileContexts);
        List<ReviewContext.RepoRelationshipHint> safeRelationshipHints = safeList(repoRelationshipHints);
        List<ReviewContext.ReviewSignal> safeReviewSignals = safeList(reviewSignals);
        List<ReviewContext.LinkedIssueContext> safeLinkedIssueContexts = safeList(linkedIssueContexts);
        RepositoryGraphSnapshot safeGraphSnapshot = repositoryGraphSnapshot == null
                ? RepositoryGraphSnapshot.empty()
                : repositoryGraphSnapshot;
        ReviewContext.ReviewImpactPlan safeImpactPlan =
                reviewImpactPlan == null ? ReviewContext.ReviewImpactPlan.empty() : reviewImpactPlan;

        if (safeFileSummaries.isEmpty()
                && safeSemanticContexts.isEmpty()
                && safeRelationshipHints.isEmpty()
                && safeReviewSignals.isEmpty()
                && safeLinkedIssueContexts.isEmpty()
                && safeGraphSnapshot.isEmpty()
                && safeImpactPlan.isEmpty()) {
            return ReviewPlan.empty();
        }

        Map<String, ReviewFile> reviewFileByPath = reviewFileByPath(reviewFiles);
        Map<String, ReviewContext.SemanticFileContext> semanticByPath = semanticByPath(safeSemanticContexts);
        Map<String, List<ReviewContext.RepoRelationshipHint>> relationshipsByPath =
                relationshipsByPath(safeRelationshipHints);
        Map<String, List<String>> relatedFilesByPath = relatedFilesByPath(
                safeRelationshipHints,
                safeList(relatedPatchExcerpts),
                safeList(repoSourceExcerpts)
        );

        ReviewPlanRiskCollector.RiskProfile riskProfile = riskCollector.collect(
                safeImpactPlan,
                safeFileSummaries,
                safeSemanticContexts,
                safeRelationshipHints,
                safeReviewSignals,
                safeLinkedIssueContexts
        );
        LinkedHashSet<String> changeTypes = new LinkedHashSet<>(riskProfile.changeTypes());
        List<ReviewPlan.RiskArea> riskAreas = riskProfile.riskAreas();

        List<ReviewPlan.PriorityFile> priorityFiles = priorityFileScorer.priorityFiles(
                safeFileSummaries,
                reviewFileByPath,
                semanticByPath,
                relationshipsByPath,
                safeGraphSnapshot
        );
        List<ReviewPlan.FileFocus> fileFocuses = fileFocusBuilder.fileFocuses(
                safeFileSummaries,
                reviewFileByPath,
                semanticByPath,
                relationshipsByPath,
                relatedFilesByPath,
                safeGraphSnapshot
        );
        List<ReviewPlan.CrossFileFocus> crossFileFocuses =
                crossFileFocusBuilder.crossFileFocuses(safeRelationshipHints);

        LinkedHashSet<String> verificationHints = new LinkedHashSet<>(safeImpactPlan.verificationHints());
        verificationHints.addAll(verificationHintBuilder.verificationHints(
                riskAreas,
                safeReviewSignals,
                safeRelationshipHints,
                safeGraphSnapshot
        ));
        verificationHints.addAll(verificationHintBuilder.linkedIssueVerificationHints(safeLinkedIssueContexts));

        boolean requiresRepoContext = requiresRepoContext(
                changeTypes,
                safeSemanticContexts,
                safeRelationshipHints,
                riskAreas,
                safeGraphSnapshot
        );
        List<String> plannerWarnings = qualityEstimator.plannerWarnings(
                safeFileSummaries,
                safeSemanticContexts,
                safeReviewSignals,
                requiresRepoContext,
                safeList(relatedPatchExcerpts),
                safeList(repoSourceExcerpts),
                safeGraphSnapshot
        );

        return new ReviewPlan(
                List.copyOf(changeTypes),
                List.copyOf(riskAreas),
                priorityFiles,
                fileFocuses,
                crossFileFocuses,
                List.copyOf(verificationHints),
                requiresRepoContext,
                qualityEstimator.confidence(
                        safeFileSummaries,
                        safeSemanticContexts,
                        safeRelationshipHints,
                        safeReviewSignals,
                        requiresRepoContext,
                        safeList(repoSourceExcerpts),
                        safeImpactPlan,
                        safeLinkedIssueContexts,
                        safeGraphSnapshot
                ),
                plannerWarnings
        );
    }

    private boolean requiresRepoContext(
            Set<String> changeTypes,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            List<ReviewContext.RepoRelationshipHint> relationshipHints,
            Iterable<ReviewPlan.RiskArea> riskAreas,
            RepositoryGraphSnapshot graphSnapshot
    ) {
        if (changeTypes.contains("public-api-change") || changeTypes.contains("security-boundary-change")) {
            return true;
        }
        if (relationshipHints.stream().anyMatch(hint -> switch (upper(hint.type())) {
            case "IMPORT_TARGET", "LAYERED_COMPONENT", "SAME_PACKAGE", "SHARED_IMPORT" -> true;
            default -> false;
        })) {
            return true;
        }
        if (semanticFileContexts.stream().anyMatch(context -> !context.imports().isEmpty())) {
            return true;
        }
        if (graphSnapshot != null && !graphSnapshot.isEmpty()) {
            return true;
        }
        for (ReviewPlan.RiskArea riskArea : riskAreas) {
            if ("api-contract".equals(riskArea.type()) || "cross-file-api-compatibility".equals(riskArea.type())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, ReviewFile> reviewFileByPath(List<ReviewFile> reviewFiles) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return Map.of();
        }
        Map<String, ReviewFile> byPath = new LinkedHashMap<>();
        reviewFiles.stream()
                .filter(reviewFile -> reviewFile != null && StringUtils.hasText(reviewFile.getFilePath()))
                .forEach(reviewFile -> byPath.putIfAbsent(normalizePath(reviewFile.getFilePath()), reviewFile));
        return byPath;
    }

    private Map<String, ReviewContext.SemanticFileContext> semanticByPath(
            List<ReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        Map<String, ReviewContext.SemanticFileContext> byPath = new LinkedHashMap<>();
        semanticFileContexts.stream()
                .filter(context -> context != null && StringUtils.hasText(context.filePath()))
                .forEach(context -> byPath.putIfAbsent(normalizePath(context.filePath()), context));
        return byPath;
    }

    private Map<String, List<ReviewContext.RepoRelationshipHint>> relationshipsByPath(
            List<ReviewContext.RepoRelationshipHint> relationshipHints
    ) {
        Map<String, List<ReviewContext.RepoRelationshipHint>> byPath = new LinkedHashMap<>();
        for (ReviewContext.RepoRelationshipHint hint : relationshipHints) {
            addRelationship(byPath, hint.sourceFile(), hint);
            addRelationship(byPath, hint.targetFile(), hint);
        }
        return byPath;
    }

    private void addRelationship(
            Map<String, List<ReviewContext.RepoRelationshipHint>> byPath,
            String filePath,
            ReviewContext.RepoRelationshipHint hint
    ) {
        if (!StringUtils.hasText(filePath)) {
            return;
        }
        byPath.computeIfAbsent(normalizePath(filePath), ignored -> new ArrayList<>()).add(hint);
    }

    private Map<String, List<String>> relatedFilesByPath(
            List<ReviewContext.RepoRelationshipHint> relationshipHints,
            List<ReviewContext.RelatedPatchExcerpt> relatedPatchExcerpts,
            List<ReviewContext.RepoSourceExcerpt> repoSourceExcerpts
    ) {
        Map<String, LinkedHashSet<String>> relatedFiles = new LinkedHashMap<>();
        for (ReviewContext.RepoRelationshipHint hint : relationshipHints) {
            addRelatedFile(relatedFiles, hint.sourceFile(), hint.targetFile());
            addRelatedFile(relatedFiles, hint.targetFile(), hint.sourceFile());
        }
        for (ReviewContext.RelatedPatchExcerpt excerpt : relatedPatchExcerpts) {
            addRelatedFile(relatedFiles, excerpt.sourceFile(), excerpt.relatedFile());
        }
        for (ReviewContext.RepoSourceExcerpt excerpt : repoSourceExcerpts) {
            addRelatedFile(relatedFiles, excerpt.sourceFile(), excerpt.relatedFile());
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        relatedFiles.forEach((path, files) -> result.put(path, List.copyOf(files)));
        return result;
    }

    private void addRelatedFile(Map<String, LinkedHashSet<String>> relatedFiles, String sourceFile, String relatedFile) {
        if (!StringUtils.hasText(sourceFile) || !StringUtils.hasText(relatedFile)) {
            return;
        }
        relatedFiles.computeIfAbsent(normalizePath(sourceFile), ignored -> new LinkedHashSet<>()).add(relatedFile);
    }

    private String normalizePath(String path) {
        return ReviewFileClassifier.normalizePath(path);
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

}
