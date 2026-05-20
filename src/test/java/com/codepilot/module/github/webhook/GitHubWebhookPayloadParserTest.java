package com.codepilot.module.github.webhook;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommandType;
import com.codepilot.module.command.parser.GithubCommandIntentAiAssistant;
import com.codepilot.module.command.parser.GithubCommandIntentResultParser;
import com.codepilot.module.command.parser.GithubCommandParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitHubWebhookPayloadParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final GitHubWebhookPayloadParser parser = parserWithoutClassifierButLlmAvailable();

    @Test
    void shouldParseOpenedPullRequestEvent() {
        GitHubPullRequestWebhookPayload payload = parser.parse("pull_request", pullRequestPayload("opened"));

        assertThat(payload.isIgnored()).isFalse();
        assertThat(payload.getEvent()).isEqualTo("pull_request");
        assertThat(payload.getAction()).isEqualTo("opened");
        assertThat(payload.getOwner()).isEqualTo("liche719");
        assertThat(payload.getRepo()).isEqualTo("codeAireview");
        assertThat(payload.getPullNumber()).isEqualTo(12);
        assertThat(payload.getPrUrl()).isEqualTo("https://github.com/liche719/codeAireview/pull/12");
        assertThat(payload.getTitle()).isEqualTo("Add webhook support");
        assertThat(payload.getHeadSha()).isEqualTo("abc123");
    }

    @Test
    void shouldParseSynchronizePullRequestEvent() {
        GitHubPullRequestWebhookPayload payload = parser.parse("pull_request", pullRequestPayload("synchronize"));

        assertThat(payload.isIgnored()).isFalse();
        assertThat(payload.getAction()).isEqualTo("synchronize");
    }

    @Test
    void shouldIgnoreUnsupportedAction() {
        GitHubPullRequestWebhookPayload payload = parser.parse("pull_request", pullRequestPayload("closed"));

        assertThat(payload.isIgnored()).isTrue();
        assertThat(payload.getAction()).isEqualTo("closed");
        assertThat(payload.getReason()).isEqualTo("unsupported action");
    }

    @Test
    void shouldIgnoreNonPullRequestEvent() {
        GitHubPullRequestWebhookPayload payload = parser.parse("push", "not-json");

        assertThat(payload.isIgnored()).isTrue();
        assertThat(payload.getReason()).isEqualTo("unsupported event");
    }

    @Test
    void shouldParseReviewCommandIssueCommentEvent() {
        GitHubPullRequestWebhookPayload payload = parser.parse("issue_comment", issueCommentPayload("created", "/review", true));

        assertThat(payload.isIgnored()).isFalse();
        assertThat(payload.getEvent()).isEqualTo("issue_comment");
        assertThat(payload.getAction()).isEqualTo("created");
        assertThat(payload.getOwner()).isEqualTo("liche719");
        assertThat(payload.getRepo()).isEqualTo("codeAireview");
        assertThat(payload.getPullNumber()).isEqualTo(12);
        assertThat(payload.getPrUrl()).isEqualTo("https://github.com/liche719/codeAireview/pull/12");
        assertThat(payload.getTitle()).isEqualTo("Add webhook support");
        assertThat(payload.getCommentId()).isEqualTo(1001L);
        assertThat(payload.getCommentBody()).isEqualTo("/review");
        assertThat(payload.getCommentUserLogin()).isEqualTo("reviewer");
        assertThat(payload.getCommandType()).isEqualTo(GithubCommandType.REVIEW.name());
        assertThat(payload.getMentionedBot()).isFalse();
    }

    @Test
    void shouldParseMentionReviewCommandIssueCommentEvent() {
        GitHubWebhookPayloadParser aiParser = parserWithAiResponse("""
                {
                  "type": "REVIEW",
                  "dryRun": false,
                  "reason": "asks for review"
                }
                """);

        GitHubPullRequestWebhookPayload payload = aiParser.parse("issue_comment", issueCommentPayload("created", "@x-pilotx please review this PR", true));

        assertThat(payload.isIgnored()).isFalse();
        assertThat(payload.getCommandType()).isEqualTo(GithubCommandType.REVIEW.name());
        assertThat(payload.getMentionedBot()).isTrue();
    }

    @Test
    void shouldParseMentionFixDryRunCommandIssueCommentEvent() {
        GitHubWebhookPayloadParser aiParser = parserWithAiResponse("""
                {
                  "type": "FIX",
                  "dryRun": true,
                  "reason": "asks for a dry-run fix"
                }
                """);

        GitHubPullRequestWebhookPayload payload = aiParser.parse("issue_comment", issueCommentPayload("created", "@x-pilotx fix dry-run", true));

        assertThat(payload.isIgnored()).isFalse();
        assertThat(payload.getCommandType()).isEqualTo(GithubCommandType.FIX.name());
        assertThat(payload.getDryRun()).isTrue();
    }

    @Test
    void shouldParseMentionChatCommandIssueCommentEvent() {
        GitHubWebhookPayloadParser aiParser = parserWithAiResponse("""
                {
                  "type": "CHAT",
                  "dryRun": false,
                  "reason": "asks for a PR summary"
                }
                """);

        GitHubPullRequestWebhookPayload payload = aiParser.parse("issue_comment", issueCommentPayload("created", "@x-pilotx \u603b\u7ed3\u4e00\u4e0b\u8fd9\u4e2apr\u4e3b\u8981\u505a\u4e86\u4ec0\u4e48", true));

        assertThat(payload.isIgnored()).isFalse();
        assertThat(payload.getCommandType()).isEqualTo(GithubCommandType.CHAT.name());
        assertThat(payload.getMentionedBot()).isTrue();
    }

    @Test
    void shouldParseUnknownMentionAsCommand() {
        GitHubWebhookPayloadParser aiParser = parserWithAiResponse("""
                {
                  "type": "UNKNOWN",
                  "dryRun": false,
                  "reason": "not an actionable command"
                }
                """);

        GitHubPullRequestWebhookPayload payload = aiParser.parse("issue_comment", issueCommentPayload("created", "@x-pilotx hello", true));

        assertThat(payload.isIgnored()).isFalse();
        assertThat(payload.getCommandType()).isEqualTo(GithubCommandType.UNKNOWN.name());
        assertThat(payload.getMentionedBot()).isTrue();
    }

    @Test
    void shouldParseMentionCommandAsUnavailableWhenLlmIsUnavailable() {
        GitHubPullRequestWebhookPayload payload = parserWithLlmUnavailable()
                .parse("issue_comment", issueCommentPayload("created", "@x-pilotx please review this PR", true));

        assertThat(payload.isIgnored()).isFalse();
        assertThat(payload.getCommandType()).isEqualTo(GithubCommandType.UNAVAILABLE.name());
        assertThat(payload.getMentionedBot()).isTrue();
    }

    @Test
    void shouldIgnoreIssueCommentWhenBodyIsNotReviewCommand() {
        GitHubPullRequestWebhookPayload payload = parser.parse("issue_comment", issueCommentPayload("created", "please review", true));

        assertThat(payload.isIgnored()).isTrue();
        assertThat(payload.getEvent()).isEqualTo("issue_comment");
        assertThat(payload.getReason()).isEqualTo("unsupported comment command");
    }

    @Test
    void shouldIgnoreIssueCommentWhenItIsNotPullRequest() {
        GitHubPullRequestWebhookPayload payload = parser.parse("issue_comment", issueCommentPayload("created", "/review", false));

        assertThat(payload.isIgnored()).isTrue();
        assertThat(payload.getEvent()).isEqualTo("issue_comment");
        assertThat(payload.getReason()).isEqualTo("not pull request comment");
    }

    @Test
    void shouldIgnoreEditedIssueComment() {
        GitHubPullRequestWebhookPayload payload = parser.parse("issue_comment", issueCommentPayload("edited", "/review", true));

        assertThat(payload.isIgnored()).isTrue();
        assertThat(payload.getEvent()).isEqualTo("issue_comment");
        assertThat(payload.getAction()).isEqualTo("edited");
        assertThat(payload.getReason()).isEqualTo("unsupported action");
    }

    @Test
    void shouldIgnoreBotGeneratedIssueComment() {
        GitHubPullRequestWebhookPayload payload = parser.parse(
                "issue_comment",
                issueCommentPayload(
                        "created",
                        "<!-- codepilot-ai-review:liche719/codeAireview --> @x-pilotx review",
                        true
                )
        );

        assertThat(payload.isIgnored()).isTrue();
        assertThat(payload.getReason()).isEqualTo("bot response");
    }

    private GitHubWebhookPayloadParser parserWithAiResponse(String response) {
        GithubCommandIntentAiAssistant assistant = mock(GithubCommandIntentAiAssistant.class);
        when(assistant.classify(anyString(), anyString(), anyString())).thenReturn(response);

        @SuppressWarnings("unchecked")
        ObjectProvider<GithubCommandIntentAiAssistant> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(assistant);

        GithubCommandParser commandParser = new GithubCommandParser(
                new GithubCommandProperties(),
                provider,
                new GithubCommandIntentResultParser(objectMapper),
                enabledLlmProperties()
        );
        return new GitHubWebhookPayloadParser(objectMapper, commandParser);
    }

    private GitHubWebhookPayloadParser parserWithoutClassifierButLlmAvailable() {
        GithubCommandParser commandParser = new GithubCommandParser(
                new GithubCommandProperties(),
                null,
                null,
                enabledLlmProperties()
        );
        return new GitHubWebhookPayloadParser(objectMapper, commandParser);
    }

    private GitHubWebhookPayloadParser parserWithLlmUnavailable() {
        GithubCommandParser commandParser = new GithubCommandParser(
                new GithubCommandProperties(),
                null,
                null,
                null
        );
        return new GitHubWebhookPayloadParser(objectMapper, commandParser);
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

    private String issueCommentPayload(String action, String body, boolean pullRequestComment) {
        String pullRequestNode = pullRequestComment
                ? """
                    "pull_request": {
                      "url": "https://api.github.com/repos/liche719/codeAireview/pulls/12"
                    },
                """
                : "";
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
                    %s
                    "user": {
                      "login": "author"
                    }
                  },
                  "comment": {
                    "id": 1001,
                    "body": "%s",
                    "user": {
                      "login": "reviewer"
                    }
                  }
                }
                """.formatted(action, pullRequestNode, body);
    }

    private LlmProperties enabledLlmProperties() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        return properties;
    }
}
