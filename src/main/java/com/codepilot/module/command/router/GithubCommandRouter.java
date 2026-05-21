package com.codepilot.module.command.router;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.command.handler.GithubCommandHandler;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GithubCommandRouter {

    private final Map<GithubCommandType, GithubCommandHandler> handlers = new EnumMap<>(GithubCommandType.class);

    private final Set<String> allowedCommentAuthorAssociations;

    public GithubCommandRouter(List<GithubCommandHandler> handlers, GithubCommandProperties properties) {
        handlers.forEach(handler -> this.handlers.put(handler.commandType(), handler));
        this.allowedCommentAuthorAssociations = normalizeAllowedAssociations(properties);
    }

    public GithubCommandHandleResult route(GitHubPullRequestWebhookPayload payload) {
        if (!isAllowedCommentAuthor(payload)) {
            return GithubCommandHandleResult.ignored("comment author is not allowed to run commands", payload.getAction());
        }
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

    private Set<String> normalizeAllowedAssociations(GithubCommandProperties properties) {
        List<String> associations = properties == null ? List.of() : properties.getAllowedCommentAuthorAssociations();
        return associations == null ? Set.of() : associations.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean isAllowedCommentAuthor(GitHubPullRequestWebhookPayload payload) {
        if (payload == null || !"issue_comment".equals(payload.getEvent())) {
            return true;
        }
        if (allowedCommentAuthorAssociations.isEmpty()) {
            return true;
        }
        String association = payload.getCommentAuthorAssociation();
        return StringUtils.hasText(association)
                && allowedCommentAuthorAssociations.contains(association.trim().toUpperCase(Locale.ROOT));
    }
}
