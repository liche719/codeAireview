package com.codepilot.module.command.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.agent-runtime")
public class AgentRuntimeProperties {

    private boolean resumableEnabled = true;

    private int maxModelTurns = 8;

    private int maxToolCalls = 12;

    private int maxContinuations = 4;

    private Duration runDeadline = Duration.ofMinutes(30);

    private int continuationContextMaxChars = 4000;

    private int waitingPollBatchSize = 50;

    private int conversationMaxTurns = 5;

    private int conversationMaxChars = 6000;
}
