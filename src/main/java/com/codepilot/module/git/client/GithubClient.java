package com.codepilot.module.git.client;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubIssueComment;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GithubClient {

    private static final int PER_PAGE = 100;

    private final RestClient restClient;

    private final String token;

    private volatile String authenticatedUserLogin;

    public GithubClient(
            @Value("${codepilot.github.api-base-url:https://api.github.com}") String apiBaseUrl,
            @Value("${codepilot.github.token:}") String token
    ) {
        this.token = token;
        this.restClient = RestClient.builder()
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
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

        try {
            restClient.post()
                    .uri("/repos/{owner}/{repo}/issues/{issueNumber}/comments", owner, repo, pullNumber)
                    .headers(this::setAuthorization)
                    .body(Map.of("body", body))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new BusinessException("failed to create GitHub PR comment: " + exception.getMessage());
        }

        log.info("GitHub PR comment created, owner={}, repo={}, pullNumber={}, bodyLength={}",
                owner, repo, pullNumber, body.length());
    }

    public List<GithubIssueComment> listPullRequestComments(String owner, String repo, Integer pullNumber) {
        try {
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
        } catch (RestClientException exception) {
            throw new BusinessException("failed to list GitHub PR comments: " + exception.getMessage());
        }
    }

    public void updateIssueComment(String owner, String repo, Long commentId, String body) {
        if (!StringUtils.hasText(body)) {
            log.info("Skip GitHub PR comment update because body is empty, owner={}, repo={}, commentId={}",
                    owner, repo, commentId);
            return;
        }

        try {
            restClient.patch()
                    .uri("/repos/{owner}/{repo}/issues/comments/{commentId}", owner, repo, commentId)
                    .headers(this::setAuthorization)
                    .body(Map.of("body", body))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new BusinessException("failed to update GitHub PR comment: " + exception.getMessage());
        }

        log.info("GitHub PR comment updated, owner={}, repo={}, commentId={}, bodyLength={}",
                owner, repo, commentId, body.length());
    }

    public GithubPullRequestDetail getPullRequestDetail(String owner, String repo, Integer pullNumber) {
        try {
            GithubPullRequestDetail detail = restClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{pullNumber}", owner, repo, pullNumber)
                    .headers(this::setAuthorization)
                    .retrieve()
                    .body(GithubPullRequestDetail.class);
            if (detail == null) {
                throw new BusinessException("GitHub PR detail response is empty");
            }
            log.info("GitHub PR detail fetched, owner={}, repo={}, pullNumber={}, headSha={}",
                    owner, repo, pullNumber, detail.getHeadSha());
            return detail;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new BusinessException("failed to get GitHub PR detail: " + exception.getMessage());
        }
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
                Map<String, Object> response = restClient.get()
                        .uri("/user")
                        .headers(this::setAuthorization)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {
                        });
                if (response == null || response.get("login") == null) {
                    return null;
                }
                authenticatedUserLogin = response.get("login").toString();
                log.info("GitHub authenticated user resolved, login={}", authenticatedUserLogin);
                return authenticatedUserLogin;
            } catch (RestClientException exception) {
                log.warn("failed to resolve GitHub authenticated user login, message={}", exception.getMessage());
                return null;
            }
        }
    }

    public String getFileContent(String owner, String repo, String path, String ref) {
        if (!StringUtils.hasText(path) || !StringUtils.hasText(ref)) {
            throw new BusinessException("failed to get GitHub file content: path and ref are required");
        }
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/contents/{path}")
                            .queryParam("ref", ref)
                            .build(owner, repo, path))
                    .headers(this::setAuthorization)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (response == null || response.get("content") == null) {
                return "";
            }
            String encoding = response.get("encoding") == null ? "" : response.get("encoding").toString();
            String content = response.get("content").toString().replaceAll("\\s+", "");
            if ("base64".equalsIgnoreCase(encoding)) {
                return new String(Base64.getDecoder().decode(content), java.nio.charset.StandardCharsets.UTF_8);
            }
            return content;
        } catch (RestClientException exception) {
            throw new BusinessException("failed to get GitHub file content: " + exception.getMessage());
        }
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

        try {
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
        } catch (RestClientException exception) {
            throw new BusinessException("failed to create GitHub PR inline comment: " + exception.getMessage());
        }

        log.info("GitHub PR inline comment created, owner={}, repo={}, pullNumber={}, path={}, line={}, side={}, bodyLength={}",
                owner, repo, pullNumber, path, line, side, body.length());
    }

    private List<GithubChangedFile> requestPullRequestFilesPage(
            String owner,
            String repo,
            Integer pullNumber,
            int page
    ) {
        List<GithubChangedFile> files = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/pulls/{pullNumber}/files")
                        .queryParam("per_page", PER_PAGE)
                        .queryParam("page", page)
                        .build(owner, repo, pullNumber))
                .headers(this::setAuthorization)
                .retrieve()
                .body(new ParameterizedTypeReference<List<GithubChangedFile>>() {
                });
        return files == null ? List.of() : files;
    }

    private List<GithubIssueComment> requestPullRequestCommentsPage(
            String owner,
            String repo,
            Integer pullNumber,
            int page
    ) {
        List<GithubIssueComment> comments = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/issues/{issueNumber}/comments")
                        .queryParam("per_page", PER_PAGE)
                        .queryParam("page", page)
                        .build(owner, repo, pullNumber))
                .headers(this::setAuthorization)
                .retrieve()
                .body(new ParameterizedTypeReference<List<GithubIssueComment>>() {
                });
        return comments == null ? List.of() : comments;
    }

    private void setAuthorization(HttpHeaders headers) {
        if (StringUtils.hasText(token)) {
            headers.setBearerAuth(token);
        }
    }
}
