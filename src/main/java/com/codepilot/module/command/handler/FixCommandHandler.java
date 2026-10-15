package com.codepilot.module.command.handler;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.service.PrCommandTaskService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 处理兼容命令 {@code /fix} 的入口：在 fix 能力开启时创建 dry-run 修复任务。
 *
 * <p>与 {@link ChatCommandHandler} 一样，本类只创建任务，不直接向 RabbitMQ 发送消息；
 * 投递统一由 command outbox 负责。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FixCommandHandler implements GithubCommandHandler {

    private final GithubCommandProperties properties;

    private final GithubClient githubClient;

    private final PrCommandTaskService prCommandTaskService;

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
        return GithubCommandHandleResult.processed(task.getId(), payload.getAction());
    }
}
