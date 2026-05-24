package com.codepilot.module.review.context;

import com.codepilot.module.agent.dto.AiReviewContext;

import java.util.List;

public record ReviewContext(
        List<String> allChangedFiles,
        int totalFileCount,
        int reviewableFileCount,
        int skippedFileCount,
        int totalAdditions,
        int totalDeletions,
        int totalPatchChars,
        List<SkippedFile> skippedFiles,
        List<FileSummary> fileSummaries,
        List<SemanticFileContext> semanticFileContexts,
        List<RepoRelationshipHint> repoRelationshipHints,
        List<ReviewSignal> reviewSignals
) {

    public ReviewContext {
        allChangedFiles = sanitizeFilePaths(allChangedFiles);
        skippedFiles = skippedFiles == null
                ? List.of()
                : skippedFiles.stream()
                .filter(skippedFile -> skippedFile != null && hasText(skippedFile.filePath()))
                .toList();
        fileSummaries = fileSummaries == null
                ? List.of()
                : fileSummaries.stream()
                .filter(fileSummary -> fileSummary != null && hasText(fileSummary.filePath()))
                .toList();
        semanticFileContexts = semanticFileContexts == null
                ? List.of()
                : semanticFileContexts.stream()
                .filter(semanticFileContext -> semanticFileContext != null && hasText(semanticFileContext.filePath()))
                .toList();
        repoRelationshipHints = repoRelationshipHints == null
                ? List.of()
                : repoRelationshipHints.stream()
                .filter(relationship -> relationship != null
                        && hasText(relationship.sourceFile())
                        && hasText(relationship.targetFile())
                        && hasText(relationship.type())
                        && hasText(relationship.reason()))
                .toList();
        reviewSignals = reviewSignals == null
                ? List.of()
                : reviewSignals.stream()
                .filter(reviewSignal -> reviewSignal != null && hasText(reviewSignal.type()) && hasText(reviewSignal.message()))
                .toList();
    }

    public static ReviewContext empty() {
        return new ReviewContext(List.of(), 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public AiReviewContext toAiReviewContext() {
        return new AiReviewContext(
                allChangedFiles,
                totalFileCount,
                reviewableFileCount,
                skippedFileCount,
                totalAdditions,
                totalDeletions,
                totalPatchChars,
                skippedFiles.stream()
                        .map(skippedFile -> new AiReviewContext.SkippedFile(skippedFile.filePath(), skippedFile.reason()))
                        .toList(),
                fileSummaries.stream()
                        .map(fileSummary -> new AiReviewContext.FileSummary(
                                fileSummary.filePath(),
                                fileSummary.changeType(),
                                fileSummary.additions(),
                                fileSummary.deletions(),
                                fileSummary.patchChars(),
                                fileSummary.reviewable(),
                                fileSummary.skipReason()
                        ))
                        .toList(),
                semanticFileContexts.stream()
                        .map(semanticFileContext -> new AiReviewContext.SemanticFileContext(
                                semanticFileContext.filePath(),
                                semanticFileContext.language(),
                                semanticFileContext.packageName(),
                                semanticFileContext.declaredSymbols(),
                                semanticFileContext.changedMethods(),
                                semanticFileContext.annotations(),
                                semanticFileContext.imports(),
                                semanticFileContext.apiRoutes()
                        ))
                        .toList(),
                repoRelationshipHints.stream()
                        .map(relationship -> new AiReviewContext.RepoRelationshipHint(
                                relationship.sourceFile(),
                                relationship.targetFile(),
                                relationship.type(),
                                relationship.reason()
                        ))
                        .toList(),
                reviewSignals.stream()
                        .map(reviewSignal -> new AiReviewContext.ReviewSignal(
                                reviewSignal.type(),
                                reviewSignal.severity(),
                                reviewSignal.message()
                        ))
                        .toList()
        );
    }

    private static List<String> sanitizeFilePaths(List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return List.of();
        }
        return filePaths.stream()
                .filter(ReviewContext::hasText)
                .map(String::trim)
                .toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record SkippedFile(String filePath, String reason) {
    }

    public record FileSummary(
            String filePath,
            String changeType,
            int additions,
            int deletions,
            int patchChars,
            boolean reviewable,
            String skipReason
    ) {
    }

    public record SemanticFileContext(
            String filePath,
            String language,
            String packageName,
            List<String> declaredSymbols,
            List<String> changedMethods,
            List<String> annotations,
            List<String> imports,
            List<String> apiRoutes
    ) {
        public SemanticFileContext {
            declaredSymbols = sanitizeTextList(declaredSymbols);
            changedMethods = sanitizeTextList(changedMethods);
            annotations = sanitizeTextList(annotations);
            imports = sanitizeTextList(imports);
            apiRoutes = sanitizeTextList(apiRoutes);
        }
    }

    public record RepoRelationshipHint(
            String sourceFile,
            String targetFile,
            String type,
            String reason
    ) {
    }

    public record ReviewSignal(String type, String severity, String message) {
    }

    private static List<String> sanitizeTextList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(ReviewContext::hasText)
                .map(String::trim)
                .toList();
    }
}
