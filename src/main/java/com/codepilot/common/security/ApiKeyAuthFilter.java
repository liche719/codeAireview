package com.codepilot.common.security;

import com.codepilot.common.response.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 30)
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String UNAUTHORIZED_MESSAGE = "invalid or missing API key";

    private final ApiAuthProperties properties;

    private final ObjectMapper objectMapper;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!requiresAuth(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String configuredApiKey = properties.getApiKey();
        if (!StringUtils.hasText(configuredApiKey)) {
            log.warn("Protected API request rejected because codepilot.api-auth.api-key is empty, method={}, uri={}",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorized(response);
            return;
        }

        String requestApiKey = request.getHeader(properties.getHeaderName());
        if (!constantTimeEquals(configuredApiKey, requestApiKey)) {
            log.warn("Protected API request rejected due to invalid API key, method={}, uri={}",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorized(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean requiresAuth(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return false;
        }
        String path = request.getRequestURI();
        if (matchesAny(properties.getExcludePathPatterns(), path)) {
            return false;
        }
        return matchesAny(properties.getProtectedPathPatterns(), path);
    }

    private boolean matchesAny(Iterable<String> patterns, String path) {
        if (patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (StringUtils.hasText(pattern) && pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.fail(
                HttpServletResponse.SC_UNAUTHORIZED,
                UNAUTHORIZED_MESSAGE
        ));
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
