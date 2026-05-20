package com.codepilot.module.command.handler;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.service.PrCommandTaskService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.task.PrCommandTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FixCommandHandler implements GithubCommandHandler {

    private final GithubCommandProperties properties;

    private final GithubClient githubClient;

    private final PrCommandTaskService prCommandTaskService;

    private final PrCommandTaskProducer prCommandTaskProducer;

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
                        payload.getOwner(), payload.getRepo(), payload.getPullNumber(), exception.getMessage());
            }
            return GithubCommandHandleResult.processed(null, payload.getAction());
        }

        PrCommandTask task = prCommandTaskService.createFixTask(payload);
        prCommandTaskProducer.send(task.getId());
        return GithubCommandHandleResult.processed(task.getId(), payload.getAction());
    }
}
