package com.codepilot.module.command.runtime;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.config.AgentRuntimeProperties;
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
        if (turns > Math.max(1, properties.getMaxModelTurns())) {
            throw new IllegalStateException("Agent model turn limit exceeded");
        }
        run.setModelTurnCount(turns);
        run.setStatus("RUNNING");
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        metrics.modelTurn();
        recordStep(run.getId(), "MODEL", "model-turn-" + turns, "STARTED", null, null, null);
    }

    public void recordToolCall(AgentRun run, String name, String inputSummary) {
        int calls = safe(run.getToolCallCount()) + 1;
        if (calls > Math.max(1, properties.getMaxToolCalls())) {
            throw new IllegalStateException("Agent tool call limit exceeded");
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
        if (continuations > Math.max(1, properties.getMaxContinuations())) {
            throw new IllegalStateException("Agent continuation limit exceeded");
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

    public void complete(AgentRun run, String response) {
        run.setStatus("COMPLETED");
        run.setLastResponse(truncate(SensitiveDataSanitizer.redact(response), 4000));
        run.setErrorMessage(null);
        run.setCompletedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        metrics.transition("completed");
    }

    public void fail(AgentRun run, String message) {
        run.setStatus("FAILED");
        run.setErrorMessage(truncate(SensitiveDataSanitizer.redact(message), 2000));
        run.setCompletedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        metrics.transition("failed");
    }

    private void requireWithinLimits(AgentRun run) {
        if (run.getDeadlineAt() != null && run.getDeadlineAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("Agent run deadline exceeded");
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
