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
        reviewSignals = reviewSignals == null
                ? List.of()
                : reviewSignals.stream()
                .filter(reviewSignal -> reviewSignal != null && hasText(reviewSignal.type()) && hasText(reviewSignal.message()))
                .toList();
    }

    public static ReviewContext empty() {
        return new ReviewContext(List.of(), 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
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

    public record ReviewSignal(String type, String severity, String message) {
    }
}
