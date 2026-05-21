package com.codepilot.module.agent.service.impl;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.parser.AiReviewResultParser;
import com.codepilot.module.agent.prompt.ReviewPromptBuilder;
import com.codepilot.module.agent.service.CodeReviewAiAssistant;
import com.codepilot.module.agent.service.ReviewRagService;
import com.codepilot.module.audit.entity.LlmCallLog;
import com.codepilot.module.audit.service.LlmCallLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiReviewServiceImplTest {

    @Test
    void shouldSaveFailedCallLogAndThrowWhenModelReturnsInvalidJson() {
        TestContext context = new TestContext();
        when(context.assistant.review(any(), any(), any(), any()))
                .thenReturn(new Result<>("{ invalid json }", null, List.of(), null, List.of()));
        ArgumentCaptor<LlmCallLog> logCaptor = ArgumentCaptor.forClass(LlmCallLog.class);

        assertThatThrownBy(() -> context.service.reviewFile(
                1L,
                "src/main/java/Demo.java",
                "@@ -1,1 +1,2 @@\n+class Demo {}",
                List.of("src/main/java/Demo.java")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse AI review result as JSON");

        verify(context.llmCallLogService).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getSuccess()).isFalse();
        assertThat(logCaptor.getValue().getErrorMessage()).isEqualTo("Failed to parse AI review result as JSON");
    }

    @Test
    void shouldSaveFailedCallLogAndThrowWhenModelResponseIsEmpty() {
        TestContext context = new TestContext();
        when(context.assistant.review(any(), any(), any(), any()))
                .thenReturn(new Result<>("", null, List.of(), null, List.of()));
        ArgumentCaptor<LlmCallLog> logCaptor = ArgumentCaptor.forClass(LlmCallLog.class);

        assertThatThrownBy(() -> context.service.reviewFile(
                1L,
                "src/main/java/Demo.java",
                "@@ -1,1 +1,2 @@\n+class Demo {}",
                List.of("src/main/java/Demo.java")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("empty model response");

        verify(context.llmCallLogService).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getSuccess()).isFalse();
        assertThat(logCaptor.getValue().getErrorMessage()).isEqualTo("empty model response");
    }

    private static class TestContext {

        private final CodeReviewAiAssistant assistant = mock(CodeReviewAiAssistant.class);

        @SuppressWarnings("unchecked")
        private final ObjectProvider<CodeReviewAiAssistant> assistantProvider = mock(ObjectProvider.class);

        private final ReviewRagService reviewRagService = mock(ReviewRagService.class);

        private final LlmCallLogService llmCallLogService = mock(LlmCallLogService.class);

        private final AiReviewServiceImpl service;

        private TestContext() {
            LlmProperties llmProperties = new LlmProperties();
            llmProperties.setEnabled(true);
            llmProperties.setApiKey("test-key");
            when(assistantProvider.getIfAvailable()).thenReturn(assistant);
            when(reviewRagService.retrieveRelevantRules(any(), any())).thenReturn(List.of());

            service = new AiReviewServiceImpl(
                    llmProperties,
                    assistantProvider,
                    new AiReviewResultParser(new ObjectMapper()),
                    reviewRagService,
                    new ReviewPromptBuilder(),
                    llmCallLogService
            );
        }
    }
}
