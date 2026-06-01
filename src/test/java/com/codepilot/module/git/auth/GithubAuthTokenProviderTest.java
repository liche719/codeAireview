package com.codepilot.module.git.auth;

import com.codepilot.module.git.config.GithubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GithubAuthTokenProviderTest {

    @Test
    void shouldUsePatTokenWhenGitHubAppIsNotConfigured() {
        GithubProperties properties = new GithubProperties();
        properties.setToken("pat-token");
        TestContext context = new TestContext(properties);

        assertThat(context.provider.resolveToken("liche719", "codeAireview")).contains("pat-token");
        HttpHeaders headers = new HttpHeaders();
        context.provider.setAuthorization(headers, "liche719", "codeAireview");
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer pat-token");
    }

    @Test
    void shouldResolveAndCacheInstallationTokenForGitHubApp() throws Exception {
        GithubProperties properties = githubAppProperties(false);
        TestContext context = new TestContext(properties);
        context.server.expect(once(), requestTo("https://api.github.test/repos/liche719/codeAireview/installation"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, startsWith("Bearer ")))
                .andRespond(withSuccess("{\"id\":456}", MediaType.APPLICATION_JSON));
        context.server.expect(once(), requestTo("https://api.github.test/app/installations/456/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, startsWith("Bearer ")))
                .andRespond(withSuccess(
                        """
                        {
                          "token": "installation-token",
                          "expires_at": "%s"
                        }
                        """.formatted(Instant.now().plusSeconds(600)),
                        MediaType.APPLICATION_JSON
                ));

        assertThat(context.provider.resolveToken("liche719", "codeAireview")).contains("installation-token");
        assertThat(context.provider.resolveToken("liche719", "codeAireview")).contains("installation-token");
        HttpHeaders headers = new HttpHeaders();
        context.provider.setAuthorization(headers, "liche719", "codeAireview");
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer installation-token");
        context.server.verify();
    }

    @Test
    void shouldResolveGitHubAppBotLogin() throws Exception {
        GithubProperties properties = githubAppProperties(false);
        TestContext context = new TestContext(properties);
        context.server.expect(once(), requestTo("https://api.github.test/app"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, startsWith("Bearer ")))
                .andRespond(withSuccess(
                        """
                        {
                          "slug": "codepilot-review"
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        assertThat(context.provider.resolveAuthenticatedLogin()).contains("codepilot-review[bot]");
        assertThat(context.provider.resolveAuthenticatedLogin()).contains("codepilot-review[bot]");
        context.server.verify();
    }

    @Test
    void shouldUseConfiguredInstallationIdWithoutRepoLookup() throws Exception {
        GithubProperties properties = githubAppProperties(true);
        TestContext context = new TestContext(properties);
        context.server.expect(once(), requestTo("https://api.github.test/app/installations/999/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, startsWith("Bearer ")))
                .andRespond(withSuccess(
                        """
                        {
                          "token": "installation-token",
                          "expires_at": "%s"
                        }
                        """.formatted(Instant.now().plusSeconds(600)),
                        MediaType.APPLICATION_JSON
                ));

        assertThat(context.provider.resolveToken("liche719", "codeAireview")).contains("installation-token");
        context.server.verify();
    }

    private GithubProperties githubAppProperties(boolean fixedInstallationId) throws Exception {
        GithubProperties properties = new GithubProperties();
        properties.setAuthMode(GithubProperties.AuthMode.APP);
        properties.setApiBaseUrl("https://api.github.test");
        properties.setAppId("12345");
        properties.setAppPrivateKeyBase64(privateKeyBase64());
        if (fixedInstallationId) {
            properties.setAppInstallationId(999L);
        }
        return properties;
    }

    private String privateKeyBase64() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    private static class TestContext {

        private final MockRestServiceServer server;

        private final GithubAuthTokenProvider provider;

        private TestContext(GithubProperties properties) {
            RestClient.Builder builder = RestClient.builder()
                    .baseUrl("https://api.github.test")
                    .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
            this.server = MockRestServiceServer.bindTo(builder).build();
            this.provider = new GithubAuthTokenProvider(properties, builder.build());
        }
    }
}
