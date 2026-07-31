package com.codepilot.module.git.client;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.git.config.GithubProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubRequestExecutorTest {

    @Test
    void shouldBoundTheWholeGithubOperation() {
        GithubProperties properties = new GithubProperties();
        properties.setCallTimeout(Duration.ofMillis(50));
        GithubRequestExecutor executor = new GithubRequestExecutor(properties, ignored -> { });

        assertThatThrownBy(() -> executor.execute("slow operation", () -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return "late";
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("timed out after 50ms");
    }
}
