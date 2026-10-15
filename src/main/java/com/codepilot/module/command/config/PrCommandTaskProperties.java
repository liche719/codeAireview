package com.codepilot.module.command.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.command")
public class PrCommandTaskProperties {

    private Duration executionLease = Duration.ofMinutes(15);

    private int outboxBatchSize = 20;

    private Duration outboxLockTimeout = Duration.ofMinutes(2);

    private Duration outboxConfirmTimeout = Duration.ofSeconds(5);

    private Duration outboxRetryInitialDelay = Duration.ofSeconds(1);

    private Duration outboxRetryMaxDelay = Duration.ofMinutes(1);

    private int outboxMaxAttempts = 10;

    private int recoveryBatchSize = 50;
}
