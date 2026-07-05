package com.codepilot.module.git.client;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.auth.GithubAuthTokenProvider;
import com.codepilot.module.git.config.GithubProperties;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.git.dto.GithubLinkedIssue;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

@Slf4j
@Component
public class GithubClient {

    private static final int PER_PAGE = 100;

    private final RestClient restClient;

    private final GithubAuthTokenProvider githubAuthTokenProvider;

    private final GithubRequestExecutor requestExecutor;

    private final GithubLinkedIssueResolver linkedIssueResolver = new GithubLinkedIssueResolver();

    private volatile String authenticatedUserLogin;

    @Autowired
    public GithubClient(GithubProperties githubProperties, GithubAuthTokenProvider githubAuthTokenProvider) {
        GithubProperties safeProperties = githubProperties == null ? new GithubProperties() : githubProperties;
        RestClient restClient = RestClient.builder()
                .baseUrl(safeProperties.getApiBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.githubAuthTokenProvider = githubAuthTokenProvider == null
                ? new GithubAuthTokenProvider(safeProperties, restClient)
                : githubAuthTokenProvider;
        this.restClient = restClient;
        this.requestExecutor = new GithubRequestExecutor(safeProperties, GithubClient::sleep);
    }

    GithubClient(String token, RestClient restClient, LongConsumer rateLimitSleeper) {
        GithubProperties properties = new GithubProperties();
        properties.setToken(token);
        properties.setAuthMode(GithubProperties.AuthMode.PAT);
        this.githubAuthTokenProvider = new GithubAuthTokenProvider(properties, restClient);
        this.restClient = restClient;
        this.requestExecutor = new GithubRequestExecutor(properties, rateLimitSleeper);
    }

    GithubClient(GithubProperties githubProperties, RestClient restClient, LongConsumer rateLimitSleeper) {
        GithubProperties safeProperties = githubProperties == null ? new GithubProperties() : githubProperties;
        this.githubAuthTokenProvider = new GithubAuthTokenProvider(safeProperties, restClient);
        this.restClient = restClient;
        this.requestExecutor = new GithubRequestExecutor(safeProperties, rateLimitSleeper);
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
                    .headers(headers -> setAuthorization(headers, owner, repo))
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
                    .headers(headers -> setAuthorization(headers, owner, repo))
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
                    .headers(headers -> setAuthorization(headers, owner, repo))
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

        GithubPullRequestDetail detail;
        try {
            detail = getPullRequestDetail(owner, repo, pullNumber);
        } catch (BusinessException exception) {
            log.warn("GitHub PR detail lookup failed, return empty linked issues, owner={}, repo={}, pullNumber={}, message={}",
                    owner, repo, pullNumber, SensitiveDataSanitizer.redact(exception.getMessage()));
            return List.of();
        }
        List<GithubLinkedIssue> bodyIssues = linkedIssueResolver.fromBody(
                owner,
                repo,
                detail.getBody(),
                this::requestIssueDetail
        );
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
                String login = githubAuthTokenProvider.resolveAuthenticatedLogin().orElse(null);
                if (!StringUtils.hasText(login)) {
                    return null;
                }
                authenticatedUserLogin = login;
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
                    .headers(headers -> setAuthorization(headers, owner, repo))
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
                    .headers(headers -> setAuthorization(headers, owner, repo))
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
                .headers(headers -> setAuthorization(headers, owner, repo))
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
                .headers(headers -> setAuthorization(headers, owner, repo))
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
                .headers(headers -> setAuthorization(headers, owner, repo))
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
                        .headers(headers -> setAuthorization(headers, owner, repo))
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
                                        "first", GithubLinkedIssueResolver.MAX_LINKED_ISSUES
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
            throw new BusinessException("failed to list GitHub PR linked issues: "
                    + linkedIssueResolver.summarizeGraphqlErrors(errorList));
        }
        return linkedIssueResolver.fromGraphqlResponse(owner, repo, response);
    }

    private Map<String, Object> requestIssueDetail(String owner, String repo, Integer issueNumber) {
        Map<String, Object> issue = executeGithubRequest("failed to get GitHub linked issue detail", () ->
                restClient.get()
                        .uri("/repos/{owner}/{repo}/issues/{issueNumber}", owner, repo, issueNumber)
                        .headers(headers -> setAuthorization(headers, owner, repo))
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {
                        })
        );
        return issue == null ? Map.of() : issue;
    }

    private <T> T executeGithubRequest(String operation, GithubRequestExecutor.GithubRequestSupplier<T> supplier) {
        return requestExecutor.execute(operation, supplier);
    }

    private void setAuthorization(HttpHeaders headers, String owner, String repo) {
        githubAuthTokenProvider.setAuthorization(headers, owner, repo);
    }

    private static void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub API rate limit retry interrupted", exception);
        }
    }
}
