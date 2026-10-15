package com.codepilot.module.command.handler;

import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.service.PrCommandTaskService;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class ChatCommandHandler implements GithubCommandHandler {

    private final PrCommandTaskService prCommandTaskService;

    private final com.codepilot.task.PrCommandTaskProducer legacyProducer;

    @Autowired
    public ChatCommandHandler(PrCommandTaskService prCommandTaskService) {
        this.prCommandTaskService = prCommandTaskService;
        this.legacyProducer = null;
    }

    public ChatCommandHandler(PrCommandTaskService prCommandTaskService,
                              com.codepilot.task.PrCommandTaskProducer ignoredProducer) {
        this.prCommandTaskService = prCommandTaskService;
        this.legacyProducer = ignoredProducer;
    }

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
        if (legacyProducer != null && task != null
                && !isTerminalStatus(task.getStatus())) {
            legacyProducer.send(task.getId());
        }
        return GithubCommandHandleResult.processed(task.getId(), payload.getAction());
    }

    private boolean hasReviewTarget(GitHubPullRequestWebhookPayload payload) {
        return payload != null
                && StringUtils.hasText(payload.getOwner())
                && StringUtils.hasText(payload.getRepo())
                && payload.getPullNumber() != null;
    }

    private boolean isTerminalStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
    }

}
