package com.codepilot.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.module.dlq.entity.DeadLetterMessage;
import com.codepilot.module.dlq.mapper.DeadLetterMessageMapper;
import com.codepilot.task.ReviewTaskProducer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实的 PostgreSQL/pgvector 与 RabbitMQ 验证死信链路。
 *
 * <p>要证明的是：一条进入 DLQ 的消息会被消费者取出、**落库**、然后才被 ack。
 * 修复前两个 DLQ 没有任何消费者，消息进去就永久滞留。</p>
 *
 * <p>测试直接把消息发到 DLQ（用默认交换 + 队列名做 routing key），
 * 而不去走"主队列重试耗尽才进 DLQ"的完整路径——后者已被
 * {@code ReliableReviewInfrastructureIntegrationTest} 覆盖，这里聚焦新增的落库环节。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "codepilot.scheduling.enabled=false",
        "codepilot.api-auth.enabled=false",
        "codepilot.api-rate-limit.enabled=false",
        "codepilot.llm.enabled=false",
        "codepilot.embedding.enabled=false",
        "codepilot.embedding.dimension=1536",
        "codepilot.rag.enabled=false",
        "codepilot.dlq.consumer-enabled=true"
})
class DeadLetterInfrastructureIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    );

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine")
    );

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private DeadLetterMessageMapper deadLetterMessageMapper;

    @Test
    void shouldPersistMessageThatLandsInTheDeadLetterQueue() throws Exception {
        String payload = "{\"taskId\":4242,\"traceId\":\"dlq-it-" + Instant.now().toEpochMilli() + "\"}";

        rabbitTemplate.convertAndSend(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_QUEUE, payload);

        DeadLetterMessage persisted = awaitPersisted(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_QUEUE, payload);

        assertThat(persisted).as("the DLQ consumer must persist the dead letter").isNotNull();
        assertThat(persisted.getMessageType()).isEqualTo("REVIEW_TASK");
        assertThat(persisted.getStatus()).isEqualTo("PENDING");
        assertThat(persisted.getPayload()).contains("4242");
        assertThat(persisted.getPayloadDigest()).hasSize(64);
        assertThat(persisted.getAttemptCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldAccumulateAttemptCountInsteadOfDuplicatingRowsForTheSameMessage() throws Exception {
        String payload = "{\"taskId\":5150,\"traceId\":\"dlq-dupe-" + Instant.now().toEpochMilli() + "\"}";

        rabbitTemplate.convertAndSend(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_QUEUE, payload);
        DeadLetterMessage first = awaitPersisted(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_QUEUE, payload);
        assertThat(first).isNotNull();

        rabbitTemplate.convertAndSend(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_QUEUE, payload);
        Thread.sleep(Duration.ofSeconds(3).toMillis());

        List<DeadLetterMessage> rows = deadLetterMessageMapper.selectList(
                new LambdaQueryWrapper<DeadLetterMessage>()
                        .eq(DeadLetterMessage::getPayloadDigest, first.getPayloadDigest())
                        .eq(DeadLetterMessage::getStatus, "PENDING"));

        assertThat(rows)
                .as("the same message must not create duplicate rows")
                .hasSize(1);
        assertThat(rows.getFirst().getAttemptCount())
                .as("a repeated dead letter must accumulate attempts")
                .isGreaterThanOrEqualTo(2);
    }

    private DeadLetterMessage awaitPersisted(String queue, String payload) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            List<DeadLetterMessage> rows = deadLetterMessageMapper.selectList(
                    new LambdaQueryWrapper<DeadLetterMessage>()
                            .eq(DeadLetterMessage::getSourceQueue, queue)
                            .orderByDesc(DeadLetterMessage::getId));
            for (DeadLetterMessage row : rows) {
                if (row.getPayload() != null && row.getPayload().contains(extractMarker(payload))) {
                    return row;
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    private String extractMarker(String payload) {
        int start = payload.indexOf("dlq-");
        return start < 0 ? payload : payload.substring(start, payload.indexOf('"', start));
    }
}
