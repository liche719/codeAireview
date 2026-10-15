package com.codepilot.module.command.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class AgentMetrics {

    private final MeterRegistry registry;

    public void modelTurn() {
        registry.counter("codepilot.agent.model.turns").increment();
    }

    public void toolCall(String result) {
        registry.counter("codepilot.agent.tool.calls", "result", result).increment();
    }

    public void transition(String state) {
        registry.counter("codepilot.agent.run.transitions", "state", state).increment();
    }

    public void workflowWait(Duration duration) {
        registry.timer("codepilot.agent.workflow.wait").record(duration);
    }
}
