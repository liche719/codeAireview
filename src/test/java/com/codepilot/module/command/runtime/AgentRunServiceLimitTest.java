package com.codepilot.module.command.runtime;

import com.codepilot.module.command.config.AgentRuntimeProperties;
import com.codepilot.module.command.entity.AgentRun;
import com.codepilot.module.command.mapper.AgentRunMapper;
import com.codepilot.module.command.mapper.AgentStepMapper;
import com.codepilot.module.command.metrics.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R8：四种硬上限各自抛出<b>带类型</b>的异常，而不是混在一起的 {@code IllegalStateException}。
 *
 * <p>类型信息是整条链路的前提：调用方要靠它决定"说什么"以及"要不要重试"。</p>
 */
class AgentRunServiceLimitTest {

    private AgentRuntimeProperties properties;

    private AgentRunService service;

    @BeforeEach
    void setUp() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentStepMapper stepMapper = mock(AgentStepMapper.class);
        when(stepMapper.nextSequence(any())).thenReturn(1);
        properties = new AgentRuntimeProperties();
        service = new AgentRunService(runMapper, stepMapper, properties, new AgentMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void shouldReportModelTurnLimit() {
        properties.setMaxModelTurns(2);
        AgentRun run = run(2, 0, 0, LocalDateTime.now().plusMinutes(5));

        assertThatThrownBy(() -> service.beginModelTurn(run))
                .isInstanceOf(AgentLimitExceededException.class)
                .satisfies(exception -> {
                    AgentLimitExceededException limit = (AgentLimitExceededException) exception;
                    assertThat(limit.getLimitType()).isEqualTo(AgentLimitExceededException.LimitType.MODEL_TURNS);
                    assertThat(limit.getCurrentValue()).isEqualTo(3);
                    assertThat(limit.getLimitValue()).isEqualTo(2);
                });
    }

    @Test
    void shouldReportToolCallLimit() {
        properties.setMaxToolCalls(1);
        AgentRun run = run(0, 1, 0, LocalDateTime.now().plusMinutes(5));

        assertThatThrownBy(() -> service.recordToolCall(run, "readFile", "summary"))
                .isInstanceOf(AgentLimitExceededException.class)
                .satisfies(exception -> assertThat(((AgentLimitExceededException) exception).getLimitType())
                        .isEqualTo(AgentLimitExceededException.LimitType.TOOL_CALLS));
    }

    @Test
    void shouldReportContinuationLimit() {
        properties.setMaxContinuations(1);
        AgentRun run = run(0, 0, 1, LocalDateTime.now().plusMinutes(5));

        assertThatThrownBy(() -> service.markWaiting(run, "REVIEW", 42L))
                .isInstanceOf(AgentLimitExceededException.class)
                .satisfies(exception -> assertThat(((AgentLimitExceededException) exception).getLimitType())
                        .isEqualTo(AgentLimitExceededException.LimitType.CONTINUATIONS));
    }

    /** 截止时间超过后，当前值不该小于上限——否则文案会出现"还没到上限就说超了"。 */
    @Test
    void shouldReportDeadlineLimitWithElapsedNotBelowTheLimit() {
        properties.setRunDeadline(Duration.ofSeconds(90));
        AgentRun run = run(0, 0, 0, LocalDateTime.now().minusSeconds(10));

        assertThatThrownBy(() -> service.beginModelTurn(run))
                .isInstanceOf(AgentLimitExceededException.class)
                .satisfies(exception -> {
                    AgentLimitExceededException limit = (AgentLimitExceededException) exception;
                    assertThat(limit.getLimitType()).isEqualTo(AgentLimitExceededException.LimitType.RUN_DEADLINE);
                    assertThat(limit.getLimitValue()).isEqualTo(90);
                    assertThat(limit.getCurrentValue()).isGreaterThanOrEqualTo(90);
                });
    }

    /** 可重试故障不能被误标成终态：上限异常只用于这四个计数/时长上限。 */
    @Test
    void shouldNotReportALimitWhileWithinLimits() {
        properties.setMaxToolCalls(5);
        AgentRun run = run(0, 0, 0, LocalDateTime.now().plusMinutes(5));

        service.beginModelTurn(run);
        service.recordToolCall(run, "readFile", "summary");

        assertThat(run.getModelTurnCount()).isEqualTo(1);
        assertThat(run.getToolCallCount()).isEqualTo(1);
    }

    private AgentRun run(int turns, int toolCalls, int continuations, LocalDateTime deadline) {
        AgentRun run = new AgentRun();
        run.setId(1L);
        run.setStatus("RUNNING");
        run.setModelTurnCount(turns);
        run.setToolCallCount(toolCalls);
        run.setContinuationCount(continuations);
        run.setDeadlineAt(deadline);
        return run;
    }
}
