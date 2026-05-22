package com.codepilot.module.command.router;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.command.handler.GithubCommandHandler;
import com.codepilot.module.git.config.GithubRepositoryProperties;
import com.codepilot.module.git.policy.GithubRepositoryPolicy;
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

        GithubCommandRouter router = newRouter(List.of(reviewHandler, chatHandler));

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

        GithubCommandRouter router = newRouter(List.of(reviewHandler));

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

        GithubCommandRouter router = newRouter(List.of(chatHandler));

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

        GithubCommandRouter router = newRouter(List.of(chatHandler));

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
    void shouldRejectIssueCommentCommandWhenAssociationAllowlistIsEmpty() {
        GithubCommandHandler chatHandler = mock(GithubCommandHandler.class);
        when(chatHandler.commandType()).thenReturn(GithubCommandType.CHAT);
        GithubCommandProperties properties = new GithubCommandProperties();
        properties.setAllowedCommentAuthorAssociations(List.of());

        GithubCommandRouter router = newRouter(List.of(chatHandler), properties);

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
    void shouldRejectIssueCommentCommandWhenRepositoryIsNotAllowed() {
        GithubCommandHandler chatHandler = mock(GithubCommandHandler.class);
        when(chatHandler.commandType()).thenReturn(GithubCommandType.CHAT);
        GithubRepositoryProperties repositoryProperties = new GithubRepositoryProperties();
        repositoryProperties.setAllowedRepositories(List.of("liche719/codeAireview"));

        GithubCommandRouter router = new GithubCommandRouter(
                List.of(chatHandler),
                new GithubCommandProperties(),
                new GithubRepositoryPolicy(repositoryProperties)
        );

        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setEvent("issue_comment");
        payload.setAction("created");
        payload.setOwner("evil");
        payload.setRepo("repo");
        payload.setCommandType(GithubCommandType.CHAT.name());
        payload.setCommentAuthorAssociation("COLLABORATOR");

        GithubCommandHandleResult result = router.route(payload);

        assertThat(result.getReason()).isEqualTo("repository is not allowed");
        verify(chatHandler, never()).handle(payload);
    }

    private GithubCommandRouter newRouter(List<GithubCommandHandler> handlers) {
        return newRouter(handlers, new GithubCommandProperties());
    }

    private GithubCommandRouter newRouter(List<GithubCommandHandler> handlers, GithubCommandProperties properties) {
        return new GithubCommandRouter(
                handlers,
                properties,
                new GithubRepositoryPolicy(new GithubRepositoryProperties())
        );
    }
}
