package com.codepilot.common.util;

import org.springframework.util.StringUtils;

public final class MarkdownSanitizer {

    private MarkdownSanitizer() {
    }

    public static String sanitizeInlineText(String content, int maxLength, String fallback) {
        if (!StringUtils.hasText(content)) {
            return fallback;
        }
        String compact = content.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (compact.length() > maxLength) {
            compact = compact.substring(0, maxLength) + "...";
        }
        return escapeMarkdown(compact);
    }

    public static String sanitizeCodeBlockText(String content, int maxLength, String fallback) {
        if (!StringUtils.hasText(content)) {
            return fallback;
        }
        String safe = content.replace("\u0000", "")
                .replace("```", "`\u200b``");
        if (safe.length() > maxLength) {
            safe = safe.substring(0, maxLength) + "\n... truncated ...";
        }
        return safe;
    }

    private static String escapeMarkdown(String content) {
        StringBuilder escaped = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (isMarkdownControl(ch)) {
                escaped.append('\\');
            }
            if (ch == '<') {
                escaped.append("&lt;");
            } else if (ch == '>') {
                escaped.append("&gt;");
            } else if (ch == '&') {
                escaped.append("&amp;");
            } else {
                escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private static boolean isMarkdownControl(char ch) {
        return switch (ch) {
            case '\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '|', '~', ':' -> true;
            default -> false;
        };
    }
}
