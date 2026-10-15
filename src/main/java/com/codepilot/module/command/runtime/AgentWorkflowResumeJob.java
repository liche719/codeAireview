package com.codepilot.module.command.runtime;

import com.codepilot.module.command.config.AgentRuntimeProperties;
import com.codepilot.module.command.entity.AgentRun;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.AgentRunMapper;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.queue.PrCommandTaskMessageDispatcher;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "codepilot.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AgentWorkflowResumeJob {

    private final AgentRunMapper agentRunMapper;
    private final AgentRunService agentRunService;
    private final ReviewTaskMapper reviewTaskMapper;
    private final PrCommandTaskMapper commandTaskMapper;
    private final PrCommandTaskStateManager commandTaskStateManager;
    private final PrCommandTaskMessageDispatcher dispatcher;
    private final AgentRuntimeProperties properties;

    @Scheduled(fixedDelayString = "${codepilot.agent-runtime.waiting-poll-interval-millis:2000}")
    @Transactional(rollbackFor = Exception.class)
    public void resumeCompletedWorkflows() {
        for (AgentRun run : agentRunMapper.findWaiting(Math.max(1, properties.getWaitingPollBatchSize()))) {
            WorkflowOutcome outcome = resolveOutcome(run);
            if (!outcome.terminal()) {
                continue;
            }
            if (agentRunMapper.claimForResume(run.getId()) == 0) {
                continue;
            }
            if (run.getDeadlineAt() != null && run.getDeadlineAt().isBefore(LocalDateTime.now())) {
                agentRunService.fail(run, "Agent run deadline exceeded while waiting for workflow");
                PrCommandTask commandTask = commandTaskMapper.selectById(run.getCommandTaskId());
                if (commandTask != null) {
                    commandTaskStateManager.markFailed(commandTask, "Agent run deadline exceeded");
                }
                continue;
            }
            agentRunService.prepareResume(run, outcome.summary());
            if (commandTaskStateManager.resumeWaiting(run.getCommandTaskId())) {
                dispatcher.enqueue(run.getCommandTaskId());
            } else {
                agentRunService.fail(run, "Waiting command task could not be resumed");
            }
        }
    }

    private WorkflowOutcome resolveOutcome(AgentRun run) {
        if (run.getWaitingWorkflowTaskId() == null || !StringUtils.hasText(run.getWaitingWorkflowType())) {
            return new WorkflowOutcome(true, "workflowStatus: FAILED\nreason: missing workflow identity");
        }
        String type = run.getWaitingWorkflowType().toUpperCase(Locale.ROOT);
        if ("REVIEW".equals(type)) {
            ReviewTask task = reviewTaskMapper.selectById(run.getWaitingWorkflowTaskId());
            if (task == null) {
                return new WorkflowOutcome(true, "workflowType: REVIEW\nworkflowStatus: FAILED\nreason: task not found");
            }
            if (!terminal(task.getStatus())) {
                return WorkflowOutcome.waiting();
            }
            return new WorkflowOutcome(true, "workflowType: REVIEW\nworkflowTaskId: " + task.getId()
                    + "\nworkflowStatus: " + task.getStatus()
                    + "\ntotalFiles: " + task.getTotalFiles()
                    + "\ntotalIssues: " + task.getTotalIssues()
                    + "\nriskLevel: " + safe(task.getRiskLevel()));
        }
        PrCommandTask task = commandTaskMapper.selectById(run.getWaitingWorkflowTaskId());
        if (task == null) {
            return new WorkflowOutcome(true, "workflowType: " + type
                    + "\nworkflowStatus: FAILED\nreason: task not found");
        }
        if (!terminal(task.getStatus())) {
            return WorkflowOutcome.waiting();
        }
        return new WorkflowOutcome(true, "workflowType: " + type
                + "\nworkflowTaskId: " + task.getId()
                + "\nworkflowStatus: " + task.getStatus()
                + "\nexecutionStage: " + safe(task.getExecutionStage())
                + "\ncreatedPrNumber: " + task.getCreatedPrNumber()
                + "\nerror: " + safe(task.getErrorMessage()));
    }

    private boolean terminal(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "N/A";
    }

    private record WorkflowOutcome(boolean terminal, String summary) {
        private static WorkflowOutcome waiting() {
            return new WorkflowOutcome(false, null);
        }
    }
}
