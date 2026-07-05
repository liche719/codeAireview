package com.codepilot.module.review.graph;

import com.codepilot.module.agent.dto.AiReviewContext;
import com.codepilot.module.review.context.ReviewContext;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RepositoryGraphSnapshotBuilder {

    private static final RepositoryGraphSnapshotAssembler SNAPSHOT_ASSEMBLER =
            new RepositoryGraphSnapshotAssembler();

    private RepositoryGraphSnapshotBuilder() {
    }

    public static RepositoryGraphSnapshot buildReviewContextGraph(
            List<ReviewContext.FileSummary> fileSummaries,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints,
            String currentFilePath
    ) {
        return SNAPSHOT_ASSEMBLER.build(
                reviewNodeInputs(fileSummaries, semanticFileContexts),
                reviewEdgeInputs(repoRelationshipHints),
                currentFilePath
        );
    }

    public static RepositoryGraphSnapshot buildAiReviewContextGraph(
            List<AiReviewContext.FileSummary> fileSummaries,
            List<AiReviewContext.SemanticFileContext> semanticFileContexts,
            List<AiReviewContext.RepoRelationshipHint> repoRelationshipHints,
            String currentFilePath
    ) {
        return SNAPSHOT_ASSEMBLER.build(
                aiNodeInputs(fileSummaries, semanticFileContexts),
                aiEdgeInputs(repoRelationshipHints),
                currentFilePath
        );
    }

    private static List<RepositoryGraphNodeInput> reviewNodeInputs(
            List<ReviewContext.FileSummary> fileSummaries,
            List<ReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        Map<String, ReviewContext.SemanticFileContext> semanticByPath = semanticByPath(semanticFileContexts);
        List<RepositoryGraphNodeInput> inputs = new ArrayList<>();
        if (fileSummaries == null) {
            return inputs;
        }
        for (ReviewContext.FileSummary fileSummary : fileSummaries) {
            if (fileSummary == null || !StringUtils.hasText(fileSummary.filePath())) {
                continue;
            }
            ReviewContext.SemanticFileContext semanticContext =
                    semanticByPath.get(RepositoryGraphPathUtils.normalizePath(fileSummary.filePath()));
            inputs.add(new RepositoryGraphNodeInput(
                    fileSummary.filePath(),
                    semanticContext == null
                            ? RepositoryGraphPathUtils.languageFromPath(fileSummary.filePath())
                            : semanticContext.language(),
                    semanticContext == null ? null : semanticContext.packageName(),
                    semanticContext != null && semanticContext.declaredSymbols() != null
                            ? semanticContext.declaredSymbols()
                            : List.of(),
                    semanticContext != null && semanticContext.changedMethods() != null
                            ? semanticContext.changedMethods()
                            : List.of(),
                    semanticContext != null && semanticContext.annotations() != null
                            ? semanticContext.annotations()
                            : List.of(),
                    semanticContext != null && semanticContext.imports() != null
                            ? semanticContext.imports()
                            : List.of(),
                    semanticContext != null && semanticContext.apiRoutes() != null
                            ? semanticContext.apiRoutes()
                            : List.of(),
                    fileSummary.reviewable(),
                    fileSummary.additions(),
                    fileSummary.deletions(),
                    fileSummary.patchChars(),
                    fileSummary.skipReason()
            ));
        }
        return inputs;
    }

    private static List<RepositoryGraphNodeInput> aiNodeInputs(
            List<AiReviewContext.FileSummary> fileSummaries,
            List<AiReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        Map<String, AiReviewContext.SemanticFileContext> semanticByPath = aiSemanticByPath(semanticFileContexts);
        List<RepositoryGraphNodeInput> inputs = new ArrayList<>();
        if (fileSummaries == null) {
            return inputs;
        }
        for (AiReviewContext.FileSummary fileSummary : fileSummaries) {
            if (fileSummary == null || !StringUtils.hasText(fileSummary.filePath())) {
                continue;
            }
            AiReviewContext.SemanticFileContext semanticContext =
                    semanticByPath.get(RepositoryGraphPathUtils.normalizePath(fileSummary.filePath()));
            inputs.add(new RepositoryGraphNodeInput(
                    fileSummary.filePath(),
                    semanticContext == null
                            ? RepositoryGraphPathUtils.languageFromPath(fileSummary.filePath())
                            : semanticContext.language(),
                    semanticContext == null ? null : semanticContext.packageName(),
                    semanticContext != null && semanticContext.declaredSymbols() != null
                            ? semanticContext.declaredSymbols()
                            : List.of(),
                    semanticContext != null && semanticContext.changedMethods() != null
                            ? semanticContext.changedMethods()
                            : List.of(),
                    semanticContext != null && semanticContext.annotations() != null
                            ? semanticContext.annotations()
                            : List.of(),
                    semanticContext != null && semanticContext.imports() != null
                            ? semanticContext.imports()
                            : List.of(),
                    semanticContext != null && semanticContext.apiRoutes() != null
                            ? semanticContext.apiRoutes()
                            : List.of(),
                    fileSummary.reviewable(),
                    fileSummary.additions(),
                    fileSummary.deletions(),
                    fileSummary.patchChars(),
                    fileSummary.skipReason()
            ));
        }
        return inputs;
    }

    private static List<RepositoryGraphEdgeInput> reviewEdgeInputs(
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints
    ) {
        List<RepositoryGraphEdgeInput> inputs = new ArrayList<>();
        if (repoRelationshipHints == null) {
            return inputs;
        }
        for (ReviewContext.RepoRelationshipHint hint : repoRelationshipHints) {
            if (hint != null) {
                inputs.add(new RepositoryGraphEdgeInput(hint.sourceFile(), hint.targetFile(), hint.type(), hint.reason()));
            }
        }
        return inputs;
    }

    private static List<RepositoryGraphEdgeInput> aiEdgeInputs(
            List<AiReviewContext.RepoRelationshipHint> repoRelationshipHints
    ) {
        List<RepositoryGraphEdgeInput> inputs = new ArrayList<>();
        if (repoRelationshipHints == null) {
            return inputs;
        }
        for (AiReviewContext.RepoRelationshipHint hint : repoRelationshipHints) {
            if (hint != null) {
                inputs.add(new RepositoryGraphEdgeInput(hint.sourceFile(), hint.targetFile(), hint.type(), hint.reason()));
            }
        }
        return inputs;
    }

    private static Map<String, ReviewContext.SemanticFileContext> semanticByPath(
            List<ReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        Map<String, ReviewContext.SemanticFileContext> semanticByPath = new LinkedHashMap<>();
        if (semanticFileContexts == null) {
            return semanticByPath;
        }
        for (ReviewContext.SemanticFileContext semanticContext : semanticFileContexts) {
            if (semanticContext != null && StringUtils.hasText(semanticContext.filePath())) {
                semanticByPath.putIfAbsent(
                        RepositoryGraphPathUtils.normalizePath(semanticContext.filePath()),
                        semanticContext
                );
            }
        }
        return semanticByPath;
    }

    private static Map<String, AiReviewContext.SemanticFileContext> aiSemanticByPath(
            List<AiReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        Map<String, AiReviewContext.SemanticFileContext> semanticByPath = new LinkedHashMap<>();
        if (semanticFileContexts == null) {
            return semanticByPath;
        }
        for (AiReviewContext.SemanticFileContext semanticContext : semanticFileContexts) {
            if (semanticContext != null && StringUtils.hasText(semanticContext.filePath())) {
                semanticByPath.putIfAbsent(
                        RepositoryGraphPathUtils.normalizePath(semanticContext.filePath()),
                        semanticContext
                );
            }
        }
        return semanticByPath;
    }
}
