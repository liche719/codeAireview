package com.codepilot.module.command.runtime;

import com.codepilot.module.command.entity.AgentRun;
import com.codepilot.module.command.mapper.AgentRunMapper;
import com.codepilot.module.command.config.AgentRuntimeProperties;
import com.codepilot.module.command.mapper.AgentStepMapper;
import com.codepilot.module.command.metrics.AgentMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 收尾时不能把计数写回 0（2026-10-29，真实故障）。
 *
 * <p>沙箱 PR 上 agent 明明调了两次工具（`agent_step` 有 2 条 TOOL 记录），
 * 但 `agent_run.tool_call_count` 是 0——运行时手里那份对象是调工具**之前**加载的，
 * 收尾 `updateById` 把整行写回去，计数被覆盖。
 *
 * <p>后果不只是指标难看：**限额（max-tool-calls）就是靠这个计数判断的**，覆盖成 0 等于限额永不触发。
 */
class AgentRunCounterRefreshTest {

    private AgentRunService service(AgentRunMapper mapper) {
        return new AgentRunService(mapper, mock(AgentStepMapper.class),
                new AgentRuntimeProperties(), mock(AgentMetrics.class));
    }

    @Test
    @DisplayName("complete 会从库里刷新计数，不会把 tool_call_count 写回 0")
    void completeKeepsPersistedCounters() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentRun stale = new AgentRun();
        stale.setId(1L);
        stale.setToolCallCount(0);   // 调工具之前加载的旧对象
        stale.setModelTurnCount(0);

        AgentRun persisted = new AgentRun();
        persisted.setId(1L);
        persisted.setToolCallCount(2);
        persisted.setModelTurnCount(3);
        persisted.setContinuationCount(0);
        when(mapper.selectById(1L)).thenReturn(persisted);

        service(mapper).complete(stale, "done");

        assertThat(stale.getToolCallCount()).isEqualTo(2);
        assertThat(stale.getModelTurnCount()).isEqualTo(3);
        assertThat(stale.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("fail 同样刷新计数")
    void failKeepsPersistedCounters() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentRun stale = new AgentRun();
        stale.setId(2L);
        stale.setToolCallCount(0);

        AgentRun persisted = new AgentRun();
        persisted.setId(2L);
        persisted.setToolCallCount(1);
        persisted.setModelTurnCount(2);
        when(mapper.selectById(2L)).thenReturn(persisted);

        service(mapper).fail(stale, "boom");

        assertThat(stale.getToolCallCount()).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo("FAILED");
    }
}
