package com.codepilot.module.git.client;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.config.GithubProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.function.LongConsumer;

@Slf4j
class GithubRequestExecutor {

    static final int MAX_RESPONSE_BODY_SUMMARY_LENGTH = 240;

    private final GithubProperties githubProperties;

    private final LongConsumer rateLimitSleeper;

    GithubRequestExecutor(GithubProperties githubProperties, LongConsumer rateLimitSleeper) {
        this.githubProperties = githubProperties == null ? new GithubProperties() : githubProperties;
        this.rateLimitSleeper = rateLimitSleeper == null ? delayMillis -> { } : rateLimitSleeper;
    }

    <T> T execute(String operation, GithubRequestSupplier<T> supplier) {
        int maxAttempts = Math.max(1, githubProperties.getRateLimitMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return supplier.get();
            } catch (RestClientResponseException exception) {
                if (!isGithubRateLimit(exception)) {
                    throw new BusinessException(operation + ": " + SensitiveDataSanitizer.redact(exception.getMessage()));
                }
                if (!shouldRetryRateLimit(exception, attempt)) {
                    throw buildRateLimitException(operation, exception);
                }
                long delayMillis = resolveRetryDelayMillis(exception, attempt);
                log.warn("GitHub API rate limit hit, retrying request, operation={}, attempt={}, delayMillis={}, status={}, message={}",
                        operation,
                        attempt,
                        delayMillis,
                        exception.getStatusCode().value(),
                        summarizeResponseBody(exception));
                sleepBeforeRetry(operation, delayMillis);
            } catch (RestClientException exception) {
                throw new BusinessException(operation + ": " + SensitiveDataSanitizer.redact(exception.getMessage()));
            }
        }
        throw new BusinessException(operation + ": GitHub API request failed");
    }

    private boolean isGithubRateLimit(RestClientResponseException exception) {
        int statusCode = exception.getStatusCode().value();
        if (statusCode == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return true;
        }
        if (statusCode != HttpStatus.FORBIDDEN.value()) {
            return false;
        }

        String remaining = firstHeader(exception.getResponseHeaders(), "X-RateLimit-Remaining");
        if ("0".equals(remaining)) {
            return true;
        }

        String responseBody = exception.getResponseBodyAsString();
        String normalizedBody = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
        return normalizedBody.contains("rate limit")
                || normalizedBody.contains("secondary rate limit")
                || normalizedBody.contains("abuse detection");
    }

    private boolean shouldRetryRateLimit(RestClientResponseException exception, int attempt) {
        if (attempt >= Math.max(1, githubProperties.getRateLimitMaxAttempts())) {
            return false;
        }
        if (exception.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return true;
        }
        return StringUtils.hasText(firstHeader(exception.getResponseHeaders(), HttpHeaders.RETRY_AFTER));
    }

    private long resolveRetryDelayMillis(RestClientResponseException exception, int attempt) {
        Long retryAfterMillis = parseRetryAfterMillis(firstHeader(exception.getResponseHeaders(), HttpHeaders.RETRY_AFTER));
        if (retryAfterMillis == null) {
            retryAfterMillis = parseRateLimitResetMillis(firstHeader(exception.getResponseHeaders(), "X-RateLimit-Reset"));
        }
        if (retryAfterMillis == null) {
            retryAfterMillis = exponentialBackoffDelayMillis(attempt);
        }
        return Math.min(Math.max(retryAfterMillis, 0L), Math.max(0L, githubProperties.getRateLimitMaxDelayMillis()));
    }

    private long exponentialBackoffDelayMillis(int attempt) {
        long initialDelayMillis = Math.max(0L, githubProperties.getRateLimitInitialDelayMillis());
        if (initialDelayMillis == 0L) {
            return 0L;
        }
        double multiplier = Math.max(1.0D, githubProperties.getRateLimitBackoffMultiplier());
        double delay = initialDelayMillis * Math.pow(multiplier, Math.max(0, attempt - 1));
        if (delay >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) delay;
    }

    private Long parseRetryAfterMillis(String retryAfter) {
        if (!StringUtils.hasText(retryAfter)) {
            return null;
        }
        String value = retryAfter.trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds * 1000L;
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Duration.between(Instant.now(), retryAt).toMillis();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private Long parseRateLimitResetMillis(String resetEpochSeconds) {
        if (!StringUtils.hasText(resetEpochSeconds)) {
            return null;
        }
        try {
            Instant resetAt = Instant.ofEpochSecond(Long.parseLong(resetEpochSeconds.trim()));
            return Duration.between(Instant.now(), resetAt).toMillis();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private BusinessException buildRateLimitException(String operation, RestClientResponseException exception) {
        String retryAfter = firstHeader(exception.getResponseHeaders(), HttpHeaders.RETRY_AFTER);
        String remaining = firstHeader(exception.getResponseHeaders(), "X-RateLimit-Remaining");
        String reset = firstHeader(exception.getResponseHeaders(), "X-RateLimit-Reset");

        StringBuilder message = new StringBuilder(operation)
                .append(": GitHub API rate limit exceeded")
                .append(", status=")
                .append(exception.getStatusCode().value());
        if (StringUtils.hasText(retryAfter)) {
            message.append(", Retry-After=").append(retryAfter);
        }
        if (StringUtils.hasText(remaining)) {
            message.append(", X-RateLimit-Remaining=").append(remaining);
        }
        if (StringUtils.hasText(reset)) {
            message.append(", X-RateLimit-Reset=").append(reset);
        }
        String responseBody = summarizeResponseBody(exception);
        if (StringUtils.hasText(responseBody)) {
            message.append(", response=").append(responseBody);
        }
        return new BusinessException(message.toString());
    }

    private String summarizeResponseBody(RestClientResponseException exception) {
        String responseBody = exception.getResponseBodyAsString();
        if (!StringUtils.hasText(responseBody)) {
            return "";
        }
        String normalized = responseBody.replaceAll("\\s+", " ").trim();
        return SensitiveDataSanitizer.redactAndTruncate(normalized, MAX_RESPONSE_BODY_SUMMARY_LENGTH);
    }

    private String firstHeader(HttpHeaders headers, String name) {
        return headers == null ? null : headers.getFirst(name);
    }

    private void sleepBeforeRetry(String operation, long delayMillis) {
        if (delayMillis <= 0) {
            return;
        }
        try {
            rateLimitSleeper.accept(delayMillis);
        } catch (RuntimeException exception) {
            throw new BusinessException(operation + ": GitHub API rate limit retry interrupted");
        }
    }

    @FunctionalInterface
    interface GithubRequestSupplier<T> {

        T get();
    }
}
