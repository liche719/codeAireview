package com.codepilot.module.command.router;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.command.handler.GithubCommandHandler;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GithubCommandRouterTest {

    @Test
    void shouldIgnoreUnknownCommandWithoutFallback() {
        GithubCommandHandler reviewHandler = mock(GithubCommandHandler.class);
        when(reviewHandler.commandType()).thenReturn(GithubCommandType.REVIEW);

        GithubCommandHandler chatHandler = mock(GithubCommandHandler.class);
        when(chatHandler.commandType()).thenReturn(GithubCommandType.CHAT);

        GithubCommandRouter router = new GithubCommandRouter(List.of(reviewHandler, chatHandler), new GithubCommandProperties());

        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setEvent("issue_comment");
        payload.setCommandType(GithubCommandType.UNKNOWN.name());
        payload.setAction("created");
        payload.setCommentAuthorAssociation("COLLABORATOR");

        GithubCommandHandleResult result = router.route(payload);

        assertThat(result.getReason()).isEqualTo("unsupported command");
        verify(reviewHandler, never()).handle(payload);
        verify(chatHandler, never()).handle(payload);
    }

    @Test
    void shouldRouteNonIssueCommentCommandWithoutAuthorAssociation() {
        GithubCommandHandler reviewHandler = mock(GithubCommandHandler.class);
        when(reviewHandler.commandType()).thenReturn(GithubCommandType.REVIEW);
        when(reviewHandler.handle(any())).thenReturn(GithubCommandHandleResult.processed(12L, "opened"));

        GithubCommandRouter router = new GithubCommandRouter(List.of(reviewHandler), new GithubCommandProperties());

        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setEvent("pull_request");
        payload.setCommandType(GithubCommandType.REVIEW.name());
        payload.setAction("opened");

        GithubCommandHandleResult result = router.route(payload);

        assertThat(result.getId()).isEqualTo(12L);
        verify(reviewHandler).handle(payload);
    }

    @Test
    void shouldRouteChatCommandToChatHandler() {
        GithubCommandHandler chatHandler = mock(GithubCommandHandler.class);
        when(chatHandler.commandType()).thenReturn(GithubCommandType.CHAT);
        when(chatHandler.handle(any())).thenReturn(GithubCommandHandleResult.processed(12L, "created"));

        GithubCommandRouter router = new GithubCommandRouter(List.of(chatHandler), new GithubCommandProperties());

        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setEvent("issue_comment");
        payload.setCommandType(GithubCommandType.CHAT.name());
        payload.setAction("created");
        payload.setCommentAuthorAssociation("COLLABORATOR");

        GithubCommandHandleResult result = router.route(payload);

        assertThat(result.getId()).isEqualTo(12L);
        verify(chatHandler).handle(payload);
    }

    @Test
    void shouldRejectIssueCommentCommandFromUntrustedAuthorAssociation() {
        GithubCommandHandler chatHandler = mock(GithubCommandHandler.class);
        when(chatHandler.commandType()).thenReturn(GithubCommandType.CHAT);

        GithubCommandRouter router = new GithubCommandRouter(List.of(chatHandler), new GithubCommandProperties());

        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setEvent("issue_comment");
        payload.setCommandType(GithubCommandType.CHAT.name());
        payload.setAction("created");
        payload.setCommentAuthorAssociation("NONE");

        GithubCommandHandleResult result = router.route(payload);

        assertThat(result.getReason()).isEqualTo("comment author is not allowed to run commands");
        verify(chatHandler, never()).handle(payload);
    }

    @Test
    void shouldAllowCommandWhenAssociationAllowlistIsEmpty() {
        GithubCommandHandler chatHandler = mock(GithubCommandHandler.class);
        when(chatHandler.commandType()).thenReturn(GithubCommandType.CHAT);
        when(chatHandler.handle(any())).thenReturn(GithubCommandHandleResult.processed(12L, "created"));
        GithubCommandProperties properties = new GithubCommandProperties();
        properties.setAllowedCommentAuthorAssociations(List.of());

        GithubCommandRouter router = new GithubCommandRouter(List.of(chatHandler), properties);

        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setEvent("issue_comment");
        payload.setCommandType(GithubCommandType.CHAT.name());
        payload.setAction("created");
        payload.setCommentAuthorAssociation("NONE");

        GithubCommandHandleResult result = router.route(payload);

        assertThat(result.getId()).isEqualTo(12L);
        verify(chatHandler).handle(payload);
    }
}
