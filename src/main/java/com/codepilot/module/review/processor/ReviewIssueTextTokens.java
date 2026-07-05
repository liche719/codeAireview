package com.codepilot.module.review.processor;

import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReviewIssueTextTokens {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{2,}|[\\u4e00-\\u9fa5]{2,}");

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "this", "that", "with", "from", "into", "when", "where",
            "will", "would", "could", "should", "issue", "risk", "change", "changed", "file",
            "code", "method", "class", "value", "data", "null", "true", "false", "return",
            "private", "public", "protected", "static", "final", "void", "string", "integer"
    );

    private ReviewIssueTextTokens() {
    }

    static Set<String> tokens(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    static boolean containsAny(Set<String> values, String... needles) {
        for (String needle : needles) {
            if (values.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    static String normalizeUpper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}
