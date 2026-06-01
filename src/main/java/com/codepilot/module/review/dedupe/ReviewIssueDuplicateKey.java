package com.codepilot.module.review.dedupe;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ReviewIssueDuplicateKey {

    private static final int TITLE_KEY_LIMIT = 80;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ReviewIssueDuplicateKey() {
    }

    public static String key(
            String filePath,
            Integer lineNumber,
            String issueType,
            String issueTypeZh,
            String title,
            String description,
            String suggestion
    ) {
        return normalizePath(filePath)
                + ":"
                + (lineNumber == null ? "NO_LINE" : lineNumber)
                + ":"
                + category(issueType, issueTypeZh, title, description, suggestion);
    }

    private static String category(
            String issueType,
            String issueTypeZh,
            String title,
            String description,
            String suggestion
    ) {
        String type = normalizeToken(issueType);
        String text = normalizeText(issueType, issueTypeZh, title, description, suggestion);

        if (isSqlRisk(type, text)) {
            return "SQL_RISK:" + sqlSubcategory(text);
        }
        if (isSecretRisk(type, text)) {
            return "SECRET_RISK";
        }
        if (isTestMissing(type, text)) {
            return "TEST_MISSING";
        }
        if (isNullRisk(text)) {
            return "NULL_RISK";
        }
        if (StringUtils.hasText(type)) {
            return type + ":" + stableTitleKey(title, description);
        }
        return "UNKNOWN:" + stableTitleKey(title, description);
    }

    private static boolean isSqlRisk(String type, String text) {
        return "SQL_RISK".equals(type)
                || text.contains("sql")
                || text.contains("\u6ce8\u5165");
    }

    private static String sqlSubcategory(String text) {
        if (containsAny(text,
                "concat",
                "concatenation",
                "injection",
                "\u62fc\u63a5",
                "\u6ce8\u5165")) {
            return "CONCAT";
        }
        if (text.contains("mybatis") || text.contains("${")) {
            return "MYBATIS_PLACEHOLDER";
        }
        if (text.contains("select *")) {
            return "SELECT_ALL";
        }
        if (text.contains("update") && text.contains("where")) {
            return "UPDATE_WHERE";
        }
        if (text.contains("delete") && text.contains("where")) {
            return "DELETE_WHERE";
        }
        if (text.contains("like") && text.contains("%")) {
            return "LIKE_WILDCARD";
        }
        return "GENERAL";
    }

    private static boolean isSecretRisk(String type, String text) {
        return "SECURITY".equals(type)
                && containsAny(text, "secret", "token", "password", "apikey", "private key", "credential");
    }

    private static boolean isTestMissing(String type, String text) {
        return "TEST_MISSING".equals(type)
                || containsAny(text, "missing test", "test missing", "\u6d4b\u8bd5\u7f3a\u5931");
    }

    private static boolean isNullRisk(String text) {
        return containsAny(text, "null", "npe", "\u7a7a\u6307\u9488");
    }

    private static boolean containsAny(String text, String... needles) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String needle : needles) {
            if (StringUtils.hasText(needle) && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String stableTitleKey(String title, String description) {
        String key = normalizeToken(StringUtils.hasText(title) ? title : description);
        if (!StringUtils.hasText(key)) {
            return "GENERAL";
        }
        return key.length() <= TITLE_KEY_LIMIT ? key : key.substring(0, TITLE_KEY_LIMIT);
    }

    private static String normalizeText(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                builder.append(' ').append(value);
            }
        }
        String normalized = builder.toString()
                .replace("\\*", "*")
                .replace("\\_", "_")
                .toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim();
    }

    private static String normalizeToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String normalizePath(String filePath) {
        return StringUtils.hasText(filePath)
                ? filePath.trim().replace('\\', '/').toLowerCase(Locale.ROOT)
                : "NO_FILE";
    }
}
