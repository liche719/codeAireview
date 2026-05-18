package com.codepilot.module.github.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookPayloadParserTest {

    private final GitHubWebhookPayloadParser parser = new GitHubWebhookPayloadParser(new ObjectMapper());

    @Test
    void shouldParseOpenedPullRequestEvent() {
        GitHubPullRequestWebhookPayload payload = parser.parse("pull_request", pullRequestPayload("opened"));

        assertThat(payload.isIgnored()).isFalse();
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
}
