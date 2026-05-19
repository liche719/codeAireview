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
}
