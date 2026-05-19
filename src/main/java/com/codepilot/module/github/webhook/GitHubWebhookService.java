package com.codepilot.module.github.webhook;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.service.ReviewTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Slf4j
@Service
public class GitHubWebhookService {

    private static final Duration DEDUP_TTL = Duration.ofSeconds(30);

    private final GitHubWebhookSignatureVerifier signatureVerifier;

    private final GitHubWebhookPayloadParser payloadParser;

    private final ReviewTaskService reviewTaskService;

    private final StringRedisTemplate stringRedisTemplate;

    private final boolean webhookEnabled;

    public GitHubWebhookService(
            GitHubWebhookSignatureVerifier signatureVerifier,
            GitHubWebhookPayloadParser payloadParser,
            ReviewTaskService reviewTaskService,
            StringRedisTemplate stringRedisTemplate,
            @Value("${codepilot.github.webhook-enabled:false}") boolean webhookEnabled
    ) {
        this.signatureVerifier = signatureVerifier;
        this.payloadParser = payloadParser;
        this.reviewTaskService = reviewTaskService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.webhookEnabled = webhookEnabled;
    }

    public GitHubWebhookResponse handle(String event, String delivery, String signature, String payload) {
        if (!webhookEnabled) {
            log.info("GitHub webhook ignored because webhook is disabled, delivery={}", delivery);
            return GitHubWebhookResponse.ignored("webhook disabled");
        }
        String rawPayload = payload == null ? "" : payload;
        if (!signatureVerifier.verify(rawPayload, signature)) {
            throw new BusinessException(401, "invalid GitHub webhook signature");
        }

        GitHubPullRequestWebhookPayload parsedPayload = payloadParser.parse(event, rawPayload);
        if (parsedPayload.isIgnored()) {
            log.info("GitHub webhook ignored, event={}, action={}, reason={}, delivery={}",
                    parsedPayload.getEvent(), parsedPayload.getAction(), parsedPayload.getReason(), delivery);
            return GitHubWebhookResponse.ignored(parsedPayload.getReason(), parsedPayload.getAction());
        }
        if (isDuplicate(parsedPayload, delivery)) {
            log.info("GitHub webhook ignored because duplicate event was detected, event={}, owner={}, repo={}, pullNumber={}, headSha={}, commentId={}, delivery={}",
                    parsedPayload.getEvent(),
                    parsedPayload.getOwner(),
                    parsedPayload.getRepo(),
                    parsedPayload.getPullNumber(),
                    parsedPayload.getHeadSha(),
                    parsedPayload.getCommentId(),
                    delivery);
            return GitHubWebhookResponse.ignored("duplicate event", parsedPayload.getAction());
        }

        ReviewCreateResponse response = reviewTaskService.createTask(
                parsedPayload.getPrUrl(),
                parsedPayload.getTitle()
        );
        log.info("GitHub webhook created review task, taskId={}, event={}, action={}, owner={}, repo={}, pullNumber={}, commentUser={}, delivery={}",
                response.getTaskId(),
                parsedPayload.getEvent(),
                parsedPayload.getAction(),
                parsedPayload.getOwner(),
                parsedPayload.getRepo(),
                parsedPayload.getPullNumber(),
                parsedPayload.getCommentUserLogin(),
                delivery);
        return GitHubWebhookResponse.processed(response.getTaskId(), parsedPayload.getAction());
    }

    private boolean isDuplicate(GitHubPullRequestWebhookPayload payload, String delivery) {
        try {
            String key = buildDedupKey(payload, delivery);
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", DEDUP_TTL);
            return Boolean.FALSE.equals(acquired);
        } catch (Exception exception) {
            log.warn("GitHub webhook dedup check failed, continue creating review task, owner={}, repo={}, pullNumber={}, message={}",
                    payload.getOwner(), payload.getRepo(), payload.getPullNumber(), exception.getMessage());
            return false;
        }
    }

    private String buildDedupKey(GitHubPullRequestWebhookPayload payload, String delivery) {
        if ("issue_comment".equals(payload.getEvent())) {
            if (payload.getCommentId() != null) {
                return "codepilot:webhook:review-command:"
                        + safePart(payload.getOwner())
                        + ":"
                        + safePart(payload.getRepo())
                        + ":"
                        + payload.getPullNumber()
                        + ":"
                        + payload.getCommentId();
            }
            if (StringUtils.hasText(delivery)) {
                return "codepilot:webhook:delivery:" + safePart(delivery);
            }
        }
        if (StringUtils.hasText(payload.getHeadSha())) {
            return "codepilot:webhook:pr-head:"
                    + safePart(payload.getOwner())
                    + ":"
                    + safePart(payload.getRepo())
                    + ":"
                    + payload.getPullNumber()
                    + ":"
                    + safePart(payload.getHeadSha());
        }
        if (StringUtils.hasText(delivery)) {
            return "codepilot:webhook:delivery:" + safePart(delivery);
        }
        return "codepilot:webhook:pr:"
                + safePart(payload.getOwner())
                + ":"
                + safePart(payload.getRepo())
                + ":"
                + payload.getPullNumber();
    }

    private String safePart(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9._-]", "_");
    }
}
