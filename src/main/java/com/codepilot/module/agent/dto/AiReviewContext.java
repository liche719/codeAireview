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
        List<SkippedFile> skippedFiles
) {

    public AiReviewContext {
        allChangedFiles = sanitizeFilePaths(allChangedFiles);
        skippedFiles = skippedFiles == null
                ? List.of()
                : skippedFiles.stream()
                .filter(skippedFile -> skippedFile != null && hasText(skippedFile.filePath()))
                .toList();
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
}
