package com.codepilot.module.tool.context;

import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

public final class DiffToolUtils {

    private DiffToolUtils() {
    }

    public static List<String> addedLines(String patch) {
        if (!StringUtils.hasText(patch)) {
            return List.of();
        }
        return Arrays.stream(patch.split("\\R"))
                .filter(line -> line.startsWith("+") && !line.startsWith("+++"))
                .map(line -> line.substring(1).trim())
                .filter(StringUtils::hasText)
                .toList();
    }

    public static String addedText(String patch) {
        return String.join("\n", addedLines(patch));
    }
}
