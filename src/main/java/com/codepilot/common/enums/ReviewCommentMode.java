package com.codepilot.common.enums;

import java.util.Locale;

public enum ReviewCommentMode {

    SUMMARY_ONLY,
    INLINE_ONLY;

    public static ReviewCommentMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return SUMMARY_ONLY;
        }
        try {
            return ReviewCommentMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return SUMMARY_ONLY;
        }
    }

    public boolean isInlineOnly() {
        return this == INLINE_ONLY;
    }
}
