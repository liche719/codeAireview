package com.codepilot.module.git.client;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.git.dto.GithubChangedFile;
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

    private static class TestContext {

        private final List<Long> retryDelays = new ArrayList<>();

        private final MockRestServiceServer server;

        private final GithubClient client;

        private TestContext() {
            RestClient.Builder builder = RestClient.builder()
                    .baseUrl("https://api.github.test")
                    .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
            this.server = MockRestServiceServer.bindTo(builder).build();
            this.client = new GithubClient("token", builder.build(), retryDelays::add);
        }
    }
}
