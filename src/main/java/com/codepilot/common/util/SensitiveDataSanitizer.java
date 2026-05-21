package com.codepilot.common.util;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

public final class SensitiveDataSanitizer {

    private static final String REDACTION = "[REDACTED]";

    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----"
    );

    private static final Pattern BASIC_AUTH_URL_PATTERN = Pattern.compile(
            "(?i)(https?://)([^\\s/@:]+):([^\\s/@]+)@"
    );

    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile(
            "(?i)(\\bAuthorization\\b\\s*[:=]\\s*(?:Bearer\\s+)?)([A-Za-z0-9._~+/=-]{8,})"
    );

    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)(\\bBearer\\s+)([A-Za-z0-9._~+/=-]{8,})"
    );

    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "(?i)(\\b(?:password|passwd|secret|token|access[_-]?key|api[_-]?key|private[_-]?key|client[_-]?secret|github[_-]?token|openai[_-]?api[_-]?key|jdbcurl)\\b\\s*[:=]\\s*)([\"']?)([^\"'\\s,;`]+)([\"']?)"
    );

    private static final List<Pattern> STANDALONE_SECRET_PATTERNS = List.of(
            Pattern.compile("\\bsk-(?:proj-)?[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{20,}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")
    );

    private SensitiveDataSanitizer() {
    }

    public static String redact(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String redacted = PRIVATE_KEY_PATTERN.matcher(content).replaceAll(REDACTION);
        redacted = BASIC_AUTH_URL_PATTERN.matcher(redacted).replaceAll("$1" + REDACTION + "@");
        redacted = AUTHORIZATION_PATTERN.matcher(redacted).replaceAll("$1" + REDACTION);
        redacted = BEARER_PATTERN.matcher(redacted).replaceAll("$1" + REDACTION);
        redacted = KEY_VALUE_PATTERN.matcher(redacted).replaceAll("$1$2" + REDACTION + "$4");
        for (Pattern pattern : STANDALONE_SECRET_PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll(REDACTION);
        }
        return redacted;
    }

    public static String redactAndTruncate(String content, int maxLength) {
        String redacted = redact(content);
        if (redacted == null || maxLength < 0 || redacted.length() <= maxLength) {
            return redacted;
        }
        return truncatePreservingRedactionMarker(redacted, maxLength);
    }

    public static String truncatePreservingRedactionMarker(String content, int maxLength) {
        if (content == null || maxLength < 0 || content.length() <= maxLength) {
            return content;
        }
        if (maxLength == 0) {
            return "";
        }
        int markerStart = content.lastIndexOf(REDACTION, Math.min(maxLength - 1, content.length() - 1));
        if (markerStart >= 0 && markerStart < maxLength && markerStart + REDACTION.length() > maxLength) {
            if (maxLength >= REDACTION.length()) {
                return content.substring(0, maxLength - REDACTION.length()) + REDACTION;
            }
            return "";
        }
        return content.substring(0, maxLength);
    }
}
