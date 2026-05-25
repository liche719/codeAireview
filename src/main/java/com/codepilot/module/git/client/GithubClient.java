package com.codepilot.module.git.client;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.config.GithubProperties;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.git.dto.GithubLinkedIssue;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class GithubClient {

    private static final int PER_PAGE = 100;

    private static final int MAX_RESPONSE_BODY_SUMMARY_LENGTH = 240;

    private static final int MAX_LINKED_ISSUES = 20;

    private static final Pattern CLOSING_KEYWORD_LINE = Pattern.compile(
            "(?i)\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\b[^\\r\\n]*"
    );

    private static final Pattern ISSUE_REFERENCE = Pattern.compile(
            "(?i)(?:https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/issues/(\\d+)"
                    + "|([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)#(\\d+)"
                    + "|#(\\d+))"
    );

    private final RestClient restClient;

    private final String token;

    private final GithubProperties githubProperties;

    private final LongConsumer rateLimitSleeper;

    private volatile String authenticatedUserLogin;

    public GithubClient(GithubProperties githubProperties) {
        this(
                githubProperties == null ? new GithubProperties() : githubProperties,
                RestClient.builder()
                        .baseUrl(githubProperties == null ? "https://api.github.com" : githubProperties.getApiBaseUrl())
                        .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                        .build(),
                GithubClient::sleep
        );
    }

    GithubClient(String token, RestClient restClient, LongConsumer rateLimitSleeper) {
        GithubProperties properties = new GithubProperties();
        properties.setToken(token);
        this.githubProperties = properties;
        this.token = token;
        this.restClient = restClient;
        this.rateLimitSleeper = rateLimitSleeper;
    }

    GithubClient(GithubProperties githubProperties, RestClient restClient, LongConsumer rateLimitSleeper) {
        GithubProperties safeProperties = githubProperties == null ? new GithubProperties() : githubProperties;
        this.githubProperties = safeProperties;
        this.token = safeProperties.getToken();
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

    public List<GithubIssueComment> listPullRequestReviewComments(String owner, String repo, Integer pullNumber) {
        List<GithubIssueComment> allComments = new ArrayList<>();
        int page = 1;

        while (true) {
            List<GithubIssueComment> pageComments = requestPullRequestReviewCommentsPage(owner, repo, pullNumber, page);
            allComments.addAll(pageComments);

            if (pageComments.size() < PER_PAGE) {
                break;
            }
            page++;
        }

        log.info("GitHub PR review comments fetched, owner={}, repo={}, pullNumber={}, totalComments={}",
                owner, repo, pullNumber, allComments.size());
        return allComments;
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

    public List<GithubLinkedIssue> listPullRequestLinkedIssues(String owner, String repo, Integer pullNumber) {
        List<GithubLinkedIssue> graphqlIssues = List.of();
        try {
            graphqlIssues = requestPullRequestClosingIssues(owner, repo, pullNumber);
        } catch (BusinessException exception) {
            log.warn("GitHub PR linked issues GraphQL lookup failed, fallback to PR body parsing, owner={}, repo={}, pullNumber={}, message={}",
                    owner, repo, pullNumber, SensitiveDataSanitizer.redact(exception.getMessage()));
        }
        if (!graphqlIssues.isEmpty()) {
            log.info("GitHub PR linked issues fetched from GraphQL, owner={}, repo={}, pullNumber={}, issueCount={}",
                    owner, repo, pullNumber, graphqlIssues.size());
            return graphqlIssues;
        }

        GithubPullRequestDetail detail = getPullRequestDetail(owner, repo, pullNumber);
        List<GithubLinkedIssue> bodyIssues = linkedIssuesFromBody(owner, repo, detail.getBody());
        log.info("GitHub PR linked issues resolved from PR body, owner={}, repo={}, pullNumber={}, issueCount={}",
                owner, repo, pullNumber, bodyIssues.size());
        return bodyIssues;
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
                log.warn("failed to resolve GitHub authenticated user login, message={}",
                        SensitiveDataSanitizer.redact(exception.getMessage()));
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

    private List<GithubIssueComment> requestPullRequestReviewCommentsPage(
            String owner,
            String repo,
            Integer pullNumber,
            int page
    ) {
        List<GithubIssueComment> comments = executeGithubRequest("failed to list GitHub PR review comments", () ->
                restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/pulls/{pullNumber}/comments")
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

    private List<GithubLinkedIssue> requestPullRequestClosingIssues(String owner, String repo, Integer pullNumber) {
        Map<String, Object> response = executeGithubRequest("failed to list GitHub PR linked issues", () ->
                restClient.post()
                        .uri("/graphql")
                        .headers(this::setAuthorization)
                        .body(Map.of(
                                "query",
                                """
                                query($owner: String!, $repo: String!, $number: Int!, $first: Int!) {
                                  repository(owner: $owner, name: $repo) {
                                    pullRequest(number: $number) {
                                      closingIssuesReferences(first: $first) {
                                        nodes {
                                          number
                                          title
                                          state
                                          url
                                          repository {
                                            name
                                            owner {
                                              login
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }
                                }
                                """,
                                "variables",
                                Map.of(
                                        "owner", owner,
                                        "repo", repo,
                                        "number", pullNumber,
                                        "first", MAX_LINKED_ISSUES
                                )
                        ))
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {
                        })
        );
        if (response == null) {
            return List.of();
        }
        Object errors = response.get("errors");
        if (errors instanceof List<?> errorList && !errorList.isEmpty()) {
            throw new BusinessException("failed to list GitHub PR linked issues: " + summarizeGraphqlErrors(errorList));
        }
        return linkedIssuesFromGraphqlResponse(owner, repo, response);
    }

    private List<GithubLinkedIssue> linkedIssuesFromGraphqlResponse(
            String owner,
            String repo,
            Map<String, Object> response
    ) {
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return List.of();
        }
        Object repository = dataMap.get("repository");
        if (!(repository instanceof Map<?, ?> repositoryMap)) {
            return List.of();
        }
        Object pullRequest = repositoryMap.get("pullRequest");
        if (!(pullRequest instanceof Map<?, ?> pullRequestMap)) {
            return List.of();
        }
        Object closingIssuesReferences = pullRequestMap.get("closingIssuesReferences");
        if (!(closingIssuesReferences instanceof Map<?, ?> referencesMap)) {
            return List.of();
        }
        Object nodes = referencesMap.get("nodes");
        if (!(nodes instanceof List<?> nodeList)) {
            return List.of();
        }

        List<GithubLinkedIssue> issues = new ArrayList<>();
        for (Object node : nodeList) {
            if (!(node instanceof Map<?, ?> nodeMap)) {
                continue;
            }
            Integer number = intValue(nodeMap.get("number"));
            if (number == null) {
                continue;
            }
            RepositoryRef repositoryRef = repositoryRef(owner, repo, nodeMap.get("repository"));
            issues.add(new GithubLinkedIssue(
                    repositoryRef.owner(),
                    repositoryRef.name(),
                    number,
                    stringValue(nodeMap.get("title")),
                    stringValue(nodeMap.get("state")),
                    stringValue(nodeMap.get("url")),
                    "GRAPHQL_CLOSING_ISSUES"
            ));
        }
        return dedupeLinkedIssues(issues);
    }

    private List<GithubLinkedIssue> linkedIssuesFromBody(String owner, String repo, String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        Map<String, GithubLinkedIssue> issuesByKey = new LinkedHashMap<>();
        Matcher keywordMatcher = CLOSING_KEYWORD_LINE.matcher(body);
        while (keywordMatcher.find() && issuesByKey.size() < MAX_LINKED_ISSUES) {
            Matcher referenceMatcher = ISSUE_REFERENCE.matcher(keywordMatcher.group());
            while (referenceMatcher.find() && issuesByKey.size() < MAX_LINKED_ISSUES) {
                RepositoryIssueRef ref = repositoryIssueRef(owner, repo, referenceMatcher);
                if (ref == null) {
                    continue;
                }
                GithubLinkedIssue issue = fetchIssueDetailOrReference(ref.owner(), ref.repo(), ref.number());
                issuesByKey.putIfAbsent(issueKey(issue), issue);
            }
        }
        return List.copyOf(issuesByKey.values());
    }

    private GithubLinkedIssue fetchIssueDetailOrReference(String owner, String repo, Integer issueNumber) {
        try {
            Map<String, Object> issue = requestIssueDetail(owner, repo, issueNumber);
            return new GithubLinkedIssue(
                    owner,
                    repo,
                    issueNumber,
                    stringValue(issue.get("title")),
                    stringValue(issue.get("state")),
                    stringValue(issue.get("html_url")),
                    "PR_BODY_CLOSING_KEYWORD"
            );
        } catch (BusinessException exception) {
            log.warn("GitHub linked issue detail lookup failed, fallback to reference only, owner={}, repo={}, issueNumber={}, message={}",
                    owner, repo, issueNumber, SensitiveDataSanitizer.redact(exception.getMessage()));
            return new GithubLinkedIssue(
                    owner,
                    repo,
                    issueNumber,
                    null,
                    null,
                    "https://github.com/" + owner + "/" + repo + "/issues/" + issueNumber,
                    "PR_BODY_CLOSING_KEYWORD"
            );
        }
    }

    private Map<String, Object> requestIssueDetail(String owner, String repo, Integer issueNumber) {
        Map<String, Object> issue = executeGithubRequest("failed to get GitHub linked issue detail", () ->
                restClient.get()
                        .uri("/repos/{owner}/{repo}/issues/{issueNumber}", owner, repo, issueNumber)
                        .headers(this::setAuthorization)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {
                        })
        );
        return issue == null ? Map.of() : issue;
    }

    private <T> T executeGithubRequest(String operation, GithubRequestSupplier<T> supplier) {
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
        if (normalized.length() <= MAX_RESPONSE_BODY_SUMMARY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_RESPONSE_BODY_SUMMARY_LENGTH);
    }

    private List<GithubLinkedIssue> dedupeLinkedIssues(List<GithubLinkedIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        Map<String, GithubLinkedIssue> issuesByKey = new LinkedHashMap<>();
        for (GithubLinkedIssue issue : issues) {
            if (issue != null && issue.getNumber() != null) {
                issuesByKey.putIfAbsent(issueKey(issue), issue);
            }
        }
        return List.copyOf(issuesByKey.values());
    }

    private String issueKey(GithubLinkedIssue issue) {
        return normalizeKey(issue.getRepositoryOwner())
                + "/"
                + normalizeKey(issue.getRepositoryName())
                + "#"
                + issue.getNumber();
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private RepositoryIssueRef repositoryIssueRef(String defaultOwner, String defaultRepo, Matcher matcher) {
        if (matcher.group(3) != null) {
            return new RepositoryIssueRef(matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)));
        }
        if (matcher.group(6) != null) {
            return new RepositoryIssueRef(matcher.group(4), matcher.group(5), Integer.parseInt(matcher.group(6)));
        }
        if (matcher.group(7) != null) {
            return new RepositoryIssueRef(defaultOwner, defaultRepo, Integer.parseInt(matcher.group(7)));
        }
        return null;
    }

    private RepositoryRef repositoryRef(String defaultOwner, String defaultRepo, Object repository) {
        if (!(repository instanceof Map<?, ?> repositoryMap)) {
            return new RepositoryRef(defaultOwner, defaultRepo);
        }
        String name = stringValue(repositoryMap.get("name"));
        Object owner = repositoryMap.get("owner");
        String ownerLogin = defaultOwner;
        if (owner instanceof Map<?, ?> ownerMap && ownerMap.get("login") != null) {
            ownerLogin = ownerMap.get("login").toString();
        }
        return new RepositoryRef(
                StringUtils.hasText(ownerLogin) ? ownerLogin : defaultOwner,
                StringUtils.hasText(name) ? name : defaultRepo
        );
    }

    private String summarizeGraphqlErrors(List<?> errors) {
        String summary = errors.stream()
                .limit(3)
                .map(this::graphqlErrorMessage)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "; " + right)
                .orElse("unknown GraphQL error");
        return SensitiveDataSanitizer.redactAndTruncate(summary, MAX_RESPONSE_BODY_SUMMARY_LENGTH);
    }

    private String graphqlErrorMessage(Object error) {
        if (error instanceof Map<?, ?> errorMap && errorMap.get("message") != null) {
            return errorMap.get("message").toString();
        }
        return error == null ? "" : error.toString();
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
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

    private record RepositoryRef(String owner, String name) {
    }

    private record RepositoryIssueRef(String owner, String repo, Integer number) {
    }
}
