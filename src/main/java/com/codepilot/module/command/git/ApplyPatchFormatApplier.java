package com.codepilot.module.command.git;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ApplyPatchFormatApplier {

    private static final String BEGIN_PATCH = "*** Begin Patch";

    private static final String END_PATCH = "*** End Patch";

    private static final String UPDATE_FILE_PREFIX = "*** Update File: ";

    private static final String ADD_FILE_PREFIX = "*** Add File: ";

    private static final String DELETE_FILE_PREFIX = "*** Delete File: ";

    private ApplyPatchFormatApplier() {
    }

    public static boolean isApplyPatchFormat(String patch) {
        if (!StringUtils.hasText(patch)) {
            return false;
        }
        String content = stripCodeFence(patch).trim();
        return content.startsWith(BEGIN_PATCH)
                || content.contains("\n" + UPDATE_FILE_PREFIX)
                || content.contains("\n" + ADD_FILE_PREFIX)
                || content.contains("\n" + DELETE_FILE_PREFIX)
                || content.contains(UPDATE_FILE_PREFIX)
                || content.contains(ADD_FILE_PREFIX)
                || content.contains(DELETE_FILE_PREFIX);
    }

    public static ApplyPatchApplicationResult apply(Path repoRoot, String patch) throws IOException {
        if (repoRoot == null) {
            throw new IllegalArgumentException("repoRoot is required");
        }
        if (!StringUtils.hasText(patch)) {
            throw new IllegalArgumentException("patch is required");
        }

        Path normalizedRoot = repoRoot.toAbsolutePath().normalize();
        List<PatchSection> sections = parseSections(stripCodeFence(patch));
        int changedFiles = 0;
        List<String> changedPaths = new ArrayList<>();

        for (PatchSection section : sections) {
            boolean changed = applySection(normalizedRoot, section);
            if (changed) {
                changedFiles++;
                changedPaths.add(section.path());
            }
        }

        return new ApplyPatchApplicationResult(changedFiles, changedPaths);
    }

    private static List<PatchSection> parseSections(String patch) {
        if (!StringUtils.hasText(patch)) {
            return List.of();
        }

        List<String> lines = splitLines(patch);
        List<PatchSection> sections = new ArrayList<>();
        PatchSection currentSection = null;
        List<String> currentBlock = null;

        for (String line : lines) {
            if (BEGIN_PATCH.equals(line) || line.isBlank() && currentSection == null) {
                continue;
            }
            if (END_PATCH.equals(line)) {
                break;
            }
            if (line.startsWith(UPDATE_FILE_PREFIX)) {
                if (currentSection != null) {
                    sections.add(currentSection);
                }
                currentSection = new PatchSection(SectionType.UPDATE, line.substring(UPDATE_FILE_PREFIX.length()).trim());
                currentBlock = null;
                continue;
            }
            if (line.startsWith(ADD_FILE_PREFIX)) {
                if (currentSection != null) {
                    sections.add(currentSection);
                }
                currentSection = new PatchSection(SectionType.ADD, line.substring(ADD_FILE_PREFIX.length()).trim());
                currentBlock = null;
                continue;
            }
            if (line.startsWith(DELETE_FILE_PREFIX)) {
                if (currentSection != null) {
                    sections.add(currentSection);
                }
                currentSection = new PatchSection(SectionType.DELETE, line.substring(DELETE_FILE_PREFIX.length()).trim());
                currentBlock = null;
                continue;
            }
            if (currentSection == null) {
                continue;
            }
            if (line.startsWith("@@")) {
                currentBlock = new ArrayList<>();
                currentSection.blocks().add(currentBlock);
                continue;
            }
            if (currentBlock == null) {
                currentBlock = new ArrayList<>();
                currentSection.blocks().add(currentBlock);
            }
            currentBlock.add(line);
        }

        if (currentSection != null) {
            sections.add(currentSection);
        }
        return sections;
    }

    private static boolean applySection(Path repoRoot, PatchSection section) throws IOException {
        Path targetPath = resolveTargetPath(repoRoot, section.path());
        return switch (section.type()) {
            case UPDATE -> applyUpdateSection(targetPath, section.blocks());
            case ADD -> applyAddSection(targetPath, section.blocks());
            case DELETE -> applyDeleteSection(targetPath);
        };
    }

    private static boolean applyUpdateSection(Path targetPath, List<List<String>> blocks) throws IOException {
        if (!Files.exists(targetPath)) {
            throw new IllegalStateException("Target file does not exist: " + targetPath);
        }

        List<String> originalLines = Files.readAllLines(targetPath, StandardCharsets.UTF_8);
        List<String> updatedLines = new ArrayList<>(originalLines);
        int searchFrom = 0;

        for (List<String> block : blocks) {
            HunkApplication application = applyHunk(updatedLines, block, searchFrom);
            updatedLines = application.lines();
            searchFrom = application.nextSearchFrom();
        }

        if (updatedLines.equals(originalLines)) {
            return false;
        }

        writeLines(targetPath, updatedLines);
        return true;
    }

    private static boolean applyAddSection(Path targetPath, List<List<String>> blocks) throws IOException {
        List<String> contentLines = new ArrayList<>();
        for (List<String> block : blocks) {
            contentLines.addAll(extractContentLines(block));
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

    private static boolean applyDeleteSection(Path targetPath) throws IOException {
        if (!Files.exists(targetPath)) {
            throw new IllegalStateException("Target file does not exist: " + targetPath);
        }
        Files.delete(targetPath);
        return true;
    }

    private static HunkApplication applyHunk(List<String> currentLines, List<String> block, int searchFrom) {
        List<String> sourceLines = new ArrayList<>();
        List<String> replacementLines = new ArrayList<>();

        for (String line : block) {
            if (line == null || line.startsWith("\\ No newline at end of file")) {
                continue;
            }
            if (line.startsWith("@@")) {
                continue;
            }
            if (line.startsWith("+++")) {
                continue;
            }
            if (line.startsWith("---")) {
                continue;
            }
            if (line.startsWith("+")) {
                replacementLines.add(line.substring(1));
                continue;
            }
            if (line.startsWith("-")) {
                sourceLines.add(line.substring(1));
                continue;
            }

            String text = line.startsWith(" ") ? line.substring(1) : line;
            sourceLines.add(text);
            replacementLines.add(text);
        }

        int index = findSubList(currentLines, sourceLines, searchFrom);
        if (index < 0) {
            index = findSubList(currentLines, sourceLines, 0);
        }
        if (index < 0) {
            throw new IllegalStateException("Unable to locate patch hunk in file: " + preview(sourceLines));
        }

        List<String> updatedLines = new ArrayList<>(currentLines);
        updatedLines.subList(index, index + sourceLines.size()).clear();
        updatedLines.addAll(index, replacementLines);
        return new HunkApplication(updatedLines, index + replacementLines.size());
    }

    private static List<String> extractContentLines(List<String> block) {
        if (block == null || block.isEmpty()) {
            return List.of();
        }
        List<String> contentLines = new ArrayList<>();
        for (String line : block) {
            if (line == null || line.startsWith("\\ No newline at end of file")) {
                continue;
            }
            if (line.startsWith("@@")) {
                continue;
            }
            if (line.startsWith("+")) {
                contentLines.add(line.substring(1));
                continue;
            }
            if (line.startsWith(" ")) {
                contentLines.add(line.substring(1));
                continue;
            }
            if (line.startsWith("-")) {
                continue;
            }
            contentLines.add(line);
        }
        return contentLines;
    }

    private static int findSubList(List<String> haystack, List<String> needle, int fromIndex) {
        if (needle == null || needle.isEmpty()) {
            return Math.max(0, fromIndex);
        }
        if (haystack == null || haystack.size() < needle.size()) {
            return -1;
        }
        int max = haystack.size() - needle.size();
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            boolean match = true;
            for (int j = 0; j < needle.size(); j++) {
                if (!equalsNullable(haystack.get(i + j), needle.get(j))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static Path resolveTargetPath(Path repoRoot, String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            throw new IllegalStateException("Patch file path is missing");
        }
        Path targetPath = repoRoot.resolve(relativePath.trim()).normalize();
        if (!targetPath.startsWith(repoRoot)) {
            throw new IllegalStateException("Patch path escapes repository root: " + relativePath);
        }
        return targetPath;
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

    private static List<String> splitLines(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] tokens = normalized.split("\n", -1);
        List<String> lines = new ArrayList<>(tokens.length);
        Collections.addAll(lines, tokens);
        return lines;
    }

    private static String stripCodeFence(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed
                    .replaceFirst("(?is)^```(?:diff|patch|text)?\\s*", "")
                    .replaceFirst("(?is)\\s*```$", "")
                    .trim();
        }
        return trimmed;
    }

    private static String preview(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "<empty>";
        }
        int limit = Math.min(3, lines.size());
        return String.join(" | ", lines.subList(0, limit));
    }

    private enum SectionType {
        UPDATE,
        ADD,
        DELETE
    }

    private record PatchSection(SectionType type, String path, List<List<String>> blocks) {

        private PatchSection(SectionType type, String path) {
            this(type, path, new ArrayList<>());
        }
    }

    private record HunkApplication(List<String> lines, int nextSearchFrom) {
    }

    public record ApplyPatchApplicationResult(int changedFiles, List<String> changedPaths) {

        public ApplyPatchApplicationResult {
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        }
    }
}
