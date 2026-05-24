package com.codepilot.common.security;

import com.codepilot.common.response.Result;
import com.codepilot.common.security.FixedWindowRateLimiter.RateLimitDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
@RequiredArgsConstructor
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final String RATE_LIMITED_MESSAGE = "API rate limit exceeded";

    private final ApiRateLimitProperties properties;

    private final ApiAuthProperties apiAuthProperties;

    private final FixedWindowRateLimiter rateLimiter;

    private final ObjectMapper objectMapper;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!requiresRateLimit(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision = rateLimiter.tryConsume(
                clientKey(request),
                properties.getMaxRequestsPerWindow(),
                properties.getWindow()
        );
        writeRateLimitHeaders(response, decision);
        if (!decision.allowed()) {
            log.warn("API request rate limited, method={}, uri={}, remoteAddr={}, retryAfterSeconds={}",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr(), decision.retryAfterSeconds());
            writeTooManyRequests(response, decision);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean requiresRateLimit(HttpServletRequest request) {
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

    private String clientKey(HttpServletRequest request) {
        String apiKey = request.getHeader(apiAuthProperties.getHeaderName());
        if (StringUtils.hasText(apiKey)) {
            return "api-key:" + shortSha256(apiKey);
        }
        String remoteAddr = StringUtils.hasText(request.getRemoteAddr()) ? request.getRemoteAddr() : "unknown";
        return "ip:" + remoteAddr;
    }

    private String shortSha256(String value) {
        byte[] digest = sha256(value);
        return HexFormat.of().formatHex(digest, 0, 8);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void writeRateLimitHeaders(HttpServletResponse response, RateLimitDecision decision) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(decision.resetEpochSeconds()));
    }

    private void writeTooManyRequests(HttpServletResponse response, RateLimitDecision decision) throws IOException {
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.fail(429, RATE_LIMITED_MESSAGE));
    }
}
