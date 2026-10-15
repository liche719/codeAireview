package com.codepilot.module.command.queue;

import com.codepilot.module.command.mapper.PrCommandTaskOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class PrCommandTaskMessageDispatcher {

    private final PrCommandTaskOutboxMapper outboxMapper;

    public void enqueue(Long commandTaskId) {
        if (commandTaskId == null) {
            throw new IllegalArgumentException("commandTaskId is required");
        }
        outboxMapper.enqueue(commandTaskId, LocalDateTime.now());
    }
}
