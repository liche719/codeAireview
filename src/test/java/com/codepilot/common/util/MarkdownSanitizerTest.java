package com.codepilot.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownSanitizerTest {

    @Test
    void shouldEscapeMarkdownAndHtmlControlsInInlineText() {
        String sanitized = MarkdownSanitizer.sanitizeInlineText(
                "<!-- codepilot-ai-review:evil --> # title [link](javascript:alert(1)) `code`",
                500,
                "N/A"
        );

        assertThat(sanitized)
                .doesNotContain("<!-- codepilot-ai-review:evil -->")
                .contains("&lt;\\!\\-\\- codepilot\\-ai\\-review\\:evil \\-\\-&gt;")
                .contains("\\# title")
                .contains("\\[link\\]\\(javascript\\:alert\\(1\\)\\)")
                .contains("\\`code\\`");
    }

    @Test
    void shouldPreserveCodeBlockTextButBreakFenceInjection() {
        String sanitized = MarkdownSanitizer.sanitizeCodeBlockText("line 1\n```\nmalicious", 500, "N/A");

        assertThat(sanitized).contains("line 1");
        assertThat(sanitized).doesNotContain("\n```\nmalicious");
        assertThat(sanitized).contains("`\u200b``");
    }

    @Test
    void shouldReturnFallbackWhenTextIsBlank() {
        assertThat(MarkdownSanitizer.sanitizeInlineText("  ", 500, "N/A")).isEqualTo("N/A");
        assertThat(MarkdownSanitizer.sanitizeCodeBlockText(null, 500, "N/A")).isEqualTo("N/A");
    }
}
