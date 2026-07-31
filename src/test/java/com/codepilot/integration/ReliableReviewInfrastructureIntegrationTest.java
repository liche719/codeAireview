package com.codepilot.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.security.RedisFixedWindowRateLimiter;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.entity.ReviewTaskOutbox;
import com.codepilot.module.review.mapper.ReviewFileMapper;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.mapper.ReviewTaskOutboxMapper;
import com.codepilot.module.review.persistence.ReviewTaskResultStore;
import com.codepilot.module.review.processor.ReviewTaskProcessingResult;
import com.codepilot.module.review.state.ReviewTaskStateManager;
import com.codepilot.task.ReviewTaskMessage;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "codepilot.scheduling.enabled=false",
        "codepilot.api-auth.enabled=false",
        "codepilot.api-rate-limit.enabled=false",
        "codepilot.llm.enabled=false",
        "codepilot.embedding.enabled=false",
        "codepilot.embedding.dimension=1536",
        "codepilot.rag.enabled=false"
})
class ReliableReviewInfrastructureIntegrationTest {

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
    private ReviewTaskMapper reviewTaskMapper;

    @Autowired
    private ReviewTaskOutboxMapper reviewTaskOutboxMapper;

    @Autowired
    private ReviewFileMapper reviewFileMapper;

    @Autowired
    private ReviewTaskResultStore reviewTaskResultStore;

    @Autowired
    private ReviewTaskStateManager reviewTaskStateManager;

    @Autowired
    private RedisFixedWindowRateLimiter redisRateLimiter;

    @Autowired
    private ReviewTaskProducer reviewTaskProducer;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void shouldUseRealInfrastructureForClaimOutboxRateLimitAndPublisherConfirm() throws Exception {
        ReviewTask task = pendingTask();
        reviewTaskMapper.insert(task);

        assertThat(claimConcurrently(task.getId(), 16)).isEqualTo(1);

        reviewTaskOutboxMapper.enqueue(task.getId(), LocalDateTime.now());
        List<ReviewTaskOutbox> claimedOutbox = reviewTaskOutboxMapper.claimDue(
                LocalDateTime.now(), LocalDateTime.now().minusMinutes(1), 10
        );
        assertThat(claimedOutbox).singleElement().extracting(ReviewTaskOutbox::getTaskId).isEqualTo(task.getId());

        String rateLimitKey = "integration-" + task.getId();
        assertThat(redisRateLimiter.tryConsume(rateLimitKey, 2, Duration.ofSeconds(30)).allowed()).isTrue();
        assertThat(redisRateLimiter.tryConsume(rateLimitKey, 2, Duration.ofSeconds(30)).allowed()).isTrue();
        assertThat(redisRateLimiter.tryConsume(rateLimitKey, 2, Duration.ofSeconds(30)).allowed()).isFalse();

        reviewTaskProducer.sendAndConfirm(task.getId(), Duration.ofSeconds(10));
        Object received = rabbitTemplate.receiveAndConvert(ReviewTaskProducer.REVIEW_TASK_QUEUE, 5000);
        assertThat(received).isInstanceOf(ReviewTaskMessage.class);
        assertThat(((ReviewTaskMessage) received).getTaskId()).isEqualTo(task.getId());
    }

    @Test
    void shouldDeadLetterRejectedMessagesAndRecoverStalePublicationClaims() {
        ReviewTask task = pendingTask();
        task.setStatus("SUCCESS");
        task.setPublishStatus("PUBLISHING");
        task.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        reviewTaskMapper.insert(task);

        LocalDateTime now = LocalDateTime.now();
        assertThat(reviewTaskMapper.findPublicationCandidates(now, now.minusMinutes(2), 10))
                .contains(task.getId());
        assertThat(reviewTaskMapper.claimPublication(task.getId(), now, now.minusMinutes(2))).isEqualTo(1);

        reviewTaskProducer.sendAndConfirm(task.getId(), Duration.ofSeconds(10));
        rabbitTemplate.execute(channel -> {
            var delivery = channel.basicGet(ReviewTaskProducer.REVIEW_TASK_QUEUE, false);
            assertThat(delivery).isNotNull();
            channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
            return null;
        });

        Object deadLetter = rabbitTemplate.receiveAndConvert(
                ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_QUEUE,
                5000
        );
        assertThat(deadLetter).isInstanceOf(ReviewTaskMessage.class);
        assertThat(((ReviewTaskMessage) deadLetter).getTaskId()).isEqualTo(task.getId());
    }

    @Test
    void shouldRollbackResultReplacementWhenTaskCompletionLosesOwnership() {
        ReviewTask task = pendingTask();
        task.setStatus("RUNNING");
        task.setExecutionToken("database-owner");
        task.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        reviewTaskMapper.insert(task);

        ReviewFile oldFile = reviewFile(task.getId(), "src/Old.java");
        reviewFileMapper.insert(oldFile);
        ReviewFile replacement = reviewFile(task.getId(), "src/New.java");
        task.setExecutionToken("stale-owner");

        ReviewTaskProcessingResult result = new ReviewTaskProcessingResult(
                1, 0, "LOW", List.of(replacement), List.of()
        );
        assertThatThrownBy(() -> reviewTaskResultStore.replaceAndComplete(task, result))
                .isInstanceOf(IllegalStateException.class);

        List<ReviewFile> persisted = reviewFileMapper.selectList(
                new LambdaQueryWrapper<ReviewFile>().eq(ReviewFile::getTaskId, task.getId())
        );
        assertThat(persisted).singleElement()
                .extracting(ReviewFile::getFilePath)
                .isEqualTo("src/Old.java");
    }

    private long claimConcurrently(Long taskId, int workers) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                calls.add(() -> reviewTaskStateManager.claim(taskId) != null);
            }
            List<Future<Boolean>> futures = executor.invokeAll(calls);
            return futures.stream().filter(this::claimed).count();
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean claimed(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ReviewTask pendingTask() {
        ReviewTask task = new ReviewTask();
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(999);
        task.setPrUrl("https://github.com/liche719/codeAireview/pull/999");
        task.setHeadSha("integration-sha-" + System.nanoTime());
        task.setReviewCommentMode(ReviewCommentMode.SUMMARY_ONLY.name());
        task.setStatus("PENDING");
        task.setTotalFiles(0);
        task.setTotalIssues(0);
        task.setAttemptCount(0);
        task.setPublishStatus("NOT_REQUESTED");
        task.setPublishAttemptCount(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }

    private ReviewFile reviewFile(Long taskId, String path) {
        ReviewFile file = new ReviewFile();
        file.setTaskId(taskId);
        file.setFilePath(path);
        file.setChangeType("modified");
        file.setSkipped(false);
        file.setCreatedAt(LocalDateTime.now());
        return file;
    }
}
