package com.codepilot.module.agent.review;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.dto.ReviewRuleContext;
import com.codepilot.module.agent.parser.AiReviewResultParser;
import com.codepilot.module.agent.parser.AiReviewResultSchemaValidator;
import com.codepilot.module.agent.prompt.ReviewPromptBuilder;
import com.codepilot.module.agent.review.cache.ReviewLlmCache;
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
        ReviewLlmCache reviewLlmCache = mock(ReviewLlmCache.class);
        LlmCallLogService llmCallLogService = mock(LlmCallLogService.class);
        ReviewRuleContext ruleContext = new ReviewRuleContext();
        ruleContext.setContent("rule ".repeat(100));
        when(reviewLlmClientRegistry.select()).thenReturn(Optional.of(reviewLlmClient));
        when(reviewLlmClient.providerName()).thenReturn("test");
        when(reviewLlmClient.isAvailable()).thenReturn(true);
        when(reviewLlmCache.find(any(), any())).thenReturn(Optional.empty());
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
                new ReviewLlmCallLogger(properties, llmCallLogService),
                reviewLlmCache
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

    @Test
    void shouldReturnCachedReviewWithoutCallingModelOrWritingCallLog() {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        ReviewLlmClient reviewLlmClient = mock(ReviewLlmClient.class);
        ReviewLlmClientRegistry reviewLlmClientRegistry = mock(ReviewLlmClientRegistry.class);
        ReviewRagService reviewRagService = mock(ReviewRagService.class);
        ReviewLlmCache reviewLlmCache = mock(ReviewLlmCache.class);
        LlmCallLogService llmCallLogService = mock(LlmCallLogService.class);
        AiReviewResult cachedResult = new AiReviewResult(List.of(), "cached");
        when(reviewLlmClientRegistry.select()).thenReturn(Optional.of(reviewLlmClient));
        when(reviewLlmClient.providerName()).thenReturn("test");
        when(reviewLlmClient.isAvailable()).thenReturn(true);
        when(reviewRagService.retrieveRelevantRules(any(), any())).thenReturn(List.of());
        when(reviewLlmCache.find(any(), any())).thenReturn(Optional.of(cachedResult));
        ReviewLlmReviewer reviewer = new ReviewLlmReviewer(
                reviewLlmClientRegistry,
                new AiReviewResultParser(new ObjectMapper(), new AiReviewResultSchemaValidator()),
                reviewRagService,
                new ReviewPromptBuilder(),
                new ReviewLlmInputLimiter(properties),
                new ReviewLlmCallLogger(properties, llmCallLogService),
                reviewLlmCache
        );

        Optional<AiReviewResult> result = reviewer.review(
                new AiReviewRequest(1L, "src/main/java/Demo.java", "+code", List.of("src/main/java/Demo.java")),
                "changed-file",
                0
        );

        assertThat(result).containsSame(cachedResult);
        verify(reviewLlmClient, org.mockito.Mockito.never()).review(any());
        verify(llmCallLogService, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void shouldNormalizeModelIssueSourceToLlmBeforeCachingAndReturning() {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        ReviewLlmClient reviewLlmClient = mock(ReviewLlmClient.class);
        ReviewLlmClientRegistry reviewLlmClientRegistry = mock(ReviewLlmClientRegistry.class);
        ReviewRagService reviewRagService = mock(ReviewRagService.class);
        ReviewLlmCache reviewLlmCache = mock(ReviewLlmCache.class);
        LlmCallLogService llmCallLogService = mock(LlmCallLogService.class);
        when(reviewLlmClientRegistry.select()).thenReturn(Optional.of(reviewLlmClient));
        when(reviewLlmClient.providerName()).thenReturn("test");
        when(reviewLlmClient.isAvailable()).thenReturn(true);
        when(reviewLlmCache.find(any(), any())).thenReturn(Optional.empty());
        when(reviewRagService.retrieveRelevantRules(any(), any())).thenReturn(List.of());
        when(reviewLlmClient.review(any()))
                .thenReturn("""
                        {
                          "issues": [
                            {
                              "filePath": "src/main/java/Demo.java",
                              "lineNumber": 12,
                              "issueType": "BUG_RISK",
                              "issueTypeZh": "潜在缺陷",
                              "severity": "HIGH",
                              "title": "Potential null pointer",
                              "description": "Object may be null before invocation.",
                              "suggestion": "Add a null check.",
                              "source": "TOOL",
                              "ruleReference": null
                            }
                          ],
                          "summary": "found"
                        }
                        """);
        ReviewLlmReviewer reviewer = new ReviewLlmReviewer(
                reviewLlmClientRegistry,
                new AiReviewResultParser(new ObjectMapper(), new AiReviewResultSchemaValidator()),
                reviewRagService,
                new ReviewPromptBuilder(),
                new ReviewLlmInputLimiter(properties),
                new ReviewLlmCallLogger(properties, llmCallLogService),
                reviewLlmCache
        );
        ArgumentCaptor<AiReviewResult> resultCaptor = ArgumentCaptor.forClass(AiReviewResult.class);

        Optional<AiReviewResult> result = reviewer.review(
                new AiReviewRequest(1L, "src/main/java/Demo.java", "+code", List.of("src/main/java/Demo.java")),
                "changed-file",
                0
        );

        assertThat(result).isPresent();
        assertThat(result.get().getIssues().getFirst().getSource()).isEqualTo("LLM");
        verify(reviewLlmCache).save(org.mockito.Mockito.eq("test"), any(), resultCaptor.capture());
        assertThat(resultCaptor.getValue().getIssues().getFirst().getSource()).isEqualTo("LLM");
    }
}
