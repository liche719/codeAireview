package com.codepilot.module.command.git;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ApplyPatchParser {

    private static final String BEGIN_PATCH = "*** Begin Patch";

    private static final String END_PATCH = "*** End Patch";

    private static final String UPDATE_FILE_PREFIX = "*** Update File: ";

    private static final String ADD_FILE_PREFIX = "*** Add File: ";

    private static final String DELETE_FILE_PREFIX = "*** Delete File: ";

    private ApplyPatchParser() {
    }

    static boolean isApplyPatchFormat(String patch) {
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

    static List<ApplyPatchSection> parseSections(String patch) {
        if (!StringUtils.hasText(patch)) {
            return List.of();
        }

        List<String> lines = splitLines(stripCodeFence(patch));
        List<ApplyPatchSection> sections = new ArrayList<>();
        ApplyPatchSection currentSection = null;
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
                currentSection = new ApplyPatchSection(
                        ApplyPatchSectionType.UPDATE,
                        line.substring(UPDATE_FILE_PREFIX.length()).trim()
                );
                currentBlock = null;
                continue;
            }
            if (line.startsWith(ADD_FILE_PREFIX)) {
                if (currentSection != null) {
                    sections.add(currentSection);
                }
                currentSection = new ApplyPatchSection(
                        ApplyPatchSectionType.ADD,
                        line.substring(ADD_FILE_PREFIX.length()).trim()
                );
                currentBlock = null;
                continue;
            }
            if (line.startsWith(DELETE_FILE_PREFIX)) {
                if (currentSection != null) {
                    sections.add(currentSection);
                }
                currentSection = new ApplyPatchSection(
                        ApplyPatchSectionType.DELETE,
                        line.substring(DELETE_FILE_PREFIX.length()).trim()
                );
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
}
