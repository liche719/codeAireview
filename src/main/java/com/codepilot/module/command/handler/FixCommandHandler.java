package com.codepilot.module.command.handler;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.service.PrCommandTaskService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@Component
public class FixCommandHandler implements GithubCommandHandler {

    private final GithubCommandProperties properties;

    private final GithubClient githubClient;

    private final PrCommandTaskService prCommandTaskService;

    private final com.codepilot.task.PrCommandTaskProducer legacyProducer;

    @Autowired
    public FixCommandHandler(GithubCommandProperties properties,
                             GithubClient githubClient,
                             PrCommandTaskService prCommandTaskService) {
        this.properties = properties;
        this.githubClient = githubClient;
        this.prCommandTaskService = prCommandTaskService;
        this.legacyProducer = null;
    }

    public FixCommandHandler(GithubCommandProperties properties,
                             GithubClient githubClient,
                             PrCommandTaskService prCommandTaskService,
                             com.codepilot.task.PrCommandTaskProducer ignoredProducer) {
        this.properties = properties;
        this.githubClient = githubClient;
        this.prCommandTaskService = prCommandTaskService;
        this.legacyProducer = ignoredProducer;
    }

    @Override
    public GithubCommandType commandType() {
        return GithubCommandType.FIX;
    }

    @Override
    public GithubCommandHandleResult handle(GitHubPullRequestWebhookPayload payload) {
        if (!properties.isFixEnabled()) {
            try {
                githubClient.createPullRequestComment(
                        payload.getOwner(),
                        payload.getRepo(),
                        payload.getPullNumber(),
                        "**CodePilot AI** 修复模式未启用。请设置 `CODEPILOT_GITHUB_FIX_ENABLED=true` 后再试。"
                );
            } catch (Exception exception) {
                log.warn("GitHub fix disabled comment failed but ignored, owner={}, repo={}, pullNumber={}, message={}",
                        payload.getOwner(), payload.getRepo(), payload.getPullNumber(),
                        SensitiveDataSanitizer.redact(exception.getMessage()));
            }
            return GithubCommandHandleResult.processed(null, payload.getAction());
        }

        PrCommandTask task = prCommandTaskService.createFixTask(payload);
        if (legacyProducer != null && task != null
                && !isTerminalStatus(task.getStatus())) {
            legacyProducer.send(task.getId());
        }
        return GithubCommandHandleResult.processed(task.getId(), payload.getAction());
    }

    private boolean isTerminalStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
    }
}
