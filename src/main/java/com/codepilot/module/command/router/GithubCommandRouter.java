package com.codepilot.module.command.router;

import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.command.handler.GithubCommandHandler;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class GithubCommandRouter {

    private final Map<GithubCommandType, GithubCommandHandler> handlers = new EnumMap<>(GithubCommandType.class);

    public GithubCommandRouter(List<GithubCommandHandler> handlers) {
        handlers.forEach(handler -> this.handlers.put(handler.commandType(), handler));
    }

    public GithubCommandHandleResult route(GitHubPullRequestWebhookPayload payload) {
        GithubCommandType type = parseType(payload.getCommandType());
        GithubCommandHandler handler = handlers.get(type);
        if (handler == null) {
            return GithubCommandHandleResult.ignored("unsupported command", payload.getAction());
        }
        return handler.handle(payload);
    }

    private GithubCommandType parseType(String value) {
        if (!StringUtils.hasText(value)) {
            return GithubCommandType.NONE;
        }
        try {
            return GithubCommandType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return GithubCommandType.UNKNOWN;
        }
    }
}
