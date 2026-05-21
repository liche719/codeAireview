package com.codepilot.module.agent.service.impl;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.parser.CodeFixResultParser;
import com.codepilot.module.agent.service.CodeFixAiAssistant;
import com.codepilot.module.audit.service.LlmCallLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeFixServiceImplTest {

    @Test
    void shouldRethrowAssistantFailureForQueueRetry() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setApiKey("llm-key");
        properties.setModel("test-model");
        CodeFixAiAssistant assistant = mock(CodeFixAiAssistant.class);
        when(assistant.generateFix(any(), any(), any())).thenThrow(new IllegalStateException("llm timeout"));
        LlmCallLogService llmCallLogService = mock(LlmCallLogService.class);
        CodeFixServiceImpl service = new CodeFixServiceImpl(
                properties,
                provider(assistant),
                new CodeFixResultParser(new ObjectMapper()),
                llmCallLogService
        );

        assertThatThrownBy(() -> service.generateFix(1L, "[]", "snippet", "limits"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("code fix generation failed")
                .hasRootCauseMessage("llm timeout");

        verify(llmCallLogService).save(any());
    }

    private ObjectProvider<CodeFixAiAssistant> provider(CodeFixAiAssistant assistant) {
        return new ObjectProvider<>() {
            @Override
            public CodeFixAiAssistant getObject(Object... args) {
                return assistant;
            }

            @Override
            public CodeFixAiAssistant getIfAvailable() {
                return assistant;
            }

            @Override
            public CodeFixAiAssistant getIfUnique() {
                return assistant;
            }

            @Override
            public CodeFixAiAssistant getObject() {
                return assistant;
            }
        };
    }
}
