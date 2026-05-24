package com.codepilot.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRateLimitFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRejectProtectedApiAfterWindowLimitIsExceeded() throws ServletException, IOException {
        ApiRateLimitProperties properties = new ApiRateLimitProperties();
        properties.setMaxRequestsPerWindow(1);
        properties.setWindow(Duration.ofMinutes(1));
        ApiAuthProperties authProperties = new ApiAuthProperties();
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                properties,
                authProperties,
                new FixedWindowRateLimiter(),
                objectMapper
        );

        MockHttpServletRequest firstRequest = request("POST", "/api/reviews", "secret");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(firstRequest, firstResponse, new MockFilterChain());

        MockHttpServletRequest secondRequest = request("POST", "/api/reviews", "secret");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(secondRequest, secondResponse, new MockFilterChain());

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getHeader("Retry-After")).isNotBlank();
        assertThat(secondResponse.getContentAsString()).contains("API rate limit exceeded");
    }

    @Test
    void shouldTrackDifferentApiKeysSeparately() throws ServletException, IOException {
        ApiRateLimitProperties properties = new ApiRateLimitProperties();
        properties.setMaxRequestsPerWindow(1);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                properties,
                new ApiAuthProperties(),
                new FixedWindowRateLimiter(),
                objectMapper
        );

        filter.doFilter(request("GET", "/api/reviews/1", "left"), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/reviews/1", "right"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldSkipExcludedPath() throws ServletException, IOException {
        ApiRateLimitProperties properties = new ApiRateLimitProperties();
        properties.setMaxRequestsPerWindow(1);
        properties.setExcludePathPatterns(java.util.List.of("/api/github/webhook"));
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                properties,
                new ApiAuthProperties(),
                new FixedWindowRateLimiter(),
                objectMapper
        );

        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/github/webhook", null), firstResponse, new MockFilterChain());
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/github/webhook", null), secondResponse, new MockFilterChain());

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldAllowWhenRateLimitIsDisabled() throws ServletException, IOException {
        ApiRateLimitProperties properties = new ApiRateLimitProperties();
        properties.setEnabled(false);
        properties.setMaxRequestsPerWindow(1);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                properties,
                new ApiAuthProperties(),
                new FixedWindowRateLimiter(),
                objectMapper
        );

        filter.doFilter(request("GET", "/api/reviews/1", "secret"), new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/api/reviews/1", "secret"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request(String method, String path, String apiKey) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        if (apiKey != null) {
            request.addHeader("X-CodePilot-Api-Key", apiKey);
        }
        return request;
    }
}
