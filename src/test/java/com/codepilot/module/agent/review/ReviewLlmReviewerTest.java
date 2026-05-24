package com.codepilot.module.agent.review;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.ReviewRuleContext;
import com.codepilot.module.agent.parser.AiReviewResultParser;
import com.codepilot.module.agent.parser.AiReviewResultSchemaValidator;
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
        CodeReviewAiAssistant assistant = mock(CodeReviewAiAssistant.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CodeReviewAiAssistant> assistantProvider = mock(ObjectProvider.class);
        ReviewRagService reviewRagService = mock(ReviewRagService.class);
        LlmCallLogService llmCallLogService = mock(LlmCallLogService.class);
        ReviewRuleContext ruleContext = new ReviewRuleContext();
        ruleContext.setContent("rule ".repeat(100));
        when(assistantProvider.getIfAvailable()).thenReturn(assistant);
        when(reviewRagService.retrieveRelevantRules(any(), any())).thenReturn(List.of(ruleContext));
        when(assistant.review(any(), any(), any(), any()))
                .thenReturn(new Result<>("""
                        {
                          "issues": [],
                          "summary": "ok"
                        }
                        """, null, List.of(), null, List.of()));
        ReviewLlmReviewer reviewer = new ReviewLlmReviewer(
                assistantProvider,
                new AiReviewResultParser(new ObjectMapper(), new AiReviewResultSchemaValidator()),
                reviewRagService,
                new ReviewPromptBuilder(),
                new ReviewLlmInputLimiter(properties),
                new ReviewLlmCallLogger(properties, llmCallLogService)
        );
        ArgumentCaptor<String> rulesCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> changedFilesCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LlmCallLog> logCaptor = ArgumentCaptor.forClass(LlmCallLog.class);

        reviewer.review(
                new AiReviewRequest(1L, "src/main/java/Demo.java", "+code", List.of("src/main/java/Demo.java")),
                "changed-file\n".repeat(100),
                0
        );

        verify(assistant).review(any(), any(), rulesCaptor.capture(), changedFilesCaptor.capture());
        assertThat(rulesCaptor.getValue()).hasSize(80).contains("[TRUNCATED");
        assertThat(changedFilesCaptor.getValue()).hasSize(60).contains("[TRUNCATED");
        verify(llmCallLogService).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getRequestSummary())
                .contains("rulesTruncated=true")
                .contains("changedFilesTruncated=true");
    }
}
