package com.codepilot.module.github.webhook;

import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.common.exception.BusinessException;
import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommandHandleResult;
import com.codepilot.module.command.parser.GithubCommandParser;
import com.codepilot.module.command.router.GithubCommandRouter;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.service.ReviewTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubWebhookServiceTest {

    @Test
    void shouldCreateReviewTaskForOpenedPullRequest() {
        TestContext context = new TestContext(true, true);
        when(context.reviewTaskService.createTask(
                "https://github.com/liche719/codeAireview/pull/12",
                "Add webhook support",
                ReviewCommentMode.INLINE_ONLY,
                "abc123"
        )).thenReturn(new ReviewCreateResponse(123L, "PENDING"));

        GitHubWebhookResponse response = context.service.handle(
                "pull_request",
                "delivery-1",
                "sha256=valid",
                pullRequestPayload("opened")
        );

        assertThat(response.getTaskId()).isEqualTo(123L);
        assertThat(response.getAction()).isEqualTo("opened");
        assertThat(response.isIgnored()).isFalse();
        verify(context.reviewTaskService).createTask(
                "https://github.com/liche719/codeAireview/pull/12",
                "Add webhook support",
                ReviewCommentMode.INLINE_ONLY,
                "abc123"
        );
        verify(context.valueOperations).setIfAbsent(
                eq("codepilot:webhook:pr-head:liche719:codeaireview:12:abc123"),
                eq("1"),
                any(Duration.class)
        );
    }

    @Test
    void shouldCreateSummaryModeTaskForSynchronizedPullRequest() {
        TestContext context = new TestContext(true, true);
        when(context.reviewTaskService.createTask(
                "https://github.com/liche719/codeAireview/pull/12",
                "Add webhook support",
                ReviewCommentMode.SUMMARY_ONLY,
                "abc123"
        )).thenReturn(new ReviewCreateResponse(124L, "PENDING"));

        GitHubWebhookResponse response = context.service.handle(
                "pull_request",
                "delivery-1",
                "sha256=valid",
                pullRequestPayload("synchronize")
        );

        assertThat(response.getTaskId()).isEqualTo(124L);
        assertThat(response.getAction()).isEqualTo("synchronize");
        assertThat(response.isIgnored()).isFalse();
        verify(context.reviewTaskService).createTask(
                "https://github.com/liche719/codeAireview/pull/12",
                "Add webhook support",
                ReviewCommentMode.SUMMARY_ONLY,
                "abc123"
        );
        verify(context.valueOperations).setIfAbsent(
                eq("codepilot:webhook:pr-head:liche719:codeaireview:12:abc123"),
                eq("1"),
                any(Duration.class)
        );
    }

    @Test
    void shouldIgnoreUnsupportedEventWithoutCreatingTask() {
        TestContext context = new TestContext(true, true);

        GitHubWebhookResponse response = context.service.handle("push", "delivery-2", "sha256=valid", "not-json");

        assertThat(response.isIgnored()).isTrue();
        assertThat(response.getReason()).isEqualTo("unsupported event");
        verify(context.reviewTaskService, never()).createTask(anyString(), any());
    }

    @Test
    void shouldRejectInvalidSignatureWithoutCreatingTask() {
        TestContext context = new TestContext(true, false);

        assertThatThrownBy(() -> context.service.handle(
                "pull_request",
                "delivery-3",
                "sha256=invalid",
                pullRequestPayload("opened")
        )).isInstanceOf(BusinessException.class)
                .hasMessage("invalid GitHub webhook signature");
        verify(context.reviewTaskService, never()).createTask(anyString(), any());
    }

    @Test
    void shouldSkipDuplicateWebhookEvent() {
        TestContext context = new TestContext(false, true);

        GitHubWebhookResponse response = context.service.handle(
                "pull_request",
                "delivery-4",
                "sha256=valid",
                pullRequestPayload("opened")
        );

        assertThat(response.isIgnored()).isTrue();
        assertThat(response.getReason()).isEqualTo("duplicate event");
        verify(context.reviewTaskService, never()).createTask(anyString(), any());
    }

    @Test
    void shouldCreateReviewTaskForReviewCommandIssueComment() {
        TestContext context = new TestContext(true, true);
        when(context.githubCommandRouter.route(any())).thenReturn(GithubCommandHandleResult.processed(456L, "created"));

        GitHubWebhookResponse response = context.service.handle(
                "issue_comment",
                "delivery-5",
                "sha256=valid",
                issueCommentPayload()
        );

        assertThat(response.getTaskId()).isEqualTo(456L);
        assertThat(response.getAction()).isEqualTo("created");
        assertThat(response.isIgnored()).isFalse();
        verify(context.githubCommandRouter).route(any());
        verify(context.valueOperations).setIfAbsent(
                eq("codepilot:webhook:pr-command:liche719:codeaireview:12:1001"),
                eq("1"),
                any(Duration.class)
        );
    }

    @Test
    void shouldIgnoreIssueCommentAuthoredByBot() {
        TestContext context = new TestContext(true, true);

        GitHubWebhookResponse response = context.service.handle(
                "issue_comment",
                "delivery-6",
                "sha256=valid",
                issueCommentPayload("created", "@x-pilotx 帮我解决上述问题", true, "X-PilotX")
        );

        assertThat(response.isIgnored()).isTrue();
        assertThat(response.getReason()).isEqualTo("bot comment");
        verify(context.githubCommandRouter, never()).route(any());
        verify(context.reviewTaskService, never()).createTask(anyString(), any());
        verify(context.valueOperations, never()).setIfAbsent(anyString(), anyString(), any());
    }

    private String pullRequestPayload(String action) {
        return """
                {
                  "action": "%s",
                  "repository": {
                    "name": "codeAireview",
                    "owner": {
                      "login": "liche719"
                    }
                  },
                  "pull_request": {
                    "number": 12,
                    "html_url": "https://github.com/liche719/codeAireview/pull/12",
                    "title": "Add webhook support",
                    "head": {
                      "sha": "abc123"
                    }
                  }
                }
                """.formatted(action);
    }

    private String issueCommentPayload() {
        return issueCommentPayload("created", "/review", true, "reviewer");
    }

    private String issueCommentPayload(String action, String body, boolean pullRequestComment) {
        return issueCommentPayload(action, body, pullRequestComment, "reviewer");
    }

    private String issueCommentPayload(String action, String body, boolean pullRequestComment, String commentUserLogin) {
        return """
                {
                  "action": "%s",
                  "repository": {
                    "name": "codeAireview",
                    "owner": {
                      "login": "liche719"
                    }
                  },
                  "issue": {
                    "number": 12,
                    "html_url": "https://github.com/liche719/codeAireview/pull/12",
                    "title": "Add webhook support",
                    "pull_request": {
                      "url": "https://api.github.com/repos/liche719/codeAireview/pulls/12"
                    }
                  },
                  "comment": {
                    "id": 1001,
                    "body": "%s",
                    "author_association": "COLLABORATOR",
                    "user": {
                      "login": "%s"
                    }
                  }
                }
                """.formatted(action, body, commentUserLogin);
    }

    private static class TestContext {

        private final GitHubWebhookSignatureVerifier signatureVerifier = mock(GitHubWebhookSignatureVerifier.class);

        private final ReviewTaskService reviewTaskService = mock(ReviewTaskService.class);

        private final GithubCommandRouter githubCommandRouter = mock(GithubCommandRouter.class);

        private final GithubClient githubClient = mock(GithubClient.class);

        @SuppressWarnings("unchecked")
        private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

        private final GitHubWebhookService service;

        private TestContext(boolean redisLockAcquired, boolean signatureValid) {
            StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(redisLockAcquired);
            when(signatureVerifier.verify(anyString(), any())).thenReturn(signatureValid);
            when(githubClient.getAuthenticatedUserLogin()).thenReturn("X-PilotX");

            service = new GitHubWebhookService(
                    signatureVerifier,
                    new GitHubWebhookPayloadParser(
                            new ObjectMapper(),
                            new GithubCommandParser(new GithubCommandProperties(), null, null, enabledLlmProperties())
                    ),
                    reviewTaskService,
                    githubCommandRouter,
                    githubClient,
                    stringRedisTemplate,
                    true
            );
        }

        private LlmProperties enabledLlmProperties() {
            LlmProperties properties = new LlmProperties();
            properties.setEnabled(true);
            properties.setApiKey("test-key");
            return properties;
        }
    }
}
