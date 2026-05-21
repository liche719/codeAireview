package com.codepilot.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataSanitizerTest {

    @Test
    void shouldRedactCommonSecretFormats() {
        String sanitized = SensitiveDataSanitizer.redact("""
                password = "super-secret"
                Authorization: Bearer ghp_123456789012345678901234567890123456
                OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuvwxyz
                jdbcUrl=jdbc:postgresql://localhost/db?password=db-secret
                -----BEGIN PRIVATE KEY-----
                abcdefg
                -----END PRIVATE KEY-----
                """);

        assertThat(sanitized)
                .doesNotContain("super-secret")
                .doesNotContain("ghp_123456789012345678901234567890123456")
                .doesNotContain("sk-proj-abcdefghijklmnopqrstuvwxyz")
                .doesNotContain("db-secret")
                .doesNotContain("abcdefg")
                .contains("[REDACTED]");
    }

    @Test
    void shouldRedactBasicAuthInUrls() {
        String sanitized = SensitiveDataSanitizer.redact(
                "https://user:token123456@example.com/owner/repo.git"
        );

        assertThat(sanitized)
                .isEqualTo("https://[REDACTED]@example.com/owner/repo.git");
    }

    @Test
    void shouldNotSplitRedactionMarkerWhenTruncating() {
        String truncated = SensitiveDataSanitizer.truncatePreservingRedactionMarker(
                "x".repeat(10) + "[REDACTED]" + "tail",
                13
        );

        assertThat(truncated)
                .isEqualTo("xxx[REDACTED]");
    }
}
