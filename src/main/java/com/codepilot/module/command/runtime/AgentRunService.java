package com.codepilot.module.command.runtime;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.config.AgentRuntimeProperties;
import com.codepilot.module.command.runtime.AgentLimitExceededException.LimitType;
import com.codepilot.module.command.entity.AgentRun;
import com.codepilot.module.command.entity.AgentStep;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.AgentRunMapper;
import com.codepilot.module.command.mapper.AgentStepMapper;
import com.codepilot.module.command.metrics.AgentMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AgentRunService {

    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final AgentRuntimeProperties properties;
    private final AgentMetrics metrics;

    public AgentRun findById(Long runId) {
        return runId == null ? null : runMapper.selectById(runId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentRun getOrCreate(PrCommandTask task) {
        AgentRun existing = runMapper.findByCommandTaskId(task.getId());
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        AgentRun run = new AgentRun();
        run.setCommandTaskId(task.getId());
        run.setStatus("PENDING");
        run.setModelTurnCount(0);
        run.setToolCallCount(0);
        run.setContinuationCount(0);
        run.setDeadlineAt(now.plus(properties.getRunDeadline()));
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        try {
            runMapper.insert(run);
            return run;
        } catch (DuplicateKeyException exception) {
            return runMapper.findByCommandTaskId(task.getId());
        }
    }

    public void beginModelTurn(AgentRun run) {
        requireWithinLimits(run);
        int turns = safe(run.getModelTurnCount()) + 1;
        int maxTurns = Math.max(1, properties.getMaxModelTurns());
        if (turns > maxTurns) {
            throw new AgentLimitExceededException(LimitType.MODEL_TURNS, turns, maxTurns);
        }
        run.setModelTurnCount(turns);
        run.setStatus("RUNNING");
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        metrics.modelTurn();
        recordStep(run.getId(), "MODEL", "model-turn-" + turns, "STARTED", null, null, null);
    }

    /**
     * 记录**一次真实模型调用**（2026-10-29）。
     *
     * <p><b>为什么需要</b>：原来 `beginModelTurn` 只在任务开始时调一次，之后 LangChain4j 的
     * AiServices 会在内部跑完整条工具循环——**那些调用没有被计数**。结果是
     * `agent_run.model_turn_count` 恒为 1，配 `max-model-turns` 的限额**永远不会触发**：
     * 看起来有治理，实际是死的。
     *
     * <p>本方法由 {@code ChatModelListener} 在每次请求模型前调用；限额在这里判，
     * 抛出的异常会被运行时当作"撞上限"处理（用户可见的收尾话术）。
     */
    public void recordModelTurn(Long runId) {
        if (runId == null) {
            return;
        }
        AgentRun run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }
        int turns = safe(run.getModelTurnCount()) + 1;
        int maxTurns = Math.max(1, properties.getMaxModelTurns());
        if (turns > maxTurns) {
            throw new AgentLimitExceededException(LimitType.MODEL_TURNS, turns, maxTurns);
        }
        run.setModelTurnCount(turns);
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    public void recordToolCall(AgentRun run, String name, String inputSummary) {
        int calls = safe(run.getToolCallCount()) + 1;
        int maxCalls = Math.max(1, properties.getMaxToolCalls());
        if (calls > maxCalls) {
            throw new AgentLimitExceededException(LimitType.TOOL_CALLS, calls, maxCalls);
        }
        run.setToolCallCount(calls);
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        recordStep(run.getId(), "TOOL", name, "STARTED", inputSummary, null, null);
    }

    public void recordToolResult(AgentRun run, String name, boolean success, String outputSummary, long durationMillis) {
        recordStep(run.getId(), "TOOL_RESULT", name, success ? "SUCCESS" : "FAILED",
                null, outputSummary, durationMillis);
        metrics.toolCall(success ? "success" : "failed");
    }

    public void markWaiting(AgentRun run, String workflowType, Long workflowTaskId) {
        int continuations = safe(run.getContinuationCount()) + 1;
        int maxContinuations = Math.max(1, properties.getMaxContinuations());
        if (continuations > maxContinuations) {
            throw new AgentLimitExceededException(LimitType.CONTINUATIONS, continuations, maxContinuations);
        }
        run.setContinuationCount(continuations);
        run.setStatus("WAITING_WORKFLOW");
        run.setWaitingWorkflowType(workflowType);
        run.setWaitingWorkflowTaskId(workflowTaskId);
        run.setContinuationContext(null);
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        metrics.transition("waiting_workflow");
    }

    public void prepareResume(AgentRun run, String continuationContext) {
        LocalDateTime waitingSince = run.getUpdatedAt();
        run.setStatus("PENDING");
        run.setContinuationContext(truncate(continuationContext, properties.getContinuationContextMaxChars()));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        if (waitingSince != null) {
            metrics.workflowWait(Duration.between(waitingSince, LocalDateTime.now()).abs());
        }
        metrics.transition("resumed");
    }


    /**
     * 收尾前先把计数从库里刷新回来（2026-10-29 修）。
     *
     * <p><b>真实故障</b>：沙箱 PR 上 agent 明明调了 2 次工具（`agent_step` 里有 2 条 TOOL 记录），
     * 可 `agent_run.tool_call_count` 却是 **0**——因为运行时手里那份 `AgentRun` 是**调工具之前**加载的，
     * 收尾时 `updateById(run)` 把整行写回去，顺手把计数覆盖成了 0。
     *
     * <p>指标一旦开始骗人，比没有指标更糟：限额（max-tool-calls）就是靠这个计数判断的，
     * 覆盖成 0 意味着限额永远不触发。
     */
    private void refreshCounters(AgentRun run) {
        if (run == null || run.getId() == null) {
            return;
        }
        AgentRun persisted = runMapper.selectById(run.getId());
        if (persisted == null) {
            return;
        }
        run.setModelTurnCount(persisted.getModelTurnCount());
        run.setToolCallCount(persisted.getToolCallCount());
        run.setContinuationCount(persisted.getContinuationCount());
    }

    public void complete(AgentRun run, String response) {
        refreshCounters(run);
        run.setStatus("COMPLETED");
        run.setLastResponse(truncate(SensitiveDataSanitizer.redact(response), 4000));
        run.setErrorMessage(null);
        run.setCompletedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        metrics.transition("completed");
    }

    public void fail(AgentRun run, String message) {
        refreshCounters(run);
        run.setStatus("FAILED");
        run.setErrorMessage(truncate(SensitiveDataSanitizer.redact(message), 2000));
        run.setCompletedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        metrics.transition("failed");
    }

    private void requireWithinLimits(AgentRun run) {
        if (run.getDeadlineAt() != null && run.getDeadlineAt().isBefore(LocalDateTime.now())) {
            // 上限值取配置的时长，当前值取"已经过去多久"。因为是在超期之后才判定，
            // elapsed 必然 >= limit，用它做展示不会出现"还没到上限就说超了"。
            int limitSeconds = (int) Math.max(1L, properties.getRunDeadline().getSeconds());
            long remainingSeconds = Duration.between(LocalDateTime.now(), run.getDeadlineAt()).getSeconds();
            int elapsedSeconds = (int) Math.max(limitSeconds, limitSeconds - remainingSeconds);
            throw new AgentLimitExceededException(LimitType.RUN_DEADLINE, elapsedSeconds, limitSeconds);
        }
    }

    private void recordStep(Long runId, String type, String name, String status,
                            String input, String output, Long durationMillis) {
        AgentStep step = new AgentStep();
        step.setAgentRunId(runId);
        step.setSequenceNo(stepMapper.nextSequence(runId));
        step.setStepType(type);
        step.setName(truncate(name, 128));
        step.setStatus(status);
        step.setInputSummary(truncate(SensitiveDataSanitizer.redact(input), 2000));
        step.setOutputSummary(truncate(SensitiveDataSanitizer.redact(output), 2000));
        step.setDurationMillis(durationMillis);
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);
    }

    private int safe(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String truncate(String value, int limit) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        int safeLimit = Math.max(1, limit);
        return value.length() <= safeLimit ? value : value.substring(0, safeLimit);
    }
}
