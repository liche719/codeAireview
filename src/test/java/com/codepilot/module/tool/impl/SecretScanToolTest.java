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
                        +String token = "abc123";
                        """
        );

        assertThat(results)
                .anySatisfy(result -> {
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
}
