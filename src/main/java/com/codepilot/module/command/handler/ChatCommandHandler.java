package com.codepilot.module.command.handler;

import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.service.PrCommandTaskService;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 把 {@code @x-pilotx ...} 自然语言评论转成 {@code CHAT} 类型的 {@code pr_command_task}。
 *
 * <p>本类只负责创建任务，不直接向 RabbitMQ 发送消息。任务落库后由 command outbox
 * （{@code PrCommandTaskOutboxPublisher}）在事务提交后可靠投递，因此这里不存在
 * "创建成功但消息丢失" 的窗口。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatCommandHandler implements GithubCommandHandler {

    private final PrCommandTaskService prCommandTaskService;

    @Override
    public GithubCommandType commandType() {
        return GithubCommandType.CHAT;
    }

    @Override
    public GithubCommandHandleResult handle(GitHubPullRequestWebhookPayload payload) {
        if (!hasReviewTarget(payload)) {
            String action = payload == null || !StringUtils.hasText(payload.getAction())
                    ? "unknown"
                    : payload.getAction();
            log.warn("Skip Agent request because PR identity is incomplete, action={}, owner={}, repo={}, pullNumber={}",
                    action,
                    payload == null ? null : payload.getOwner(),
                    payload == null ? null : payload.getRepo(),
                    payload == null ? null : payload.getPullNumber());
            return GithubCommandHandleResult.ignored("missing PR identity", action);
        }

        PrCommandTask task = prCommandTaskService.createChatTask(payload);
        return GithubCommandHandleResult.processed(task.getId(), payload.getAction());
    }

    private boolean hasReviewTarget(GitHubPullRequestWebhookPayload payload) {
        return payload != null
                && StringUtils.hasText(payload.getOwner())
                && StringUtils.hasText(payload.getRepo())
                && payload.getPullNumber() != null;
    }
}
