package com.codepilot.module.dlq.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.response.Result;
import com.codepilot.module.dlq.config.DeadLetterProperties;
import com.codepilot.module.dlq.consumer.DeadLetterConsumer;
import com.codepilot.module.dlq.dto.DeadLetterPageView;
import com.codepilot.module.dlq.entity.DeadLetterMessage;
import com.codepilot.module.dlq.service.DeadLetterService;
import com.codepilot.task.PrCommandTaskProducer;
import com.codepilot.task.ReviewTaskProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 死信的运维接口。
 *
 * <p>受既有 API Key 过滤器保护（路径匹配 {@code /api/**}），不需要额外鉴权配置。</p>
 *
 * <p><b>重放默认关闭</b>：它会把手上有副作用的消息重新投回主链路，属于显式运维动作。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    private final DeadLetterProperties properties;

    private final ReviewTaskProducer reviewTaskProducer;

    private final PrCommandTaskProducer prCommandTaskProducer;

    @GetMapping
    public Result<DeadLetterPageView> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sourceQueue,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size
    ) {
        Page<DeadLetterMessage> page = deadLetterService.page(status, sourceQueue, current, size);
        return Result.success(new DeadLetterPageView(
                page.getRecords(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize(),
                deadLetterService.countPending()
        ));
    }

    @GetMapping("/{id}")
    public Result<DeadLetterMessage> get(@PathVariable Long id) {
        DeadLetterMessage record = deadLetterService.get(id);
        if (record == null) {
            throw new BusinessException(404, "dead letter message not found, id=" + id);
        }
        return Result.success(record);
    }

    /**
     * 重新投递一条死信。
     *
     * <p>重放是安全的：目标任务走的是既有的 claim + execution token 入口，
     * 重复投递不会导致重复执行。但因为它有副作用，仍需显式开启。</p>
     */
    @PostMapping("/{id}/replay")
    public Result<Void> replay(@PathVariable Long id) {
        if (!properties.isReplayEnabled()) {
            throw new BusinessException(403,
                    "dead letter replay is disabled; set codepilot.dlq.replay-enabled=true to enable it");
        }
        DeadLetterMessage record = deadLetterService.get(id);
        if (record == null) {
            throw new BusinessException(404, "dead letter message not found, id=" + id);
        }
        Long taskId = deadLetterService.extractTaskId(record);
        if (taskId == null) {
            throw new BusinessException(422,
                    "cannot replay dead letter because no task id could be read from its payload, id=" + id);
        }

        if (DeadLetterConsumer.MESSAGE_TYPE_REVIEW_TASK.equals(record.getMessageType())) {
            reviewTaskProducer.send(taskId);
        } else if (DeadLetterConsumer.MESSAGE_TYPE_PR_COMMAND_TASK.equals(record.getMessageType())) {
            prCommandTaskProducer.send(taskId);
        } else {
            throw new BusinessException(422, "unsupported message type for replay: " + record.getMessageType());
        }

        deadLetterService.markReplayed(id, null);
        return Result.success();
    }

    @PostMapping("/{id}/discard")
    public Result<Void> discard(@PathVariable Long id) {
        DeadLetterMessage record = deadLetterService.get(id);
        if (record == null) {
            throw new BusinessException(404, "dead letter message not found, id=" + id);
        }
        deadLetterService.markDiscarded(id, null);
        return Result.success();
    }
}
