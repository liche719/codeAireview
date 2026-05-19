package com.codepilot.task;

import com.codepilot.module.command.service.PrCommandTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PrCommandTaskConsumer {

    private final PrCommandTaskService prCommandTaskService;

    @RabbitListener(queues = PrCommandTaskProducer.PR_COMMAND_TASK_QUEUE)
    public void consume(PrCommandTaskMessage message) {
        log.info("PR command task message consumed, commandTaskId={}, traceId={}",
                message.getCommandTaskId(), message.getTraceId());
        prCommandTaskService.processFixTask(message.getCommandTaskId());
    }
}
