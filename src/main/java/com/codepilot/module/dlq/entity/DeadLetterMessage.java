package com.codepilot.module.dlq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条最终消费失败、被投进死信队列的消息。
 *
 * <p>由 {@code DeadLetterConsumer} 先写入本表、再对 DLQ 消息 ack——
 * 顺序刻意如此：落库失败时不 ack，让消息留在 DLQ，宁可重复也不丢。</p>
 */
@Data
@TableName("dead_letter_message")
public class DeadLetterMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源 DLQ，例如 codepilot.review.task.dlq。 */
    private String sourceQueue;

    /** 消息类型，例如 REVIEW_TASK / PR_COMMAND_TASK。 */
    private String messageType;

    /** 原始消息体（已脱敏）。 */
    private String payload;

    /** payload 的 sha256，用于识别"同一条消息反复死亡"。 */
    private String payloadDigest;

    private String errorType;

    private String errorMessage;

    private Integer attemptCount;

    /** PENDING / REPLAYED / DISCARDED。 */
    private String status;

    private LocalDateTime replayedAt;

    private String replayedBy;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;
}
