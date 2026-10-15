package com.codepilot.module.dlq.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.dlq.config.DeadLetterProperties;
import com.codepilot.module.dlq.entity.DeadLetterMessage;
import com.codepilot.module.dlq.mapper.DeadLetterMessageMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * 死信的落库、查询、重放与丢弃。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_REPLAYED = "REPLAYED";
    public static final String STATUS_DISCARDED = "DISCARDED";

    private final DeadLetterMessageMapper deadLetterMessageMapper;

    private final DeadLetterProperties properties;

    private final ObjectMapper objectMapper;

    /**
     * 把一条 DLQ 消息落库。调用方必须在本方法成功返回之后才对消息 ack——
     * 落库失败时消息应留在 DLQ，宁可重复也不丢。
     */
    public void record(String sourceQueue, String messageType, Message amqpMessage) {
        String rawPayload = amqpMessage == null || amqpMessage.getBody() == null
                ? ""
                : new String(amqpMessage.getBody(), StandardCharsets.UTF_8);

        DeadLetterMessage record = new DeadLetterMessage();
        record.setSourceQueue(sourceQueue);
        record.setMessageType(messageType);
        record.setPayload(SensitiveDataSanitizer.redactAndTruncate(rawPayload, properties.getPayloadMaxChars()));
        record.setPayloadDigest(sha256(rawPayload));
        record.setErrorType(errorType(amqpMessage));
        record.setErrorMessage(errorMessage(amqpMessage));
        record.setAttemptCount(1);
        record.setStatus(STATUS_PENDING);
        // first_seen_at / last_seen_at 由 SQL 的 CURRENT_TIMESTAMP 写入，这里不设值。

        deadLetterMessageMapper.upsertPending(record);
        log.warn("Dead letter persisted, sourceQueue={}, messageType={}, payloadDigest={}",
                sourceQueue, messageType, record.getPayloadDigest());
    }

    public Page<DeadLetterMessage> page(String status, String sourceQueue, long current, long size) {
        LambdaQueryWrapper<DeadLetterMessage> wrapper = new LambdaQueryWrapper<DeadLetterMessage>()
                .eq(StringUtils.hasText(status), DeadLetterMessage::getStatus, status)
                .eq(StringUtils.hasText(sourceQueue), DeadLetterMessage::getSourceQueue, sourceQueue)
                .orderByDesc(DeadLetterMessage::getId);
        return deadLetterMessageMapper.selectPage(new Page<>(current, size), wrapper);
    }

    public DeadLetterMessage get(Long id) {
        return deadLetterMessageMapper.selectById(id);
    }

    public long countPending() {
        return deadLetterMessageMapper.countPending();
    }

    /**
     * 把死信标记为已重放。
     *
     * <p>本方法<b>只改状态，不负责重新投递</b>：重新投递由调用方（控制器/运维脚本）完成，
     * 因为它需要访问具体的 producer。拆开是为了让状态变更有单一入口、便于审计。</p>
     */
    public void markReplayed(Long id, String operator) {
        DeadLetterMessage record = deadLetterMessageMapper.selectById(id);
        if (record == null) {
            return;
        }
        record.setStatus(STATUS_REPLAYED);
        record.setReplayedAt(LocalDateTime.now());
        record.setReplayedBy(StringUtils.hasText(operator) ? operator : properties.getReplayOperator());
        record.setLastSeenAt(LocalDateTime.now());
        deadLetterMessageMapper.updateById(record);
    }

    public void markDiscarded(Long id, String operator) {
        DeadLetterMessage record = deadLetterMessageMapper.selectById(id);
        if (record == null) {
            return;
        }
        record.setStatus(STATUS_DISCARDED);
        record.setReplayedAt(LocalDateTime.now());
        record.setReplayedBy(StringUtils.hasText(operator) ? operator : properties.getReplayOperator());
        record.setLastSeenAt(LocalDateTime.now());
        deadLetterMessageMapper.updateById(record);
    }

    /** 从死信 payload 中取出原始任务 id，供重放使用。 */
    public Long extractTaskId(DeadLetterMessage record) {
        if (record == null || !StringUtils.hasText(record.getPayload())) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(record.getPayload());
            JsonNode id = node.has("taskId") ? node.get("taskId") : node.get("commandTaskId");
            return id == null || id.isNull() ? null : id.asLong();
        } catch (Exception exception) {
            log.warn("Failed to parse dead letter payload for replay, id={}, message={}",
                    record.getId(), SensitiveDataSanitizer.redact(exception.getMessage()));
            return null;
        }
    }

    private String errorType(Message amqpMessage) {
        Object message = header(amqpMessage, "x-exception-message");
        if (message == null) {
            return deathReason(amqpMessage);
        }
        return message.getClass().getSimpleName();
    }

    private String errorMessage(Message amqpMessage) {
        Object exceptionMessage = header(amqpMessage, "x-exception-message");
        if (exceptionMessage != null) {
            return SensitiveDataSanitizer.redactAndTruncate(String.valueOf(exceptionMessage), 2000);
        }
        String reason = deathReason(amqpMessage);
        return StringUtils.hasText(reason) ? "dead-lettered: " + reason : null;
    }

    /** 读取 RabbitMQ 在死信时写入的 x-death 头里的 reason（rejected / expired / maxlen）。 */
    private String deathReason(Message amqpMessage) {
        Object death = header(amqpMessage, "x-death");
        if (death instanceof java.util.List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> first) {
            Object reason = first.get("reason");
            return reason == null ? null : String.valueOf(reason);
        }
        return null;
    }

    private Object header(Message amqpMessage, String name) {
        MessageProperties messageProperties = amqpMessage == null ? null : amqpMessage.getMessageProperties();
        return messageProperties == null ? null : messageProperties.getHeader(name);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required but unavailable", exception);
        }
    }
}
