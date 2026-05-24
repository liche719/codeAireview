package com.codepilot.module.audit.service.impl;

import com.codepilot.module.audit.mapper.LlmCallLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmCallLogServiceImplTest {

    @Test
    void shouldDeleteExpiredLogsByRetentionDays() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        when(mapper.deleteCreatedBefore(org.mockito.Mockito.any())).thenReturn(5);
        LlmCallLogServiceImpl service = new LlmCallLogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        LocalDateTime before = LocalDateTime.now().minusDays(30).minusSeconds(2);
        LocalDateTime after = LocalDateTime.now().minusDays(30).plusSeconds(2);

        int deletedCount = service.deleteExpired(30);

        assertThat(deletedCount).isEqualTo(5);
        verify(mapper).deleteCreatedBefore(cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isBetween(before, after);
    }

    @Test
    void shouldSkipCleanupWhenRetentionIsDisabled() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        LlmCallLogServiceImpl service = new LlmCallLogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        int deletedCount = service.deleteExpired(0);

        assertThat(deletedCount).isZero();
        verify(mapper, never()).deleteCreatedBefore(org.mockito.Mockito.any());
    }
}
