package com.codepilot.common.util;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PromptInputSanitizer {

    private static final Pattern UNTRUSTED_BLOCK_TAG_PATTERN = Pattern.compile(
            "</?\\s*untrusted_[a-z0-9_-]+(?:\\s+[^>]*)?\\s*>",
            Pattern.CASE_INSENSITIVE
    );

    private PromptInputSanitizer() {
    }

    public static String escapeUntrustedBlockDelimiters(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        Matcher matcher = UNTRUSTED_BLOCK_TAG_PATTERN.matcher(content);
        return matcher.replaceAll(match -> escapeAngleBrackets(match.group()));
    }

    private static String escapeAngleBrackets(String content) {
        return content.replace("<", "&lt;").replace(">", "&gt;");
    }
}
