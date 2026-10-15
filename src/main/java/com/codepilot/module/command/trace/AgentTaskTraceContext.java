package com.codepilot.module.command.trace;

import java.util.function.Supplier;

public final class AgentTaskTraceContext {

    public record ScheduledWorkflow(String type, Long taskId) {}

    public record ExecutionResult<T>(T value, ScheduledWorkflow scheduledWorkflow) {}

    private static final class Execution {
        private final Long commandTaskId;
        private final Long agentRunId;
        private ScheduledWorkflow scheduledWorkflow;

        private Execution(Long commandTaskId, Long agentRunId) {
            this.commandTaskId = commandTaskId;
            this.agentRunId = agentRunId;
        }
    }

    private static final ThreadLocal<Execution> CURRENT = new ThreadLocal<>();

    private AgentTaskTraceContext() {
    }

    public static Long currentTaskId() {
        Execution execution = CURRENT.get();
        return execution == null ? null : execution.commandTaskId;
    }

    public static Long currentAgentRunId() {
        Execution execution = CURRENT.get();
        return execution == null ? null : execution.agentRunId;
    }

    public static boolean canScheduleWorkflow() {
        Execution execution = CURRENT.get();
        return execution != null && execution.scheduledWorkflow == null;
    }

    public static void scheduleWorkflow(String type, Long taskId) {
        Execution execution = CURRENT.get();
        if (execution == null || execution.scheduledWorkflow != null || taskId == null) {
            throw new IllegalStateException("Only one asynchronous workflow can be scheduled per Agent turn");
        }
        execution.scheduledWorkflow = new ScheduledWorkflow(type, taskId);
    }

    public static ScheduledWorkflow scheduledWorkflow() {
        Execution execution = CURRENT.get();
        return execution == null ? null : execution.scheduledWorkflow;
    }

    public static <T> T run(Long commandTaskId, Supplier<T> action) {
        return run(commandTaskId, null, action);
    }

    public static <T> T run(Long commandTaskId, Long agentRunId, Supplier<T> action) {
        return runTracked(commandTaskId, agentRunId, action).value();
    }

    public static <T> ExecutionResult<T> runTracked(Long commandTaskId, Long agentRunId, Supplier<T> action) {
        Execution previous = CURRENT.get();
        Execution execution = new Execution(commandTaskId, agentRunId);
        CURRENT.set(execution);
        try {
            T value = action.get();
            return new ExecutionResult<>(value, execution.scheduledWorkflow);
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
