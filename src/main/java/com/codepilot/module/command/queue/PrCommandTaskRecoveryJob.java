package com.codepilot.module.command.queue;

import com.codepilot.module.command.config.PrCommandTaskProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "codepilot.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class PrCommandTaskRecoveryJob {

    private final PrCommandTaskMapper taskMapper;

    private final PrCommandTaskMessageDispatcher dispatcher;

    private final PrCommandTaskProperties properties;

    @Scheduled(fixedDelayString = "${codepilot.command.recovery-poll-interval-millis:30000}")
    @Transactional(rollbackFor = Exception.class)
    public void recoverExpiredExecutions() {
        LocalDateTime now = LocalDateTime.now();
        for (PrCommandTask task : taskMapper.findExpiredExecutions(
                now, Math.max(1, properties.getRecoveryBatchSize()))) {
            if (taskMapper.recoverExpiredExecution(task.getId(), task.getExecutionToken(), now) == 1) {
                dispatcher.enqueue(task.getId());
            }
        }
    }
}
