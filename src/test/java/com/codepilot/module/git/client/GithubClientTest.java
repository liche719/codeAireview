package com.codepilot.module.git.client;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.git.config.GithubProperties;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.git.dto.GithubLinkedIssue;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GithubClientTest {

    @Test
    void shouldRetryTooManyRequestsOnceAndReturnFiles() {
        TestContext context = new TestContext();
        String filesUrl = "https://api.github.test/repos/liche719/codeAireview/pulls/123/files?per_page=100&page=1";
        context.server.expect(once(), requestTo(filesUrl))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"secondary rate limit\"}"));
        context.server.expect(once(), requestTo(filesUrl))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        [
                          {
                            "filename": "src/main/java/Demo.java",
                            "status": "modified",
                            "additions": 2,
                            "deletions": 1,
                            "changes": 3
                          }
                        ]
                        """,
                        MediaType.APPLICATION_JSON
                ));

        List<GithubChangedFile> files = context.client.listPullRequestFiles("liche719", "codeAireview", 123);

        assertThat(files).hasSize(1);
        assertThat(files.getFirst().getFilename()).isEqualTo("src/main/java/Demo.java");
        assertThat(context.retryDelays).containsExactly(1000L);
        context.server.verify();
    }

    @Test
    void shouldUseExponentialBackoffForRepeatedSecondaryRateLimitResponses() {
        GithubProperties properties = new GithubProperties();
        properties.setToken("token");
        properties.setRateLimitMaxAttempts(4);
        properties.setRateLimitInitialDelayMillis(250L);
        properties.setRateLimitBackoffMultiplier(2.0D);
        properties.setRateLimitMaxDelayMillis(1000L);
        TestContext context = new TestContext(properties);
        String filesUrl = "https://api.github.test/repos/liche719/codeAireview/pulls/123/files?per_page=100&page=1";
        context.server.expect(once(), requestTo(filesUrl))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"secondary rate limit\"}"));
        context.server.expect(once(), requestTo(filesUrl))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"secondary rate limit\"}"));
        context.server.expect(once(), requestTo(filesUrl))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<GithubChangedFile> files = context.client.listPullRequestFiles("liche719", "codeAireview", 123);

        assertThat(files).isEmpty();
        assertThat(context.retryDelays).containsExactly(250L, 500L);
        context.server.verify();
    }

    @Test
    void shouldExposeClearRateLimitErrorForForbiddenPrimaryRateLimit() {
        TestContext context = new TestContext();
        context.server.expect(once(), requestTo("https://api.github.test/repos/liche719/codeAireview/pulls/123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header("X-RateLimit-Remaining", "0")
                        .header("X-RateLimit-Reset", "1893456000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"API rate limit exceeded for user ID 1.\"}"));

        assertThatThrownBy(() -> context.client.getPullRequestDetail("liche719", "codeAireview", 123))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("failed to get GitHub PR detail: GitHub API rate limit exceeded")
                .hasMessageContaining("status=403")
                .hasMessageContaining("X-RateLimit-Remaining=0")
                .hasMessageContaining("X-RateLimit-Reset=1893456000")
                .hasMessageContaining("API rate limit exceeded");
        assertThat(context.retryDelays).isEmpty();
        context.server.verify();
    }

    @Test
    void shouldRedactSecretsFromRateLimitResponseSummary() {
        TestContext context = new TestContext();
        context.server.expect(once(), requestTo("https://api.github.test/repos/liche719/codeAireview/pulls/123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header("X-RateLimit-Remaining", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"API rate limit exceeded token=ghp_123456789012345678901234567890123456\"}"));

        assertThatThrownBy(() -> context.client.getPullRequestDetail("liche719", "codeAireview", 123))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GitHub API rate limit exceeded")
                .hasMessageContaining("[REDACTED]")
                .hasMessageNotContaining("ghp_123456789012345678901234567890123456");
        context.server.verify();
    }

    @Test
    void shouldNotRetryForbiddenWhenItIsNotRateLimit() {
        TestContext context = new TestContext();
        context.server.expect(once(), requestTo("https://api.github.test/repos/liche719/codeAireview/pulls/123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Resource not accessible by integration\"}"));

        assertThatThrownBy(() -> context.client.getPullRequestDetail("liche719", "codeAireview", 123))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("failed to get GitHub PR detail")
                .hasMessageNotContaining("GitHub API rate limit exceeded");
        assertThat(context.retryDelays).isEmpty();
        context.server.verify();
    }

    @Test
    void shouldRedactSecretsFromGitHubApiFailureMessage() {
        TestContext context = new TestContext();
        context.server.expect(once(), requestTo("https://api.github.test/repos/liche719/codeAireview/pulls/123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"token=ghp_123456789012345678901234567890123456 rejected\"}"));

        assertThatThrownBy(() -> context.client.getPullRequestDetail("liche719", "codeAireview", 123))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("failed to get GitHub PR detail")
                .hasMessageContaining("[REDACTED]")
                .hasMessageNotContaining("ghp_123456789012345678901234567890123456");
        context.server.verify();
    }

    @Test
    void shouldListPullRequestReviewComments() {
        TestContext context = new TestContext();
        context.server.expect(once(), requestTo("https://api.github.test/repos/liche719/codeAireview/pulls/123/comments?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andRespond(withSuccess(
                        """
                        [
                          {
                            "id": 1001,
                            "body": "<!-- codepilot-inline-review:abc123 -->",
                            "user": {
                              "login": "x-pilotx"
                            },
                            "created_at": "2026-01-01T00:00:00Z",
                            "updated_at": "2026-01-01T00:00:00Z"
                          }
                        ]
                        """,
                        MediaType.APPLICATION_JSON
                ));

        List<GithubIssueComment> comments = context.client.listPullRequestReviewComments("liche719", "codeAireview", 123);

        assertThat(comments).hasSize(1);
        assertThat(comments.getFirst().getId()).isEqualTo(1001L);
        assertThat(comments.getFirst().getBody()).contains("codepilot-inline-review");
        assertThat(comments.getFirst().getUserLogin()).isEqualTo("x-pilotx");
        context.server.verify();
    }

    @Test
    void shouldListPullRequestLinkedIssuesFromGraphqlClosingReferences() {
        TestContext context = new TestContext();
        context.server.expect(once(), requestTo("https://api.github.test/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andRespond(withSuccess(
                        """
                        {
                          "data": {
                            "repository": {
                              "pullRequest": {
                                "closingIssuesReferences": {
                                  "nodes": [
                                    {
                                      "number": 42,
                                      "title": "Fix SQL injection",
                                      "state": "OPEN",
                                      "url": "https://github.com/liche719/codeAireview/issues/42",
                                      "repository": {
                                        "name": "codeAireview",
                                        "owner": {
                                          "login": "liche719"
                                        }
                                      }
                                    }
                                  ]
                                }
                              }
                            }
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        List<GithubLinkedIssue> issues = context.client.listPullRequestLinkedIssues("liche719", "codeAireview", 123);

        assertThat(issues)
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.getRepositoryOwner()).isEqualTo("liche719");
                    assertThat(issue.getRepositoryName()).isEqualTo("codeAireview");
                    assertThat(issue.getNumber()).isEqualTo(42);
                    assertThat(issue.getTitle()).isEqualTo("Fix SQL injection");
                    assertThat(issue.getState()).isEqualTo("OPEN");
                    assertThat(issue.getHtmlUrl()).isEqualTo("https://github.com/liche719/codeAireview/issues/42");
                    assertThat(issue.getLinkSource()).isEqualTo("GRAPHQL_CLOSING_ISSUES");
                });
        context.server.verify();
    }

    @Test
    void shouldFallbackToPullRequestBodyClosingKeywordsWhenGraphqlUnavailable() {
        TestContext context = new TestContext();
        context.server.expect(once(), requestTo("https://api.github.test/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Resource not accessible by integration\"}"));
        context.server.expect(once(), requestTo("https://api.github.test/repos/liche719/codeAireview/pulls/123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "number": 123,
                          "title": "Patch SQL issue",
                          "body": "Fixes #42 and resolves liche719/codeAireview#43"
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));
        context.server.expect(once(), requestTo("https://api.github.test/repos/liche719/codeAireview/issues/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "number": 42,
                          "title": "Fix SQL injection",
                          "state": "open",
                          "html_url": "https://github.com/liche719/codeAireview/issues/42"
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));
        context.server.expect(once(), requestTo("https://api.github.test/repos/liche719/codeAireview/issues/43"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "number": 43,
                          "title": "Add regression test",
                          "state": "closed",
                          "html_url": "https://github.com/liche719/codeAireview/issues/43"
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        List<GithubLinkedIssue> issues = context.client.listPullRequestLinkedIssues("liche719", "codeAireview", 123);

        assertThat(issues).hasSize(2);
        assertThat(issues)
                .extracting(GithubLinkedIssue::getNumber)
                .containsExactly(42, 43);
        assertThat(issues)
                .extracting(GithubLinkedIssue::getLinkSource)
                .containsOnly("PR_BODY_CLOSING_KEYWORD");
        context.server.verify();
    }

    @Test
    void shouldReturnEmptyLinkedIssuesWhenPullRequestDetailUnavailableAfterGraphqlFailure() {
        TestContext context = new TestContext();
        context.server.expect(once(), requestTo("https://api.github.test/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Resource not accessible by integration\"}"));
        context.server.expect(once(), requestTo("https://api.github.test/repos/liche719/codeAireview/pulls/123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Resource not accessible by integration\"}"));

        List<GithubLinkedIssue> issues = context.client.listPullRequestLinkedIssues("liche719", "codeAireview", 123);

        assertThat(issues).isEmpty();
        context.server.verify();
    }

    private static class TestContext {

        private final List<Long> retryDelays = new ArrayList<>();

        private final MockRestServiceServer server;

        private final GithubClient client;

        private TestContext() {
            this(new GithubProperties());
        }

        private TestContext(GithubProperties properties) {
            properties.setApiBaseUrl("https://api.github.test");
            if (!org.springframework.util.StringUtils.hasText(properties.getToken())) {
                properties.setToken("token");
            }
            RestClient.Builder builder = RestClient.builder()
                    .baseUrl("https://api.github.test")
                    .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
            this.server = MockRestServiceServer.bindTo(builder).build();
            this.client = new GithubClient(properties, builder.build(), retryDelays::add);
        }
    }
}
