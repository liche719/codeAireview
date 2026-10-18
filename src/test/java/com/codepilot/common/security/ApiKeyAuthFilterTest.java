package com.codepilot.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRejectProtectedApiWhenApiKeyIsMissingFromConfig() throws ServletException, IOException {
        ApiAuthProperties properties = new ApiAuthProperties();
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/reviews/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("invalid or missing API key");
    }

    @Test
    void shouldRejectProtectedApiWhenHeaderApiKeyIsInvalid() throws ServletException, IOException {
        ApiAuthProperties properties = new ApiAuthProperties();
        properties.setApiKey("secret");
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/reviews");
        request.addHeader("X-CodePilot-Api-Key", "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void shouldAllowProtectedApiWhenHeaderApiKeyMatches() throws ServletException, IOException {
        ApiAuthProperties properties = new ApiAuthProperties();
        properties.setApiKey("secret");
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/reviews/1");
        request.addHeader("X-CodePilot-Api-Key", "secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldSkipGitHubWebhookBecauseItUsesGithubSignature() throws ServletException, IOException {
        ApiAuthProperties properties = new ApiAuthProperties();
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/github/webhook");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldAllowProtectedApiWhenAuthIsDisabled() throws ServletException, IOException {
        ApiAuthProperties properties = new ApiAuthProperties();
        properties.setEnabled(false);
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/reviews");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * /actuator/metrics 必须鉴权：它会列出全部内部指标名与数值，而服务在公网。
     *
     * <p>这条来自真机发现——部署后直接 `curl https://<host>:8990/actuator/metrics`
     * 拿到了完整指标列表，不需要任何凭据。</p>
     */
    @Test
    void shouldRequireApiKeyForActuatorMetrics() throws ServletException, IOException {
        ApiAuthProperties properties = new ApiAuthProperties();
        properties.setApiKey("secret");
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    /** 但 /actuator/health 要公开：探活和监控依赖它，且它只返回 {"status":"UP"}。 */
    @Test
    void shouldKeepActuatorHealthPublic() throws ServletException, IOException {
        ApiAuthProperties properties = new ApiAuthProperties();
        properties.setApiKey("secret");
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(properties, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
