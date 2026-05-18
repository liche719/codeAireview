package com.codepilot.module.agent.service.impl;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.dto.ReviewRuleContext;
import com.codepilot.module.agent.parser.AiReviewResultParser;
import com.codepilot.module.agent.prompt.ReviewPromptBuilder;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.agent.service.CodeReviewAiAssistant;
import com.codepilot.module.agent.service.ReviewRagService;
import com.codepilot.module.audit.entity.LlmCallLog;
import com.codepilot.module.audit.service.LlmCallLogService;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewServiceImpl implements AiReviewService {

    private static final int RESPONSE_SUMMARY_LIMIT = 1000;

    private final LlmProperties llmProperties;

    private final ObjectProvider<CodeReviewAiAssistant> codeReviewAiAssistantProvider;

    private final AiReviewResultParser aiReviewResultParser;

    private final ReviewRagService reviewRagService;

    private final ReviewPromptBuilder reviewPromptBuilder;

    private final LlmCallLogService llmCallLogService;

    @Override
    public AiReviewResult reviewFile(Long taskId, String filePath, String patch) {
        if (!llmProperties.isEnabled()) {
            log.info("Skip ai review because llm is disabled, filePath={}", filePath);
            return AiReviewResult.empty();
        }
        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            log.info("Skip ai review because llm api key is missing, filePath={}", filePath);
            return AiReviewResult.empty();
        }
        if (!StringUtils.hasText(patch)) {
            log.info("Skip ai review because patch is empty, filePath={}", filePath);
            return AiReviewResult.empty();
        }

        CodeReviewAiAssistant codeReviewAiAssistant = codeReviewAiAssistantProvider.getIfAvailable();
        if (codeReviewAiAssistant == null) {
            log.warn("Skip ai review because CodeReviewAiAssistant bean is unavailable, filePath={}", filePath);
            return AiReviewResult.empty();
        }

        List<ReviewRuleContext> rules = reviewRagService.retrieveRelevantRules(filePath, patch);
        String rulesContext = reviewPromptBuilder.buildRulesContext(rules);
        log.info("AI review RAG context prepared, filePath={}, ruleCount={}, contextChars={}",
                filePath, rules.size(), rulesContext == null ? 0 : rulesContext.length());

        String responseText = null;
        String errorMessage = null;
        boolean success = false;
        long startTime = System.currentTimeMillis();

        try {
            Result<String> result = codeReviewAiAssistant.review(filePath, patch, rulesContext);
            responseText = result == null ? null : result.content();
            if (!StringUtils.hasText(responseText)) {
                errorMessage = "empty model response";
                log.warn("LangChain4j review returned empty content, filePath={}", filePath);
                return AiReviewResult.empty();
            }

            success = true;
            return aiReviewResultParser.parse(responseText);
        } catch (Exception exception) {
            errorMessage = exception.getMessage();
            log.warn("LangChain4j ai review failed, filePath={}, message={}", filePath, errorMessage, exception);
            return AiReviewResult.empty();
        } finally {
            long costTimeMs = System.currentTimeMillis() - startTime;
            saveCallLog(taskId, filePath, patch, rules.size(), costTimeMs, success, errorMessage, responseText);
        }
    }

    private void saveCallLog(
            Long taskId,
            String filePath,
            String patch,
            int ruleCount,
            long costTimeMs,
            boolean success,
            String errorMessage,
            String responseText
    ) {
        try {
            LlmCallLog logRecord = new LlmCallLog();
            logRecord.setTaskId(taskId);
            logRecord.setModelName(llmProperties.getModel());
            logRecord.setCostTimeMs(costTimeMs);
            logRecord.setRequestSummary(buildRequestSummary(filePath, patch, ruleCount));
            logRecord.setResponseSummary(truncate(responseText, RESPONSE_SUMMARY_LIMIT));
            logRecord.setSuccess(success);
            logRecord.setErrorMessage(errorMessage);
            logRecord.setCreatedAt(LocalDateTime.now());
            llmCallLogService.save(logRecord);
        } catch (Exception exception) {
            log.warn("Failed to save llm call log, taskId={}, filePath={}", taskId, filePath, exception);
        }
    }

    private String buildRequestSummary(String filePath, String patch, int ruleCount) {
        int patchLength = patch == null ? 0 : patch.length();
        return "filePath=" + filePath + ", patchLength=" + patchLength + ", ragRuleCount=" + ruleCount;
    }

    private String truncate(String content, int maxLength) {
        if (!StringUtils.hasText(content) || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength);
    }
}
