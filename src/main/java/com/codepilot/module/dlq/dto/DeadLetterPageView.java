package com.codepilot.module.dlq.dto;

import com.codepilot.module.dlq.entity.DeadLetterMessage;

import java.util.List;

/**
 * 死信分页结果。刻意不复用 {@code PageView}，避免与它的字段语义耦合。
 */
public record DeadLetterPageView(
        List<DeadLetterMessage> records,
        long total,
        long current,
        long size,
        long pendingCount
) {
}
