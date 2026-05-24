package com.codepilot.module.agent.dto;

import java.util.List;

public record AiReviewContext(
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
        ReviewImpactPlan reviewImpactPlan,
        List<RelatedPatchExcerpt> relatedPatchExcerpts,
        List<ReviewSignal> reviewSignals
) {

    public AiReviewContext {
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
        reviewImpactPlan = reviewImpactPlan == null ? ReviewImpactPlan.empty() : reviewImpactPlan;
        relatedPatchExcerpts = relatedPatchExcerpts == null
                ? List.of()
                : relatedPatchExcerpts.stream()
                .filter(excerpt -> excerpt != null
                        && hasText(excerpt.sourceFile())
                        && hasText(excerpt.relatedFile())
                        && hasText(excerpt.reason())
                        && hasText(excerpt.excerpt()))
                .toList();
        reviewSignals = reviewSignals == null
                ? List.of()
                : reviewSignals.stream()
                .filter(reviewSignal -> reviewSignal != null && hasText(reviewSignal.type()) && hasText(reviewSignal.message()))
                .toList();
    }

    public AiReviewContext(
            List<String> allChangedFiles,
            int totalFileCount,
            int reviewableFileCount,
            int skippedFileCount,
            int totalAdditions,
            int totalDeletions,
            int totalPatchChars,
            List<SkippedFile> skippedFiles
    ) {
        this(
                allChangedFiles,
                totalFileCount,
                reviewableFileCount,
                skippedFileCount,
                totalAdditions,
                totalDeletions,
                totalPatchChars,
                skippedFiles,
                List.of(),
                List.of(),
                List.of(),
                ReviewImpactPlan.empty(),
                List.of(),
                List.of()
        );
    }

    public AiReviewContext(
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
        this(
                allChangedFiles,
                totalFileCount,
                reviewableFileCount,
                skippedFileCount,
                totalAdditions,
                totalDeletions,
                totalPatchChars,
                skippedFiles,
                fileSummaries,
                semanticFileContexts,
                repoRelationshipHints,
                ReviewImpactPlan.empty(),
                List.of(),
                reviewSignals
        );
    }

    public AiReviewContext(
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
            ReviewImpactPlan reviewImpactPlan,
            List<ReviewSignal> reviewSignals
    ) {
        this(
                allChangedFiles,
                totalFileCount,
                reviewableFileCount,
                skippedFileCount,
                totalAdditions,
                totalDeletions,
                totalPatchChars,
                skippedFiles,
                fileSummaries,
                semanticFileContexts,
                repoRelationshipHints,
                reviewImpactPlan,
                List.of(),
                reviewSignals
        );
    }

    public AiReviewContext(
            List<String> allChangedFiles,
            int totalFileCount,
            int reviewableFileCount,
            int skippedFileCount,
            int totalAdditions,
            int totalDeletions,
            int totalPatchChars,
            List<SkippedFile> skippedFiles,
            List<FileSummary> fileSummaries,
            List<ReviewSignal> reviewSignals
    ) {
        this(
                allChangedFiles,
                totalFileCount,
                reviewableFileCount,
                skippedFileCount,
                totalAdditions,
                totalDeletions,
                totalPatchChars,
                skippedFiles,
                fileSummaries,
                List.of(),
                List.of(),
                ReviewImpactPlan.empty(),
                List.of(),
                reviewSignals
        );
    }

    public AiReviewContext(
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
            List<ReviewSignal> reviewSignals
    ) {
        this(
                allChangedFiles,
                totalFileCount,
                reviewableFileCount,
                skippedFileCount,
                totalAdditions,
                totalDeletions,
                totalPatchChars,
                skippedFiles,
                fileSummaries,
                semanticFileContexts,
                List.of(),
                ReviewImpactPlan.empty(),
                List.of(),
                reviewSignals
        );
    }

    public static AiReviewContext empty() {
        return fromChangedFiles(List.of());
    }

    public static AiReviewContext fromChangedFiles(List<String> allChangedFiles) {
        List<String> filePaths = sanitizeFilePaths(allChangedFiles);
        return new AiReviewContext(
                filePaths,
                filePaths.size(),
                filePaths.size(),
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ReviewImpactPlan.empty(),
                List.of(),
                List.of()
        );
    }

    private static List<String> sanitizeFilePaths(List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return List.of();
        }
        return filePaths.stream()
                .filter(AiReviewContext::hasText)
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

    public record ReviewImpactPlan(
            List<String> changeTypes,
            List<String> impactAreas,
            List<String> priorityFocuses,
            List<String> verificationHints
    ) {
        public ReviewImpactPlan {
            changeTypes = sanitizeTextList(changeTypes);
            impactAreas = sanitizeTextList(impactAreas);
            priorityFocuses = sanitizeTextList(priorityFocuses);
            verificationHints = sanitizeTextList(verificationHints);
        }

        public static ReviewImpactPlan empty() {
            return new ReviewImpactPlan(List.of(), List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return changeTypes.isEmpty()
                    && impactAreas.isEmpty()
                    && priorityFocuses.isEmpty()
                    && verificationHints.isEmpty();
        }
    }

    public record RelatedPatchExcerpt(
            String sourceFile,
            String relatedFile,
            String reason,
            String excerpt,
            boolean truncated
    ) {
    }

    public record ReviewSignal(String type, String severity, String message) {
    }

    private static List<String> sanitizeTextList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(AiReviewContext::hasText)
                .map(String::trim)
                .toList();
    }
}
