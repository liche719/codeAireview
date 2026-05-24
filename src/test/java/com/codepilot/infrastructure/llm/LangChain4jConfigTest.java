package com.codepilot.infrastructure.llm;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jConfigTest {

    @Test
    void shouldCreateStructuredReviewModelWithJsonSchemaCapability() {
        LlmProperties properties = enabledProperties();
        LangChain4jConfig config = new LangChain4jConfig(properties, new AiReviewJsonSchemaFactory());

        ChatModel model = config.structuredCodeReviewChatModel();

        assertThat(model).isNotNull();
        assertThat(model.supportedCapabilities()).contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
    }

    @Test
    void shouldCreateReviewModelWithoutJsonSchemaWhenDisabledForProviderCompatibility() {
        LlmProperties properties = enabledProperties();
        properties.setReviewStructuredOutputEnabled(false);
        LangChain4jConfig config = new LangChain4jConfig(properties, new AiReviewJsonSchemaFactory());

        ChatModel model = config.structuredCodeReviewChatModel();

        assertThat(model).isNotNull();
        assertThat(model.supportedCapabilities()).doesNotContain(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
    }

    @Test
    void shouldKeepDefaultModelWithoutReviewJsonSchemaCapability() {
        LlmProperties properties = enabledProperties();
        LangChain4jConfig config = new LangChain4jConfig(properties, new AiReviewJsonSchemaFactory());

        ChatModel model = config.codeReviewChatModel();

        assertThat(model).isNotNull();
        assertThat(model.supportedCapabilities()).doesNotContain(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
    }

    private LlmProperties enabledProperties() {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://api.openai.com/v1");
        properties.setModel("gpt-4o-mini");
        return properties;
    }
}
