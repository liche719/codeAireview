package com.codepilot.module.dlq.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.dlq.config.DeadLetterProperties;
import com.codepilot.module.dlq.entity.DeadLetterMessage;
import com.codepilot.module.dlq.mapper.DeadLetterMessageMapper;
import com.codepilot.module.dlq.service.DeadLetterService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 死信积压指标与保留期清理。
 *
 * <p>指标直接从**数据库**的 PENDING 计数读取，而不是向 RabbitMQ 管理接口查队列深度：
 * 消费者会把消息尽快搬进数据库，所以数据库计数就是真正待处理的工作量；
 * 而且这样不需要额外的管理连接，失败时只是这一次刷新不生效，不会影响应用启动。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "codepilot.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DeadLetterMaintenanceJob {

    private final DeadLetterService deadLetterService;

    private final DeadLetterMessageMapper deadLetterMessageMapper;

    private final DeadLetterProperties properties;

    private final AtomicLong pendingBacklog = new AtomicLong();

    @PostConstruct
    void registerBacklogGauge(MeterRegistry meterRegistry) {
        Gauge.builder("codepilot.dlq.backlog", pendingBacklog, AtomicLong::doubleValue)
                .description("Dead letter messages still waiting for an operator")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${codepilot.dlq.maintenance-interval-millis:30000}")
    public void refreshBacklogAndCleanUp() {
        try {
            pendingBacklog.set(deadLetterService.countPending());
        } catch (Exception exception) {
            // 指标刷新失败绝不能影响主链路，也不能让应用启动失败。
            log.warn("Failed to refresh dead letter backlog metric, errorType={}, message={}",
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
        }
        cleanUpExpiredRecords();
    }

    private void cleanUpExpiredRecords() {
        int retentionDays = properties.getRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        try {
            LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
            int deleted = deadLetterMessageMapper.delete(new LambdaQueryWrapper<DeadLetterMessage>()
                    .lt(DeadLetterMessage::getLastSeenAt, threshold));
            if (deleted > 0) {
                log.info("Expired dead letter records removed, deleted={}, retentionDays={}",
                        deleted, retentionDays);
            }
        } catch (Exception exception) {
            log.warn("Failed to clean up expired dead letter records, errorType={}, message={}",
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }
}
