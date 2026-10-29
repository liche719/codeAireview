package com.codepilot.infrastructure.llm;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.DisabledChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class LangChain4jConfig {

    private static final String OPENAI_COMPATIBLE_PROVIDER = "openai-compatible";

    private final LlmProperties llmProperties;

    private final AiReviewJsonSchemaFactory aiReviewJsonSchemaFactory;

    /**
     * 用于给模型调用计数（2026-10-29）。用 ObjectProvider 延迟获取，
     * 避免配置层直接依赖命令模块的 bean。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<
            com.codepilot.module.command.runtime.AgentRunService> agentRunServiceProvider;

    /**
     * 把每次真实模型调用计入 agent_run（2026-10-29）。
     * 用字段注入 + {@code required=false}：配置层不该因为命令模块的 bean 而启动失败。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.codepilot.module.command.trace.AgentModelTurnListener agentModelTurnListener;

    @Bean
    @Primary
    @SuppressWarnings("deprecation")
    public ChatModel codeReviewChatModel() {
        if (!llmProperties.isEnabled()) {
            log.info("LangChain4j ChatModel uses disabled model because codepilot.llm.enabled=false");
            return new DisabledChatModel();
        }
        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            log.info("LangChain4j ChatModel uses disabled model because codepilot.llm.api-key is empty");
            return new DisabledChatModel();
        }
        if (!isOpenAiCompatibleProvider()) {
            log.warn("LangChain4j ChatModel uses disabled model because provider is unsupported: {}",
                    llmProperties.getProvider());
            return new DisabledChatModel();
        }

        Duration timeout = Duration.ofSeconds(Math.max(1, llmProperties.getTimeoutSeconds()));
        String baseUrl = normalizeBaseUrl(llmProperties.getBaseUrl());

        log.info("Creating LangChain4j ChatModel, provider={}, model={}, baseUrl={}, timeoutSeconds={}",
                llmProperties.getProvider(),
                llmProperties.getModel(),
                baseUrl,
                timeout.toSeconds());

        OpenAiChatModel.OpenAiChatModelBuilder chatModelBuilder = baseOpenAiChatModelBuilder(baseUrl, timeout);
        if (agentModelTurnListener != null) {
            chatModelBuilder.listeners(List.of(agentModelTurnListener));
        }
        ChatModel built = chatModelBuilder.build();
        // 2026-10-29：用装饰器统计真实模型调用。listener 路线实测不管用（计数恒为 1），
        // 这里是我们自己的类，每次 chat 必然经过，max-model-turns 才真正生效。
        return agentRunServiceProvider == null ? built : new CountingChatModel(built, agentRunServiceProvider);
    }

    @Bean
    @SuppressWarnings("deprecation")
    public ChatModel structuredCodeReviewChatModel() {
        if (!llmProperties.isEnabled()) {
            log.info("Structured review ChatModel uses disabled model because codepilot.llm.enabled=false");
            return new DisabledChatModel();
        }
        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            log.info("Structured review ChatModel uses disabled model because codepilot.llm.api-key is empty");
            return new DisabledChatModel();
        }
        if (!isOpenAiCompatibleProvider()) {
            log.warn("Structured review ChatModel uses disabled model because provider is unsupported: {}",
                    llmProperties.getProvider());
            return new DisabledChatModel();
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
