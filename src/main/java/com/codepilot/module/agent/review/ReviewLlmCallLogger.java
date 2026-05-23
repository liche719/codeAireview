package com.codepilot.module.agent.review;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.audit.entity.LlmCallLog;
import com.codepilot.module.audit.service.LlmCallLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewLlmCallLogger {

    private static final int RESPONSE_SUMMARY_LIMIT = 1000;

    private final LlmProperties llmProperties;

    private final LlmCallLogService llmCallLogService;

    public void save(
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
            logRecord.setResponseSummary(SensitiveDataSanitizer.redactAndTruncate(responseText, RESPONSE_SUMMARY_LIMIT));
            logRecord.setSuccess(success);
            logRecord.setErrorMessage(SensitiveDataSanitizer.redact(errorMessage));
            logRecord.setCreatedAt(LocalDateTime.now());
            llmCallLogService.save(logRecord);
        } catch (Exception exception) {
            log.warn("Failed to save llm call log, taskId={}, filePath={}, errorType={}, message={}",
                    taskId,
                    filePath,
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    private String buildRequestSummary(String filePath, String patch, int ruleCount) {
        int patchLength = patch == null ? 0 : patch.length();
        return "filePath=" + filePath + ", patchLength=" + patchLength + ", ragRuleCount=" + ruleCount;
    }
}
