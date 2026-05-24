package com.codepilot.module.agent.review;

import com.codepilot.infrastructure.llm.LlmProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewLlmGateTest {

    @Test
    void shouldRejectWhenLlmIsDisabled() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(false);
        properties.setApiKey("test-key");
        ReviewLlmGate gate = new ReviewLlmGate(properties);

        assertThat(gate.isAvailable("src/main/java/Demo.java", 1)).isFalse();
    }

    @Test
    void shouldRejectWhenApiKeyIsMissing() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setApiKey(" ");
        ReviewLlmGate gate = new ReviewLlmGate(properties);

        assertThat(gate.isAvailable("src/main/java/Demo.java", 1)).isFalse();
    }

    @Test
    void shouldAllowWhenLlmConfigIsUsable() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        ReviewLlmGate gate = new ReviewLlmGate(properties);

        assertThat(gate.isAvailable("src/main/java/Demo.java", 1)).isTrue();
    }
}
