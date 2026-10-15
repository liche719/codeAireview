package com.codepilot.module.command.trace;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.entity.AgentRun;
import com.codepilot.module.command.runtime.AgentRunService;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class AgentToolTraceAspect {

    private static final int SUMMARY_LIMIT = 1200;

    private final AgentRunService agentRunService;
    private final PrCommandTaskLogService commandTaskLogService;

    @Around("@annotation(dev.langchain4j.agent.tool.Tool)")
    public Object trace(ProceedingJoinPoint joinPoint) throws Throwable {
        Long commandTaskId = AgentTaskTraceContext.currentTaskId();
        Long runId = AgentTaskTraceContext.currentAgentRunId();
        if (commandTaskId == null || runId == null) {
            return joinPoint.proceed();
        }
        AgentRun run = agentRunService.findById(runId);
        if (run == null) {
            return joinPoint.proceed();
        }
        String toolName = joinPoint.getSignature().getName();
        String inputSummary = summarizeArguments(joinPoint.getArgs());
        agentRunService.recordToolCall(run, toolName, inputSummary);
        commandTaskLogService.record(commandTaskId, "TOOL_START", true, toolName, inputSummary);
        long started = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            long durationMillis = elapsedMillis(started);
            String outputSummary = summarizeResult(result);
            agentRunService.recordToolResult(run, toolName, true, outputSummary, durationMillis);
            commandTaskLogService.record(commandTaskId, "TOOL_RESULT", true, toolName,
                    "durationMillis=" + durationMillis + ", output=" + outputSummary);
            return result;
        } catch (Throwable throwable) {
            long durationMillis = elapsedMillis(started);
            String outputSummary = throwable.getClass().getSimpleName() + ": " + summarize(throwable.getMessage());
            agentRunService.recordToolResult(run, toolName, false, outputSummary, durationMillis);
            commandTaskLogService.record(commandTaskId, "TOOL_RESULT", false, toolName,
                    "durationMillis=" + durationMillis + ", error=" + outputSummary);
            throw throwable;
        }
    }

    private String summarizeArguments(Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return "arguments=none";
        }
        StringBuilder summary = new StringBuilder();
        for (int index = 0; index < arguments.length; index++) {
            Object value = arguments[index];
            if (index > 0) {
                summary.append(", ");
            }
            summary.append("arg").append(index).append("=");
            if (value == null) {
                summary.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                summary.append(value);
            } else {
                summary.append(value.getClass().getSimpleName()).append("(length=")
                        .append(String.valueOf(value).length()).append(")");
            }
        }
        return summary.toString();
    }

    private String summarize(Object value) {
        String redacted = SensitiveDataSanitizer.redact(value == null ? "null" : String.valueOf(value));
        return redacted.length() <= SUMMARY_LIMIT ? redacted : redacted.substring(0, SUMMARY_LIMIT);
    }

    private String summarizeResult(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return value.getClass().getSimpleName() + "(length=" + String.valueOf(value).length() + ")";
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}
