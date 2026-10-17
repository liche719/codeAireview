package com.codepilot.module.git.client;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.git.auth.GithubAuthTokenProvider;
import com.codepilot.module.git.config.GithubProperties;
import com.codepilot.module.git.config.GithubRestClientFactory;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.git.dto.GithubLinkedIssue;
import com.codepilot.module.git.dto.GithubReviewThread;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.git.dto.GithubMergeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

@Slf4j
@Component
public class GithubClient {

    private static final int PER_PAGE = 100;

    /** commit status 的 state 默认值（GitHub 只认 error/failure/pending/success）。 */
    private static final String STATUS_SUCCESS_STATE = "success";

    /** GitHub 对 commit status description 的长度上限。 */
    private static final int COMMIT_STATUS_DESCRIPTION_MAX_CHARS = 140;

    private final RestClient restClient;

    private final GithubAuthTokenProvider githubAuthTokenProvider;

    private final GithubRequestExecutor requestExecutor;

    private final GithubLinkedIssueResolver linkedIssueResolver = new GithubLinkedIssueResolver();

    private final GithubReviewThreadResolver reviewThreadResolver = new GithubReviewThreadResolver();

    private volatile String authenticatedUserLogin;

    @Autowired
    public GithubClient(GithubProperties githubProperties, GithubAuthTokenProvider githubAuthTokenProvider) {
        GithubProperties safeProperties = githubProperties == null ? new GithubProperties() : githubProperties;
        RestClient restClient = GithubRestClientFactory.create(safeProperties);
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

    public GithubPullRequestDetail createPullRequest(
            String owner,
            String repo,
            String title,
            String head,
            String base,
            String body
    ) {
        if (!StringUtils.hasText(title) || !StringUtils.hasText(head) || !StringUtils.hasText(base)) {
            throw new BusinessException("failed to create GitHub pull request: title, head and base are required");
        }
        GithubPullRequestDetail detail = executeGithubRequest("failed to create GitHub pull request", () ->
                restClient.post()
                        .uri("/repos/{owner}/{repo}/pulls", owner, repo)
                        .headers(headers -> setAuthorization(headers, owner, repo))
                        .body(Map.of(
                                "title", title,
                                "head", head,
                                "base", base,
                                "body", body == null ? "" : body
                        ))
                        .retrieve()
                        .body(GithubPullRequestDetail.class)
        );
        if (detail == null || detail.getNumber() == null || !StringUtils.hasText(detail.getHtmlUrl())) {
            throw new BusinessException("failed to create GitHub pull request: response is incomplete");
        }
        return detail;
    }

    public GithubPullRequestDetail findOpenPullRequestByHead(
            String owner,
            String repo,
            String headBranch,
            String baseBranch
    ) {
        List<GithubPullRequestDetail> pullRequests = executeGithubRequest(
                "failed to find GitHub pull request by head",
                () -> restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/repos/{owner}/{repo}/pulls")
                                .queryParam("state", "open")
                                .queryParam("head", owner + ":" + headBranch)
                                .queryParam("base", baseBranch)
                                .build(owner, repo))
                        .headers(headers -> setAuthorization(headers, owner, repo))
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<GithubPullRequestDetail>>() {
                        })
        );
        return pullRequests == null || pullRequests.isEmpty() ? null : pullRequests.getFirst();
    }

    public GithubMergeResult mergePullRequest(
            String owner,
            String repo,
            Integer pullNumber,
            String expectedHeadSha,
            String mergeMethod,
            String commitTitle
    ) {
        GithubMergeResult result = executeGithubRequest("failed to merge GitHub pull request", () ->
                restClient.put()
                        .uri("/repos/{owner}/{repo}/pulls/{pullNumber}/merge", owner, repo, pullNumber)
                        .headers(headers -> setAuthorization(headers, owner, repo))
                        .body(Map.of(
                                "sha", expectedHeadSha,
                                "merge_method", mergeMethod,
                                "commit_title", commitTitle
                        ))
                        .retrieve()
                        .body(GithubMergeResult.class)
        );
        if (result == null) {
            throw new BusinessException("failed to merge GitHub pull request: response is empty");
        }
        return result;
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
        createPullRequestInlineComment(owner, repo, pullNumber, commitId, path, line, side, null, body);
    }

    /**
     * 创建行内评论；{@code startLine} 非空时创建<b>多行区间</b>评论。
     *
     * <p>GitHub 的约束：{@code start_line} 与 {@code line} 必须位于 diff 的<b>同一侧</b>、
     * 且都落在 diff 内。调用方（{@code DiffLineMapper.mapRange}）会先做本地校验，
     * 校验不过就降级为单行评论，而不是丢弃 finding。</p>
     *
     * @param startSide 起始行所在侧；与 {@code startLine} 同时提供，二者要么都给要么都不给
     */
    public void createPullRequestInlineComment(
            String owner,
            String repo,
            Integer pullNumber,
            String commitId,
            String path,
            Integer line,
            String side,
            Integer startLine,
            String startSide,
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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        payload.put("commit_id", commitId);
        payload.put("path", path);
        boolean ranged = startLine != null && StringUtils.hasText(startSide) && startLine < line;
        if (ranged) {
            payload.put("start_line", startLine);
            payload.put("start_side", startSide);
        }
        payload.put("line", line);
        payload.put("side", side);

        executeGithubRequest("failed to create GitHub PR inline comment", () -> {
            restClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{pullNumber}/comments", owner, repo, pullNumber)
                    .headers(headers -> setAuthorization(headers, owner, repo))
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });

        log.info("GitHub PR inline comment created, owner={}, repo={}, pullNumber={}, path={}, line={}, side={}, startLine={}, ranged={}, bodyLength={}",
                owner, repo, pullNumber, path, line, side, startLine, ranged, body.length());
    }

    /**
     * 多行区间重载（不带 startSide，默认与 {@code side} 相同）。
     *
     * <p>刻意保留一个更短的签名：绝大多数调用点两侧一致，要求调用方重复传 side 只会增加出错面。</p>
     */
    public void createPullRequestInlineComment(
            String owner,
            String repo,
            Integer pullNumber,
            String commitId,
            String path,
            Integer line,
            String side,
            Integer startLine,
            String body
    ) {
        createPullRequestInlineComment(owner, repo, pullNumber, commitId, path, line, side, startLine, side, body);
    }

    /**
     * 回复某条 review comment 所在的线程（R14，同时补上 C8）。
     *
     * <p>这是"在 diff 行上提问"必须要有的能力：如果没有它，机器人的回答会变成一条
     * 新的顶层 PR 评论，与提问所在的代码行脱节，用户还得自己把两件事对上。</p>
     *
     * <p>{@code commentId} 必须是<b>线程根</b>评论的 id——GitHub 的 replies 接口不接受
     * 一条回复自己的 id。payload 解析阶段已经把 {@code in_reply_to_id} 归一成线程根了。</p>
     */
    public void replyToReviewComment(
            String owner,
            String repo,
            Integer pullNumber,
            Long commentId,
            String body
    ) {
        if (commentId == null) {
            throw new BusinessException("failed to reply to GitHub review comment: comment id is required");
        }
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("body", body == null ? "" : body);
        executeGithubRequest("failed to reply to GitHub review comment", () ->
                restClient.post()
                        .uri(uriBuilder -> uriBuilder
                                .path("/repos/{owner}/{repo}/pulls/{pullNumber}/comments/{commentId}/replies")
                                .build(owner, repo, pullNumber, commentId))
                        .headers(headers -> setAuthorization(headers, owner, repo))
                        .body(requestBody)
                        .retrieve()
                        .toBodilessEntity()
        );
    }

    /**
     * 写 commit status（R17）。
     *
     * <p><b>取值范围只有 {@code error / failure / pending / success}</b>——commit status
     * <b>没有</b> {@code neutral}（那是 Checks API 的 conclusion）。因此本项目的 NEUTRAL
     * 判定由 {@code ReviewGateEvaluator.toCommitStatus} 映射为 {@code success}。</p>
     *
     * <p>{@code description} 有 140 字符上限，调用方负责截断；这里再兜一层。</p>
     */
    public void createCommitStatus(
            String owner,
            String repo,
            String sha,
            String state,
            String context,
            String description
    ) {
        if (!StringUtils.hasText(sha)) {
            throw new BusinessException("failed to create GitHub commit status: commit sha is required");
        }
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("state", StringUtils.hasText(state) ? state : STATUS_SUCCESS_STATE);
        requestBody.put("context", StringUtils.hasText(context) ? context : "CodePilot Review");
        requestBody.put("description", truncateCommitStatusDescription(description));
        executeGithubRequest("failed to create GitHub commit status", () ->
                restClient.post()
                        .uri(uriBuilder -> uriBuilder
                                .path("/repos/{owner}/{repo}/statuses/{sha}")
                                .build(owner, repo, sha))
                        .headers(headers -> setAuthorization(headers, owner, repo))
                        .body(requestBody)
                        .retrieve()
                        .toBodilessEntity()
        );
    }

    private String truncateCommitStatusDescription(String description) {
        String safe = description == null ? "" : description;
        return safe.length() <= COMMIT_STATUS_DESCRIPTION_MAX_CHARS
                ? safe
                : safe.substring(0, COMMIT_STATUS_DESCRIPTION_MAX_CHARS - 1) + "…";
    }

    private List<GithubChangedFile> requestPullRequestFilesPage(            String owner,
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

    /**
     * 读取 PR 的 review thread 及其 resolved 状态。
     *
     * <p>必须用 GraphQL：REST 不暴露 thread 的 resolve 状态，只有 GraphQL 的
     * {@code PullRequestReviewThread.isResolved} 才有。</p>
     *
     * <p>注意只取每条线程的<b>首条</b>评论——判断"这个线程是不是我们开的"必须看发起者。</p>
     */
    public List<GithubReviewThread> listReviewThreads(String owner, String repo, Integer pullNumber) {
        Map<String, Object> response = executeGithubRequest("failed to list GitHub PR review threads", () ->
                restClient.post()
                        .uri("/graphql")
                        .headers(headers -> setAuthorization(headers, owner, repo))
                        .body(Map.of(
                                "query",
                                """
                                query($owner: String!, $repo: String!, $number: Int!, $first: Int!) {
                                  repository(owner: $owner, name: $repo) {
                                    pullRequest(number: $number) {
                                      reviewThreads(first: $first) {
                                        nodes {
                                          id
                                          isResolved
                                          comments(first: 1) {
                                            nodes {
                                              author { login }
                                              body
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
                                        "first", GithubReviewThreadResolver.PAGE_SIZE
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
            throw new BusinessException("failed to list GitHub PR review threads: "
                    + linkedIssueResolver.summarizeGraphqlErrors(errorList));
        }
        return reviewThreadResolver.fromGraphqlResponse(response);
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
