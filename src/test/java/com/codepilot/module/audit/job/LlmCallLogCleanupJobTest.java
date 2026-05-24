package com.codepilot.module.audit.job;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.audit.service.LlmCallLogService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmCallLogCleanupJobTest {

    @Test
    void shouldDeleteExpiredLlmCallLogsWhenCleanupIsEnabled() {
        LlmProperties properties = new LlmProperties();
        properties.setCallLogCleanupEnabled(true);
        properties.setCallLogRetentionDays(21);
        LlmCallLogService service = mock(LlmCallLogService.class);
        when(service.deleteExpired(21)).thenReturn(2);
        LlmCallLogCleanupJob job = new LlmCallLogCleanupJob(properties, service);

        job.cleanupExpiredEntries();

        verify(service).deleteExpired(21);
    }

    @Test
    void shouldSkipCleanupWhenDisabled() {
        LlmProperties properties = new LlmProperties();
        properties.setCallLogCleanupEnabled(false);
        LlmCallLogService service = mock(LlmCallLogService.class);
        LlmCallLogCleanupJob job = new LlmCallLogCleanupJob(properties, service);

        job.cleanupExpiredEntries();

        verify(service, never()).deleteExpired(org.mockito.Mockito.anyInt());
    }
}
