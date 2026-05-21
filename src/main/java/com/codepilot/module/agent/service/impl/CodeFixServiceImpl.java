package com.codepilot.module.agent.service.impl;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.common.util.PromptInputSanitizer;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.agent.dto.CodeFixResult;
import com.codepilot.module.agent.parser.CodeFixResultParser;
import com.codepilot.module.agent.service.CodeFixAiAssistant;
import com.codepilot.module.agent.service.CodeFixService;
import com.codepilot.module.audit.entity.LlmCallLog;
import com.codepilot.module.audit.service.LlmCallLogService;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeFixServiceImpl implements CodeFixService {

    private static final int RESPONSE_SUMMARY_LIMIT = 1000;

    private final LlmProperties llmProperties;

    private final ObjectProvider<CodeFixAiAssistant> codeFixAiAssistantProvider;

    private final CodeFixResultParser codeFixResultParser;

    private final LlmCallLogService llmCallLogService;

    @Override
    public CodeFixResult generateFix(Long commandTaskId, String issues, String snippets, String limits) {
        if (!llmProperties.isEnabled() || !StringUtils.hasText(llmProperties.getApiKey())) {
            log.info("Skip code fix because llm is disabled or api key is missing, commandTaskId={}", commandTaskId);
            return CodeFixResult.empty();
        }

        CodeFixAiAssistant assistant = codeFixAiAssistantProvider.getIfAvailable();
        if (assistant == null) {
            log.warn("Skip code fix because CodeFixAiAssistant bean is unavailable, commandTaskId={}", commandTaskId);
            return CodeFixResult.empty();
        }

        long startTime = System.currentTimeMillis();
        String responseText = null;
        String errorMessage = null;
        boolean success = false;
        try {
            Result<String> result = assistant.generateFix(promptSafe(issues), promptSafe(snippets), promptSafe(limits));
            responseText = result == null ? null : result.content();
            CodeFixResult fixResult = codeFixResultParser.parse(responseText);
            success = true;
            return fixResult;
        } catch (Exception exception) {
            errorMessage = SensitiveDataSanitizer.redact(exception.getMessage());
            log.warn("Code fix generation failed, commandTaskId={}, errorType={}, message={}",
                    commandTaskId, exception.getClass().getSimpleName(), errorMessage);
            throw new IllegalStateException("code fix generation failed", exception);
        } finally {
            saveCallLog(commandTaskId, issues, snippets, limits, System.currentTimeMillis() - startTime, success, errorMessage, responseText);
        }
    }

    private void saveCallLog(
            Long commandTaskId,
            String issues,
            String snippets,
            String limits,
            long costTimeMs,
            boolean success,
            String errorMessage,
            String responseText
    ) {
        try {
            LlmCallLog logRecord = new LlmCallLog();
            logRecord.setTaskId(commandTaskId);
            logRecord.setModelName(llmProperties.getModel());
            logRecord.setCostTimeMs(costTimeMs);
            logRecord.setRequestSummary("codeFix issuesLength=" + length(issues)
                    + ", snippetsLength=" + length(snippets)
                    + ", limits=" + limits);
            logRecord.setResponseSummary(SensitiveDataSanitizer.redactAndTruncate(responseText, RESPONSE_SUMMARY_LIMIT));
            logRecord.setSuccess(success);
            logRecord.setErrorMessage(SensitiveDataSanitizer.redact(errorMessage));
            logRecord.setCreatedAt(LocalDateTime.now());
            llmCallLogService.save(logRecord);
        } catch (Exception exception) {
            log.warn("Failed to save code fix llm call log, commandTaskId={}, errorType={}, message={}",
                    commandTaskId, exception.getClass().getSimpleName(), SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private String promptSafe(String content) {
        return PromptInputSanitizer.escapeUntrustedBlockDelimiters(content);
    }

    private String truncate(String content, int maxLength) {
        if (!StringUtils.hasText(content) || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength);
    }
}
