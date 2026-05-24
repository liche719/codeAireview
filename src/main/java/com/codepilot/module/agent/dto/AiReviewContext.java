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
                List.of()
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

    public record ReviewSignal(String type, String severity, String message) {
    }
}
