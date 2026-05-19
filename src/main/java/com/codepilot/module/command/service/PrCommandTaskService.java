package com.codepilot.module.command.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;

public interface PrCommandTaskService extends IService<PrCommandTask> {

    PrCommandTask createFixTask(GitHubPullRequestWebhookPayload payload);

    void processFixTask(Long commandTaskId);
}
