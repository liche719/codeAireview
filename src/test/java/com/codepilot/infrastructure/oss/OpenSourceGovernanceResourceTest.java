package com.codepilot.infrastructure.oss;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSourceGovernanceResourceTest {

    @Test
    void shouldDocumentSecurityReportingAndDeploymentRiskControls() throws IOException {
        String security = Files.readString(Path.of("SECURITY.md"), StandardCharsets.UTF_8);

        assertThat(security)
                .contains("Reporting a Vulnerability")
                .contains("GitHub private vulnerability reporting")
                .contains("CODEPILOT_API_AUTH_ENABLED=true")
                .contains("CODEPILOT_GITHUB_WEBHOOK_SECRET")
                .contains("CODEPILOT_GITHUB_ALLOWED_REPOSITORIES")
                .contains("CODEPILOT_GITHUB_FIX_ENABLED=false")
                .contains("least-privilege GitHub token")
                .contains("prompt injection");
    }

    @Test
    void shouldDocumentContributionQualityAndSecurityExpectations() throws IOException {
        String contributing = Files.readString(Path.of("CONTRIBUTING.md"), StandardCharsets.UTF_8);

        assertThat(contributing)
                .contains("mvn test")
                .contains("Prompt regression tests")
                .contains("Deterministic tool eval")
                .contains("GitHub integration unit tests")
                .contains("Security tests")
                .contains("Do not commit `.env`")
                .contains("Do not add or change the project license without maintainer approval");
    }
}
