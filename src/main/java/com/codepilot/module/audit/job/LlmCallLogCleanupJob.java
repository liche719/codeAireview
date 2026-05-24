package com.codepilot.module.audit.job;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.audit.service.LlmCallLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmCallLogCleanupJob {

    private final LlmProperties llmProperties;

    private final LlmCallLogService llmCallLogService;

    @Scheduled(cron = "${codepilot.llm.call-log-cleanup-cron:0 30 */6 * * *}")
    public void cleanupExpiredEntries() {
        if (llmProperties == null || !llmProperties.isCallLogCleanupEnabled()) {
            return;
        }
        try {
            int deletedCount = llmCallLogService.deleteExpired(llmProperties.getCallLogRetentionDays());
            if (deletedCount > 0) {
                log.info("LLM call log cleanup deleted expired entries, deletedCount={}", deletedCount);
            }
        } catch (Exception exception) {
            log.warn("LLM call log cleanup failed, errorType={}, message={}",
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }
}
