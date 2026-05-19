package com.codepilot.module.command.handler;

import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;

public interface GithubCommandHandler {

    GithubCommandType commandType();

    GithubCommandHandleResult handle(GitHubPullRequestWebhookPayload payload);
}
