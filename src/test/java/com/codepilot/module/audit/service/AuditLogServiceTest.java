package com.codepilot.module.audit.service;

import com.codepilot.module.audit.entity.AuditLog;
import com.codepilot.module.audit.mapper.AuditLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计写入（2026-10-29）。
 *
 * <p>最关键的一条不是"记得对"，而是：**审计失败不能拖垮主流程**。
 * 为了一条审计记录把已经成功的审查变成失败，是把"可追溯"变成"新的失败点"。
 */
class AuditLogServiceTest {

    @Test
    @DisplayName("正常写入：动作/操作者/来源/目标/结果都落库")
    void recordsFields() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        when(mapper.insert(any(AuditLog.class))).thenReturn(1);
        AuditLogService service = new AuditLogService(mapper);

        service.record(AuditLogService.ACTION_TRIGGER_REVIEW, "api-key", "10.0.0.1",
                "owner/repo#1", "SUCCESS", "taskId=1");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getAction()).isEqualTo("TRIGGER_REVIEW");
        assertThat(saved.getActor()).isEqualTo("api-key");
        assertThat(saved.getSourceIp()).isEqualTo("10.0.0.1");
        assertThat(saved.getTarget()).isEqualTo("owner/repo#1");
        assertThat(saved.getResult()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("写库失败不抛异常：审计是旁路")
    void writeFailureDoesNotThrow() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        when(mapper.insert(any(AuditLog.class))).thenThrow(new RuntimeException("db down"));
        AuditLogService service = new AuditLogService(mapper);

        assertThatCode(() -> service.record(AuditLogService.ACTION_APPROVE_FIX, "u", "ip", "t", "SUCCESS", "d"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("超长字段被截断，不会因为一条审计把事务撑爆")
    void truncatesLongFields() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        when(mapper.insert(any(AuditLog.class))).thenReturn(1);
        AuditLogService service = new AuditLogService(mapper);

        service.record("X".repeat(200), "u", "ip", "t", "SUCCESS", "d".repeat(5000));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getAction()).hasSize(48);
        assertThat(captor.getValue().getDetail()).hasSize(1000);
    }
}
