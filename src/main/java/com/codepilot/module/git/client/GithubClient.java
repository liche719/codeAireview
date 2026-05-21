package com.codepilot.module.git.client;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongConsumer;

@Slf4j
@Component
public class GithubClient {

    private static final int PER_PAGE = 100;

    private static final int MAX_GITHUB_REQUEST_ATTEMPTS = 2;

    private static final long DEFAULT_RATE_LIMIT_RETRY_DELAY_MILLIS = 1000L;

    private static final long MAX_RATE_LIMIT_RETRY_DELAY_MILLIS = 2000L;

    private static final int MAX_RESPONSE_BODY_SUMMARY_LENGTH = 240;

    private final RestClient restClient;

    private final String token;

    private final LongConsumer rateLimitSleeper;

    private volatile String authenticatedUserLogin;

    public GithubClient(
            @Value("${codepilot.github.api-base-url:https://api.github.com}") String apiBaseUrl,
            @Value("${codepilot.github.token:}") String token
    ) {
        this(
                token,
                RestClient.builder()
                        .baseUrl(apiBaseUrl)
                        .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                        .build(),
                GithubClient::sleep
        );
    }

    GithubClient(String token, RestClient restClient, LongConsumer rateLimitSleeper) {
        this.token = token;
        this.restClient = restClient;
        this.rateLimitSleeper = rateLimitSleeper;
    }

    public List<GithubChangedFile> listPullRequestFiles(String owner, String repo, Integer pullNumber) {
        List<GithubChangedFile> allFiles = new ArrayList<>();
        int page = 1;

        while (true) {
            List<GithubChangedFile> pageFiles = requestPullRequestFilesPage(owner, repo, pullNumber, page);
            allFiles.addAll(pageFiles);

            if (pageFiles.size() < PER_PAGE) {
                break;
            }
            page++;
        }

        log.info("GitHub PR files fetched, owner={}, repo={}, pullNumber={}, totalFiles={}",
                owner, repo, pullNumber, allFiles.size());
        return allFiles;
    }

    public void createPullRequestComment(String owner, String repo, Integer pullNumber, String body) {
        if (!StringUtils.hasText(body)) {
            log.info("Skip GitHub PR comment because body is empty, owner={}, repo={}, pullNumber={}",
                    owner, repo, pullNumber);
            return;
        }

        executeGithubRequest("failed to create GitHub PR comment", () -> {
            restClient.post()
                    .uri("/repos/{owner}/{repo}/issues/{issueNumber}/comments", owner, repo, pullNumber)
                    .headers(this::setAuthorization)
                    .body(Map.of("body", body))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });

        log.info("GitHub PR comment created, owner={}, repo={}, pullNumber={}, bodyLength={}",
                owner, repo, pullNumber, body.length());
    }

    public List<GithubIssueComment> listPullRequestComments(String owner, String repo, Integer pullNumber) {
        List<GithubIssueComment> allComments = new ArrayList<>();
        int page = 1;

        while (true) {
            List<GithubIssueComment> pageComments = requestPullRequestCommentsPage(owner, repo, pullNumber, page);
            allComments.addAll(pageComments);

            if (pageComments.size() < PER_PAGE) {
                break;
            }
            page++;
        }

        log.info("GitHub PR comments fetched, owner={}, repo={}, pullNumber={}, totalComments={}",
                owner, repo, pullNumber, allComments.size());
        return allComments;
    }

    public void updateIssueComment(String owner, String repo, Long commentId, String body) {
        if (!StringUtils.hasText(body)) {
            log.info("Skip GitHub PR comment update because body is empty, owner={}, repo={}, commentId={}",
                    owner, repo, commentId);
            return;
        }

        executeGithubRequest("failed to update GitHub PR comment", () -> {
            restClient.patch()
                    .uri("/repos/{owner}/{repo}/issues/comments/{commentId}", owner, repo, commentId)
                    .headers(this::setAuthorization)
                    .body(Map.of("body", body))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });

        log.info("GitHub PR comment updated, owner={}, repo={}, commentId={}, bodyLength={}",
                owner, repo, commentId, body.length());
    }

    public GithubPullRequestDetail getPullRequestDetail(String owner, String repo, Integer pullNumber) {
        GithubPullRequestDetail detail = executeGithubRequest("failed to get GitHub PR detail", () ->
                restClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{pullNumber}", owner, repo, pullNumber)
                    .headers(this::setAuthorization)
                    .retrieve()
                    .body(GithubPullRequestDetail.class)
        );
        if (detail == null) {
            throw new BusinessException("GitHub PR detail response is empty");
        }
        log.info("GitHub PR detail fetched, owner={}, repo={}, pullNumber={}, headSha={}",
                owner, repo, pullNumber, detail.getHeadSha());
        return detail;
    }

    public String getAuthenticatedUserLogin() {
        String cachedLogin = authenticatedUserLogin;
        if (StringUtils.hasText(cachedLogin)) {
            return cachedLogin;
        }

        synchronized (this) {
            if (StringUtils.hasText(authenticatedUserLogin)) {
                return authenticatedUserLogin;
            }
            try {
                Map<String, Object> response = executeGithubRequest("failed to resolve GitHub authenticated user login", () ->
                        restClient.get()
                        .uri("/user")
                        .headers(this::setAuthorization)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {
                        })
                );
                if (response == null || response.get("login") == null) {
                    return null;
                }
                authenticatedUserLogin = response.get("login").toString();
                log.info("GitHub authenticated user resolved, login={}", authenticatedUserLogin);
                return authenticatedUserLogin;
            } catch (BusinessException exception) {
                log.warn("failed to resolve GitHub authenticated user login, message={}", exception.getMessage());
                return null;
            }
        }
    }

    public String getFileContent(String owner, String repo, String path, String ref) {
        if (!StringUtils.hasText(path) || !StringUtils.hasText(ref)) {
            throw new BusinessException("failed to get GitHub file content: path and ref are required");
        }
        Map<String, Object> response = executeGithubRequest("failed to get GitHub file content", () ->
                restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/contents/{path}")
                            .queryParam("ref", ref)
                            .build(owner, repo, path))
                    .headers(this::setAuthorization)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
        );
        if (response == null || response.get("content") == null) {
            return "";
        }
        String encoding = response.get("encoding") == null ? "" : response.get("encoding").toString();
        String content = response.get("content").toString().replaceAll("\\s+", "");
        if ("base64".equalsIgnoreCase(encoding)) {
            return new String(Base64.getDecoder().decode(content), java.nio.charset.StandardCharsets.UTF_8);
        }
        return content;
    }

    public void createPullRequestInlineComment(
            String owner,
            String repo,
            Integer pullNumber,
            String commitId,
            String path,
            Integer line,
            String side,
            String body
    ) {
        if (!StringUtils.hasText(body)) {
            log.info("Skip GitHub PR inline comment because body is empty, owner={}, repo={}, pullNumber={}, path={}, line={}",
                    owner, repo, pullNumber, path, line);
            return;
        }
        if (!StringUtils.hasText(commitId) || !StringUtils.hasText(path) || line == null || !StringUtils.hasText(side)) {
            throw new BusinessException("failed to create GitHub PR inline comment: commit_id, path, line and side are required");
        }

        executeGithubRequest("failed to create GitHub PR inline comment", () -> {
            restClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{pullNumber}/comments", owner, repo, pullNumber)
                    .headers(this::setAuthorization)
                    .body(Map.of(
                            "body", body,
                            "commit_id", commitId,
                            "path", path,
                            "line", line,
                            "side", side
                    ))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });

        log.info("GitHub PR inline comment created, owner={}, repo={}, pullNumber={}, path={}, line={}, side={}, bodyLength={}",
                owner, repo, pullNumber, path, line, side, body.length());
    }

    private List<GithubChangedFile> requestPullRequestFilesPage(
            String owner,
            String repo,
            Integer pullNumber,
            int page
    ) {
        List<GithubChangedFile> files = executeGithubRequest("failed to list GitHub PR files", () ->
                restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/pulls/{pullNumber}/files")
                        .queryParam("per_page", PER_PAGE)
                        .queryParam("page", page)
                        .build(owner, repo, pullNumber))
                .headers(this::setAuthorization)
                .retrieve()
                .body(new ParameterizedTypeReference<List<GithubChangedFile>>() {
                })
        );
        return files == null ? List.of() : files;
    }

    private List<GithubIssueComment> requestPullRequestCommentsPage(
            String owner,
            String repo,
            Integer pullNumber,
            int page
    ) {
        List<GithubIssueComment> comments = executeGithubRequest("failed to list GitHub PR comments", () ->
                restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/issues/{issueNumber}/comments")
                        .queryParam("per_page", PER_PAGE)
                        .queryParam("page", page)
                        .build(owner, repo, pullNumber))
                .headers(this::setAuthorization)
                .retrieve()
                .body(new ParameterizedTypeReference<List<GithubIssueComment>>() {
                })
        );
        return comments == null ? List.of() : comments;
    }

    private <T> T executeGithubRequest(String operation, GithubRequestSupplier<T> supplier) {
        for (int attempt = 1; attempt <= MAX_GITHUB_REQUEST_ATTEMPTS; attempt++) {
            try {
                return supplier.get();
            } catch (RestClientResponseException exception) {
                if (!isGithubRateLimit(exception)) {
                    throw new BusinessException(operation + ": " + exception.getMessage());
                }
                if (!shouldRetryRateLimit(exception, attempt)) {
                    throw buildRateLimitException(operation, exception);
                }
                long delayMillis = resolveRetryDelayMillis(exception);
                log.warn("GitHub API rate limit hit, retrying request, operation={}, attempt={}, delayMillis={}, status={}, message={}",
                        operation,
                        attempt,
                        delayMillis,
                        exception.getStatusCode().value(),
                        summarizeResponseBody(exception));
                sleepBeforeRetry(operation, delayMillis);
            } catch (RestClientException exception) {
                throw new BusinessException(operation + ": " + exception.getMessage());
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
        if (attempt >= MAX_GITHUB_REQUEST_ATTEMPTS) {
            return false;
        }
        if (exception.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return true;
        }
        return StringUtils.hasText(firstHeader(exception.getResponseHeaders(), HttpHeaders.RETRY_AFTER));
    }

    private long resolveRetryDelayMillis(RestClientResponseException exception) {
        Long retryAfterMillis = parseRetryAfterMillis(firstHeader(exception.getResponseHeaders(), HttpHeaders.RETRY_AFTER));
        if (retryAfterMillis == null) {
            retryAfterMillis = parseRateLimitResetMillis(firstHeader(exception.getResponseHeaders(), "X-RateLimit-Reset"));
        }
        if (retryAfterMillis == null) {
            retryAfterMillis = DEFAULT_RATE_LIMIT_RETRY_DELAY_MILLIS;
        }
        return Math.min(Math.max(retryAfterMillis, 0L), MAX_RATE_LIMIT_RETRY_DELAY_MILLIS);
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
        if (normalized.length() <= MAX_RESPONSE_BODY_SUMMARY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_RESPONSE_BODY_SUMMARY_LENGTH);
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

    private void setAuthorization(HttpHeaders headers) {
        if (StringUtils.hasText(token)) {
            headers.setBearerAuth(token);
        }
    }

    private static void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub API rate limit retry interrupted", exception);
        }
    }

    @FunctionalInterface
    private interface GithubRequestSupplier<T> {

        T get();
    }
}
