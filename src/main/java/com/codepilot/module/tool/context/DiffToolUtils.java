package com.codepilot.module.tool.context;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiffToolUtils {

    private static final Pattern HUNK_HEADER_PATTERN = Pattern.compile(
            "^@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,\\d+)?\\s+@@.*$"
    );

    private DiffToolUtils() {
    }

    public static List<String> addedLines(String patch) {
        return addedLineEntries(patch).stream()
                .map(AddedLine::text)
                .toList();
    }

    public static List<AddedLine> addedLineEntries(String patch) {
        if (!StringUtils.hasText(patch)) {
            return List.of();
        }
        List<AddedLine> lines = new ArrayList<>();
        int currentNewLineNumber = 1;
        int fallbackLineNumber = 1;
        boolean insideHunk = false;
        for (String line : Arrays.asList(patch.split("\\R"))) {
            Matcher hunkMatcher = HUNK_HEADER_PATTERN.matcher(line);
            if (hunkMatcher.matches()) {
                currentNewLineNumber = Integer.parseInt(hunkMatcher.group(1));
                insideHunk = true;
                continue;
            }
            if (line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }
            if (line.startsWith("+")) {
                String text = line.substring(1).trim();
                if (StringUtils.hasText(text)) {
                    lines.add(new AddedLine(text, insideHunk ? currentNewLineNumber : fallbackLineNumber));
                }
                if (insideHunk) {
                    currentNewLineNumber++;
                }
                fallbackLineNumber++;
                continue;
            }
            if (insideHunk && line.startsWith(" ")) {
                currentNewLineNumber++;
            }
        }
        return lines;
    }

    public static String addedText(String patch) {
        return String.join("\n", addedLines(patch));
    }

    public record AddedLine(String text, Integer newLineNumber) {
    }
}
