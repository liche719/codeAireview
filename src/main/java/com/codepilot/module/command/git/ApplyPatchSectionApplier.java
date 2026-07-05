package com.codepilot.module.command.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ApplyPatchSectionApplier {

    private ApplyPatchSectionApplier() {
    }

    static boolean apply(Path repoRoot, ApplyPatchSection section) throws IOException {
        Path targetPath = ApplyPatchTargetGuard.resolveTargetPath(repoRoot, section.path());
        return switch (section.type()) {
            case UPDATE -> applyUpdateSection(repoRoot, targetPath, section.blocks());
            case ADD -> applyAddSection(repoRoot, targetPath, section.blocks());
            case DELETE -> applyDeleteSection(repoRoot, targetPath);
        };
    }

    private static boolean applyUpdateSection(Path repoRoot, Path targetPath, List<List<String>> blocks)
            throws IOException {
        ApplyPatchTargetGuard.ensureRealPathWithinRoot(repoRoot, targetPath);
        if (!Files.exists(targetPath)) {
            throw new IllegalStateException("Target file does not exist: " + targetPath);
        }

        List<String> originalLines = Files.readAllLines(targetPath, StandardCharsets.UTF_8);
        List<String> updatedLines = new ArrayList<>(originalLines);
        int searchFrom = 0;

        for (List<String> block : blocks) {
            ApplyPatchHunkApplication application = ApplyPatchHunkApplier.applyHunk(updatedLines, block, searchFrom);
            updatedLines = application.lines();
            searchFrom = application.nextSearchFrom();
        }

        if (updatedLines.equals(originalLines)) {
            return false;
        }

        writeLines(targetPath, updatedLines);
        return true;
    }

    private static boolean applyAddSection(Path repoRoot, Path targetPath, List<List<String>> blocks)
            throws IOException {
        ApplyPatchTargetGuard.ensureRealPathWithinRoot(repoRoot, targetPath);
        List<String> contentLines = new ArrayList<>();
        for (List<String> block : blocks) {
            contentLines.addAll(ApplyPatchHunkApplier.extractContentLines(block));
        }

        if (Files.exists(targetPath)) {
            List<String> originalLines = Files.readAllLines(targetPath, StandardCharsets.UTF_8);
            if (originalLines.equals(contentLines)) {
                return false;
            }
        } else {
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }

        writeLines(targetPath, contentLines);
        return true;
    }

    private static boolean applyDeleteSection(Path repoRoot, Path targetPath) throws IOException {
        ApplyPatchTargetGuard.ensureRealPathWithinRoot(repoRoot, targetPath);
        if (!Files.exists(targetPath)) {
            throw new IllegalStateException("Target file does not exist: " + targetPath);
        }
        Files.delete(targetPath);
        return true;
    }

    private static void writeLines(Path targetPath, List<String> lines) throws IOException {
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String content = String.join(System.lineSeparator(), lines);
        if (!lines.isEmpty()) {
            content = content + System.lineSeparator();
        }
        Files.writeString(targetPath, content, StandardCharsets.UTF_8);
    }
}
