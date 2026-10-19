package com.codepilot.module.github.webhook;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.util.SensitiveDataSanitizer;
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
        if (isRequiredCommentBodyMissing(parsedPayload)) {
            log.info("GitHub webhook ignored because the comment body is missing, event={}, action={}, owner={}, repo={}, pullNumber={}, delivery={}",
                    parsedPayload.getEvent(),
                    parsedPayload.getAction(),
                    parsedPayload.getOwner(),
                    parsedPayload.getRepo(),
                    parsedPayload.getPullNumber(),
                    delivery);
            return GitHubWebhookResponse.ignored("missing comment body", parsedPayload.getAction());
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

        if (isCommentEvent(parsedPayload.getEvent())) {
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
                    payload.getOwner(), payload.getRepo(), payload.getPullNumber(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            return false;
        }
    }

    /**
     * 评论文体缺失时不处理。
     *
     * <h2>为什么不再按"作者是不是 bot 自己"过滤</h2>
     * 原先这里比较评论作者与 {@code getAuthenticatedUserLogin()}，命中就忽略，
     * 目的是防止「收到评论 → 回一条评论 → 又收到评论」自循环。
     *
     * <p>但这道判断有个致命副作用：<b>当 token 就是使用者本人时，他自己 @bot 的评论会被当成
     * "bot 自己发的"而静默丢弃</b>——单人项目最常见的配置下，@ 功能等于完全不可用。
     * 真机验证时确认过：日志里是 {@code ignored because comment was authored by bot}。</p>
     *
     * <p>真正可靠的身份判据是**评论标记**，而且 {@code GitHubWebhookPayloadParser}
     * 已经在入口按 {@link CommentMarkers#containsAnyIdentity} 丢弃我们自己发的评论
     * （摘要 / Agent 回复 / 命令提示 / 修复结果 / 行内评论）。标记比作者更精确：
     * 它只匹配我们实际产出的内容，与账号是谁无关。</p>
     */
    private boolean isRequiredCommentBodyMissing(GitHubPullRequestWebhookPayload payload) {
        return isCommentEvent(payload.getEvent()) && !StringUtils.hasText(payload.getCommentBody());
    }

    private boolean isCommentEvent(String event) {
        return "issue_comment".equals(event) || "pull_request_review_comment".equals(event);
    }

    private String buildDedupKey(GitHubPullRequestWebhookPayload payload, String delivery) {
        if (isCommentEvent(payload.getEvent())) {
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

    /**
     * PR 事件一律 {@link ReviewCommentMode#BOTH}：摘要给总览与覆盖度，行内给定位。
     *
     * <p>原先是"opened → 只发行内、其余（含 synchronize）→ 只发摘要"，
     * 直接导致两个问题：PR 刚打开时没有覆盖度声明（R2 要求说清"只审了 N/M 个文件"），
     * 而之后的每次推送行内评论再也不出现。真机验证时这正是"8 条可发布 finding、
     * 行内 0 条"的原因。</p>
     */
    private ReviewCommentMode resolveReviewCommentMode(GitHubPullRequestWebhookPayload payload) {
        if ("pull_request".equals(payload.getEvent())) {
            return ReviewCommentMode.BOTH;
        }
        return ReviewCommentMode.SUMMARY_ONLY;
    }
}
