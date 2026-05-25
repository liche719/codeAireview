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
        ReviewPlan reviewPlan,
        List<RelatedPatchExcerpt> relatedPatchExcerpts,
        List<RepoSourceExcerpt> repoSourceExcerpts,
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
        reviewPlan = reviewPlan == null ? ReviewPlan.empty() : reviewPlan;
        relatedPatchExcerpts = relatedPatchExcerpts == null
                ? List.of()
                : relatedPatchExcerpts.stream()
                .filter(excerpt -> excerpt != null
                        && hasText(excerpt.sourceFile())
                        && hasText(excerpt.relatedFile())
                        && hasText(excerpt.reason())
                        && hasText(excerpt.excerpt()))
                .toList();
        repoSourceExcerpts = repoSourceExcerpts == null
                ? List.of()
                : repoSourceExcerpts.stream()
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
                ReviewPlan.empty(),
                List.of(),
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
                ReviewPlan.empty(),
                List.of(),
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
            List<RelatedPatchExcerpt> relatedPatchExcerpts,
            List<RepoSourceExcerpt> repoSourceExcerpts,
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
                ReviewPlan.empty(),
                relatedPatchExcerpts,
                repoSourceExcerpts,
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
            List<RelatedPatchExcerpt> relatedPatchExcerpts,
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
                ReviewPlan.empty(),
                relatedPatchExcerpts,
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
                ReviewPlan.empty(),
                List.of(),
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
                ReviewPlan.empty(),
                List.of(),
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
                ReviewPlan.empty(),
                List.of(),
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
                ReviewPlan.empty(),
                List.of(),
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

    public record ReviewPlan(
            List<String> changeTypes,
            List<RiskArea> riskAreas,
            List<PriorityFile> priorityFiles,
            List<FileFocus> fileFocuses,
            List<CrossFileFocus> crossFileFocuses,
            List<String> verificationHints,
            boolean requiresRepoContext,
            double confidence,
            List<String> plannerWarnings
    ) {
        public ReviewPlan {
            changeTypes = sanitizeTextList(changeTypes, 8);
            riskAreas = riskAreas == null
                    ? List.of()
                    : riskAreas.stream()
                    .filter(riskArea -> riskArea != null
                            && hasText(riskArea.type())
                            && hasText(riskArea.severity())
                            && hasText(riskArea.reason()))
                    .limit(10)
                    .toList();
            priorityFiles = priorityFiles == null
                    ? List.of()
                    : priorityFiles.stream()
                    .filter(priorityFile -> priorityFile != null && hasText(priorityFile.filePath()))
                    .limit(10)
                    .toList();
            fileFocuses = fileFocuses == null
                    ? List.of()
                    : fileFocuses.stream()
                    .filter(fileFocus -> fileFocus != null
                            && hasText(fileFocus.filePath())
                            && (!fileFocus.focuses().isEmpty()
                            || !fileFocus.verificationHints().isEmpty()
                            || !fileFocus.relatedFiles().isEmpty()))
                    .limit(30)
                    .toList();
            crossFileFocuses = crossFileFocuses == null
                    ? List.of()
                    : crossFileFocuses.stream()
                    .filter(crossFileFocus -> crossFileFocus != null
                            && hasText(crossFileFocus.type())
                            && !crossFileFocus.files().isEmpty()
                            && hasText(crossFileFocus.reason()))
                    .limit(8)
                    .toList();
            verificationHints = sanitizeTextList(verificationHints, 10);
            confidence = round(clamp(confidence));
            plannerWarnings = sanitizeTextList(plannerWarnings, 8);
        }

        public static ReviewPlan empty() {
            return new ReviewPlan(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    0.0,
                    List.of()
            );
        }

        public boolean isEmpty() {
            return changeTypes.isEmpty()
                    && riskAreas.isEmpty()
                    && priorityFiles.isEmpty()
                    && fileFocuses.isEmpty()
                    && crossFileFocuses.isEmpty()
                    && verificationHints.isEmpty()
                    && !requiresRepoContext
                    && plannerWarnings.isEmpty();
        }

        public record RiskArea(String type, String severity, String reason) {
            public RiskArea {
                type = singleLine(type);
                severity = singleLine(severity);
                reason = singleLine(reason);
            }
        }

        public record PriorityFile(String filePath, int score, List<String> reasons) {
            public PriorityFile {
                filePath = singleLine(filePath);
                reasons = sanitizeTextList(reasons, 3);
            }
        }

        public record FileFocus(
                String filePath,
                List<String> focuses,
                List<String> verificationHints,
                List<String> relatedFiles
        ) {
            public FileFocus {
                filePath = singleLine(filePath);
                focuses = sanitizeTextList(focuses, 6);
                verificationHints = sanitizeTextList(verificationHints, 4);
                relatedFiles = sanitizeTextList(relatedFiles, 8);
            }
        }

        public record CrossFileFocus(String type, List<String> files, String reason, String verificationHint) {
            public CrossFileFocus {
                type = singleLine(type);
                files = sanitizeTextList(files, 4);
                reason = singleLine(reason);
                verificationHint = singleLine(verificationHint);
            }
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

    public record RepoSourceExcerpt(
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
        return sanitizeTextList(values, Integer.MAX_VALUE);
    }

    private static List<String> sanitizeTextList(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(AiReviewContext::hasText)
                .map(AiReviewContext::singleLine)
                .limit(limit)
                .toList();
    }

    private static String singleLine(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
