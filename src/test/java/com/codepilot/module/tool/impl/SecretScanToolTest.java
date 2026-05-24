package com.codepilot.module.tool.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretScanToolTest {

    private final SecretScanTool secretScanTool = new SecretScanTool();

    @Test
    void shouldDetectNewPasswordTokenOrSecret() {
        var results = secretScanTool.scanSecrets(
                "src/main/java/Demo.java",
                """
                        @@ -8,1 +8,2 @@
                        +String token = "abc123456789";
                        """
        );

        assertThat(results)
                .anySatisfy(result -> {
                    assertThat(result.getIssueType()).isEqualTo("SECURITY");
                    assertThat(result.getSeverity()).isEqualTo("HIGH");
                    assertThat(result.getLineNumber()).isEqualTo(8);
                });
    }

    @Test
    void shouldReportMultipleDistinctSecretLines() {
        var results = secretScanTool.scanSecrets(
                "src/main/java/Demo.java",
                """
                        +String token = "abc123456789";
                        +String password = "change-me";
                        +String token = "abc123456789";
                        """
        );

        assertThat(results).hasSize(2);
        assertThat(results)
                .allSatisfy(result -> {
                    assertThat(result.getIssueType()).isEqualTo("SECURITY");
                    assertThat(result.getSeverity()).isEqualTo("HIGH");
                });
    }

    @Test
    void shouldIgnoreDeletedSecretLine() {
        var results = secretScanTool.scanSecrets(
                "src/main/java/Demo.java",
                """
                        -String token = "abc123";
                        """
        );

        assertThat(results).isEmpty();
    }

    @Test
    void shouldIgnoreFileHeader() {
        var results = secretScanTool.scanSecrets(
                "src/main/java/token/Demo.java",
                """
                        +++ b/src/main/java/token/Demo.java
                        """
        );

        assertThat(results).isEmpty();
    }

    @Test
    void shouldIgnoreSensitiveVariableNamesWithoutHardcodedValues() {
        var results = secretScanTool.scanSecrets(
                "src/main/java/Demo.java",
                """
                        +String tokenProvider = authTokenProvider.current();
                        +String token = System.getenv("TOKEN");
                        """
        );

        assertThat(results).isEmpty();
    }

    @Test
    void shouldDetectCommonSecretValueFormats() {
        var results = secretScanTool.scanSecrets(
                "src/main/java/Demo.java",
                """
                        +String githubToken = "ghp_1234567890abcdefghijklmnopqr";
                        +String awsKey = "AKIA1234567890ABCDEF";
                        +String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.VeryLongSignatureValue123";
                        +String key = "-----BEGIN PRIVATE KEY-----";
                        """
        );

        assertThat(results).hasSize(4);
        assertThat(results)
                .extracting(result -> result.getSeverity())
                .containsOnly("HIGH");
    }
}
