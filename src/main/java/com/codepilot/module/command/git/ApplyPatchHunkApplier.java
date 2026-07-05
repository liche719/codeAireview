package com.codepilot.module.command.git;

import java.util.ArrayList;
import java.util.List;

final class ApplyPatchHunkApplier {

    private ApplyPatchHunkApplier() {
    }

    static ApplyPatchHunkApplication applyHunk(List<String> currentLines, List<String> block, int searchFrom) {
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
        return new ApplyPatchHunkApplication(updatedLines, index + replacementLines.size());
    }

    static List<String> extractContentLines(List<String> block) {
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

    private static String preview(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "<empty>";
        }
        int limit = Math.min(3, lines.size());
        return String.join(" | ", lines.subList(0, limit));
    }
}
