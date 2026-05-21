package com.codepilot.module.command.service.impl;

import com.codepilot.module.command.entity.PrCommandTaskLog;
import com.codepilot.module.command.mapper.PrCommandTaskLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrCommandTaskLogServiceImplTest {

    @Test
    void shouldRedactSecretsBeforeSavingCommandTaskLog() {
        PrCommandTaskLogMapper mapper = mock(PrCommandTaskLogMapper.class);
        when(mapper.insert(any(PrCommandTaskLog.class))).thenReturn(1);
        PrCommandTaskLogServiceImpl service = new PrCommandTaskLogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        org.mockito.ArgumentCaptor<PrCommandTaskLog> logCaptor =
                org.mockito.ArgumentCaptor.forClass(PrCommandTaskLog.class);

        service.record(
                1L,
                "PATCH_EXECUTE",
                false,
                "token=ghp_123456789012345678901234567890123456",
                "password=plain-secret"
        );

        verify(mapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getMessage())
                .contains("[REDACTED]")
                .doesNotContain("ghp_123456789012345678901234567890123456");
        assertThat(logCaptor.getValue().getDetail())
                .contains("[REDACTED]")
                .doesNotContain("plain-secret");
    }
}
