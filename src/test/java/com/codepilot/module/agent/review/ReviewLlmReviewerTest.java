package com.codepilot.module.agent.review;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.ReviewRuleContext;
import com.codepilot.module.agent.parser.AiReviewResultParser;
import com.codepilot.module.agent.parser.AiReviewResultSchemaValidator;
import com.codepilot.module.agent.prompt.ReviewPromptBuilder;
import com.codepilot.module.agent.service.ReviewRagService;
import com.codepilot.module.audit.entity.LlmCallLog;
import com.codepilot.module.audit.service.LlmCallLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewLlmReviewerTest {

    @Test
    void shouldTruncateLargeRulesAndChangedFilesContextBeforeCallingAssistant() {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        properties.setMaxReviewPatchChars(1000);
        properties.setMaxReviewRulesChars(80);
        properties.setMaxReviewContextChars(60);
        ReviewLlmClient reviewLlmClient = mock(ReviewLlmClient.class);
        ReviewLlmClientRegistry reviewLlmClientRegistry = mock(ReviewLlmClientRegistry.class);
        ReviewRagService reviewRagService = mock(ReviewRagService.class);
        LlmCallLogService llmCallLogService = mock(LlmCallLogService.class);
        ReviewRuleContext ruleContext = new ReviewRuleContext();
        ruleContext.setContent("rule ".repeat(100));
        when(reviewLlmClientRegistry.select()).thenReturn(Optional.of(reviewLlmClient));
        when(reviewLlmClient.providerName()).thenReturn("test");
        when(reviewLlmClient.isAvailable()).thenReturn(true);
        when(reviewRagService.retrieveRelevantRules(any(), any())).thenReturn(List.of(ruleContext));
        when(reviewLlmClient.review(any()))
                .thenReturn("""
                        {
                          "issues": [],
                          "summary": "ok"
                        }
                        """);
        ReviewLlmReviewer reviewer = new ReviewLlmReviewer(
                reviewLlmClientRegistry,
                new AiReviewResultParser(new ObjectMapper(), new AiReviewResultSchemaValidator()),
                reviewRagService,
                new ReviewPromptBuilder(),
                new ReviewLlmInputLimiter(properties),
                new ReviewLlmCallLogger(properties, llmCallLogService)
        );
        ArgumentCaptor<ReviewLlmInput> inputCaptor = ArgumentCaptor.forClass(ReviewLlmInput.class);
        ArgumentCaptor<LlmCallLog> logCaptor = ArgumentCaptor.forClass(LlmCallLog.class);

        reviewer.review(
                new AiReviewRequest(1L, "src/main/java/Demo.java", "+code", List.of("src/main/java/Demo.java")),
                "changed-file\n".repeat(100),
                0
        );

        verify(reviewLlmClient).review(inputCaptor.capture());
        assertThat(inputCaptor.getValue().rulesContext()).hasSize(80).contains("[TRUNCATED");
        assertThat(inputCaptor.getValue().changedFilesContext()).hasSize(60).contains("[TRUNCATED");
        verify(llmCallLogService).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getRequestSummary())
                .contains("rulesTruncated=true")
                .contains("changedFilesTruncated=true");
    }
}
