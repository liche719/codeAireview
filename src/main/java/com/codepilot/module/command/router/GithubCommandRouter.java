package com.codepilot.module.command.router;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.command.handler.GithubCommandHandler;
import com.codepilot.module.git.policy.GithubRepositoryPolicy;
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

    private final GithubRepositoryPolicy githubRepositoryPolicy;

    public GithubCommandRouter(
            List<GithubCommandHandler> handlers,
            GithubCommandProperties properties,
            GithubRepositoryPolicy githubRepositoryPolicy
    ) {
        handlers.forEach(handler -> this.handlers.put(handler.commandType(), handler));
        this.allowedCommentAuthorAssociations = normalizeAllowedAssociations(properties);
        this.githubRepositoryPolicy = githubRepositoryPolicy;
    }

    public GithubCommandHandleResult route(GitHubPullRequestWebhookPayload payload) {
        if (!isAllowedCommentAuthor(payload)) {
            return GithubCommandHandleResult.ignored("comment author is not allowed to run commands", payload.getAction());
        }
        if (payload != null
                && isCommentEvent(payload.getEvent())
                && !githubRepositoryPolicy.isAllowed(payload.getOwner(), payload.getRepo())) {
            return GithubCommandHandleResult.ignored("repository is not allowed", payload.getAction());
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

    /**
     * 作者授权判定。
     *
     * <p>R14 起必须同时覆盖 {@code issue_comment} 与 {@code pull_request_review_comment}：
     * 原先只认前者，于是 diff 行上的评论会走到 {@code return true} 这条"非评论事件"分支，
     * <b>完全绕过 author_association 白名单</b>——任何人都能在 diff 上 @bot 触发动作。
     * 这是新增事件类型时最容易漏掉的一处授权缺口。</p>
     */
    private boolean isAllowedCommentAuthor(GitHubPullRequestWebhookPayload payload) {
        if (payload == null || !isCommentEvent(payload.getEvent())) {
            return true;
        }
        if (allowedCommentAuthorAssociations.isEmpty()) {
            return false;
        }
        String association = payload.getCommentAuthorAssociation();
        return StringUtils.hasText(association)
                && allowedCommentAuthorAssociations.contains(association.trim().toUpperCase(Locale.ROOT));
    }

    private boolean isCommentEvent(String event) {
        return "issue_comment".equals(event) || "pull_request_review_comment".equals(event);
    }
}
