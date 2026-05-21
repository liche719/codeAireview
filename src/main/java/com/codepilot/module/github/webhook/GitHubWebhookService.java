package com.codepilot.module.github.webhook;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.router.GithubCommandRouter;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.policy.GithubRepositoryPolicy;
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

    private final GithubCommandRouter githubCommandRouter;

    private final GithubClient githubClient;

    private final StringRedisTemplate stringRedisTemplate;

    private final GithubRepositoryPolicy githubRepositoryPolicy;

    private final boolean webhookEnabled;

    public GitHubWebhookService(
            GitHubWebhookSignatureVerifier signatureVerifier,
            GitHubWebhookPayloadParser payloadParser,
            ReviewTaskService reviewTaskService,
            GithubCommandRouter githubCommandRouter,
            GithubClient githubClient,
            StringRedisTemplate stringRedisTemplate,
            GithubRepositoryPolicy githubRepositoryPolicy,
            @Value("${codepilot.github.webhook-enabled:false}") boolean webhookEnabled
    ) {
        this.signatureVerifier = signatureVerifier;
        this.payloadParser = payloadParser;
        this.reviewTaskService = reviewTaskService;
        this.githubCommandRouter = githubCommandRouter;
        this.githubClient = githubClient;
        this.stringRedisTemplate = stringRedisTemplate;
        this.githubRepositoryPolicy = githubRepositoryPolicy;
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
        if (!githubRepositoryPolicy.isAllowed(parsedPayload.getOwner(), parsedPayload.getRepo())) {
            log.info("GitHub webhook ignored because repository is not allowed, event={}, action={}, owner={}, repo={}, pullNumber={}, delivery={}",
                    parsedPayload.getEvent(),
                    parsedPayload.getAction(),
                    parsedPayload.getOwner(),
                    parsedPayload.getRepo(),
                    parsedPayload.getPullNumber(),
                    delivery);
            return GitHubWebhookResponse.ignored("repository is not allowed", parsedPayload.getAction());
        }
        if (isBotAuthoredIssueComment(parsedPayload)) {
            log.info("GitHub webhook ignored because comment was authored by bot, event={}, action={}, owner={}, repo={}, pullNumber={}, commentUser={}, delivery={}",
                    parsedPayload.getEvent(),
                    parsedPayload.getAction(),
                    parsedPayload.getOwner(),
                    parsedPayload.getRepo(),
                    parsedPayload.getPullNumber(),
                    parsedPayload.getCommentUserLogin(),
                    delivery);
            return GitHubWebhookResponse.ignored("bot comment", parsedPayload.getAction());
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

        if ("issue_comment".equals(parsedPayload.getEvent())) {
            GithubCommandHandleResult result = githubCommandRouter.route(parsedPayload);
            log.info("GitHub webhook routed PR command, resultId={}, commandType={}, action={}, owner={}, repo={}, pullNumber={}, commentUser={}, delivery={}",
                    result.getId(),
                    parsedPayload.getCommandType(),
                    parsedPayload.getAction(),
                    parsedPayload.getOwner(),
                    parsedPayload.getRepo(),
                    parsedPayload.getPullNumber(),
                    parsedPayload.getCommentUserLogin(),
                    delivery);
            return StringUtils.hasText(result.getReason())
                    ? GitHubWebhookResponse.ignored(result.getReason(), result.getAction())
                    : GitHubWebhookResponse.processed(result.getId(), result.getAction());
        }

        ReviewCreateResponse response = reviewTaskService.createTask(
                parsedPayload.getPrUrl(),
                parsedPayload.getTitle(),
                resolveReviewCommentMode(parsedPayload),
                parsedPayload.getHeadSha()
        );
        log.info("GitHub webhook created review task, taskId={}, event={}, action={}, owner={}, repo={}, pullNumber={}, delivery={}",
                response.getTaskId(),
                parsedPayload.getEvent(),
                parsedPayload.getAction(),
                parsedPayload.getOwner(),
                parsedPayload.getRepo(),
                parsedPayload.getPullNumber(),
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

    private boolean isBotAuthoredIssueComment(GitHubPullRequestWebhookPayload payload) {
        if (!"issue_comment".equals(payload.getEvent()) || !StringUtils.hasText(payload.getCommentUserLogin())) {
            return false;
        }
        String botLogin = githubClient == null ? null : githubClient.getAuthenticatedUserLogin();
        return StringUtils.hasText(botLogin) && botLogin.equalsIgnoreCase(payload.getCommentUserLogin());
    }

    private String buildDedupKey(GitHubPullRequestWebhookPayload payload, String delivery) {
        if ("issue_comment".equals(payload.getEvent())) {
            if (payload.getCommentId() != null) {
                return "codepilot:webhook:pr-command:"
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

    private ReviewCommentMode resolveReviewCommentMode(GitHubPullRequestWebhookPayload payload) {
        if ("pull_request".equals(payload.getEvent()) && "opened".equals(payload.getAction())) {
            return ReviewCommentMode.INLINE_ONLY;
        }
        return ReviewCommentMode.SUMMARY_ONLY;
    }
}
