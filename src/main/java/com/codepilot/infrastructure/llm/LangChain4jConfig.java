package com.codepilot.infrastructure.llm;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class LangChain4jConfig {

    private static final String OPENAI_COMPATIBLE_PROVIDER = "openai-compatible";

    private final LlmProperties llmProperties;

    private final AiReviewJsonSchemaFactory aiReviewJsonSchemaFactory;

    @Bean
    @Primary
    @SuppressWarnings("deprecation")
    @ConditionalOnProperty(prefix = "codepilot.llm", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ChatModel codeReviewChatModel() {
        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            log.info("LangChain4j ChatModel bean was not created because codepilot.llm.api-key is empty");
            return null;
        }
        if (!isOpenAiCompatibleProvider()) {
            log.warn("LangChain4j ChatModel bean was not created because provider is unsupported: {}",
                    llmProperties.getProvider());
            return null;
        }

        Duration timeout = Duration.ofSeconds(Math.max(1, llmProperties.getTimeoutSeconds()));
        String baseUrl = normalizeBaseUrl(llmProperties.getBaseUrl());

        log.info("Creating LangChain4j ChatModel, provider={}, model={}, baseUrl={}, timeoutSeconds={}",
                llmProperties.getProvider(),
                llmProperties.getModel(),
                baseUrl,
                timeout.toSeconds());

        return baseOpenAiChatModelBuilder(baseUrl, timeout)
                .build();
    }

    @Bean
    @SuppressWarnings("deprecation")
    @ConditionalOnProperty(prefix = "codepilot.llm", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ChatModel structuredCodeReviewChatModel() {
        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            log.info("Structured review ChatModel bean was not created because codepilot.llm.api-key is empty");
            return null;
        }
        if (!isOpenAiCompatibleProvider()) {
            log.warn("Structured review ChatModel bean was not created because provider is unsupported: {}",
                    llmProperties.getProvider());
            return null;
        }
        Duration timeout = Duration.ofSeconds(Math.max(1, llmProperties.getTimeoutSeconds()));
        String baseUrl = normalizeBaseUrl(llmProperties.getBaseUrl());
        OpenAiChatModel.OpenAiChatModelBuilder builder = baseOpenAiChatModelBuilder(baseUrl, timeout);
        if (!llmProperties.isReviewStructuredOutputEnabled()) {
            log.info("Creating review ChatModel without structured output because codepilot.llm.review-structured-output-enabled=false, provider={}, model={}, baseUrl={}, timeoutSeconds={}",
                    llmProperties.getProvider(),
                    llmProperties.getModel(),
                    baseUrl,
                    timeout.toSeconds());
            return builder.build();
        }

        log.info("Creating structured review ChatModel, provider={}, model={}, baseUrl={}, timeoutSeconds={}",
                llmProperties.getProvider(),
                llmProperties.getModel(),
                baseUrl,
                timeout.toSeconds());

        return builder
                .responseFormat(aiReviewJsonSchemaFactory.responseFormat())
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .strictJsonSchema(true)
                .build();
    }

    private OpenAiChatModel.OpenAiChatModelBuilder baseOpenAiChatModelBuilder(String baseUrl, Duration timeout) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(llmProperties.getApiKey())
                .modelName(llmProperties.getModel())
                .temperature(llmProperties.getTemperature())
                .timeout(timeout);
    }

    private boolean isOpenAiCompatibleProvider() {
        return OPENAI_COMPATIBLE_PROVIDER.equalsIgnoreCase(
                StringUtils.trimWhitespace(llmProperties.getProvider() == null
                        ? ""
                        : llmProperties.getProvider())
        );
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "https://api.openai.com/v1";
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT).startsWith("http")
                ? trimmed
                : "https://" + trimmed;
    }
}
