package com.codepilot.module.agent.review;

import com.codepilot.infrastructure.llm.LlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewLlmClientRegistryTest {

    @Test
    void shouldSelectClientMatchingConfiguredProvider() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("custom-provider");
        ReviewLlmClient unsupportedClient = client("openai-compatible", false);
        ReviewLlmClient supportedClient = client("custom-provider", true);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewLlmClient> clients = mock(ObjectProvider.class);
        when(clients.orderedStream()).thenReturn(Stream.of(unsupportedClient, supportedClient));

        ReviewLlmClientRegistry registry = new ReviewLlmClientRegistry(properties, clients);

        assertThat(registry.select()).containsSame(supportedClient);
    }

    @Test
    void shouldReturnEmptyWhenNoClientSupportsProvider() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("unsupported");
        ReviewLlmClient unsupportedClient = client("openai-compatible", false);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewLlmClient> clients = mock(ObjectProvider.class);
        when(clients.orderedStream()).thenReturn(Stream.of(unsupportedClient));

        ReviewLlmClientRegistry registry = new ReviewLlmClientRegistry(properties, clients);

        assertThat(registry.select()).isEmpty();
    }

    private ReviewLlmClient client(String providerName, boolean supports) {
        ReviewLlmClient client = mock(ReviewLlmClient.class);
        when(client.providerName()).thenReturn(providerName);
        when(client.supports("custom-provider")).thenReturn(supports);
        when(client.supports("unsupported")).thenReturn(supports);
        return client;
    }
}
