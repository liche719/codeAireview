package com.codepilot.module.git.client;

import com.codepilot.module.git.dto.GithubChangedFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class GithubClient {

    private static final int PER_PAGE = 100;

    private final RestClient restClient;

    private final String token;

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
                .headers(headers -> {
                    if (token != null && !token.isBlank()) {
                        headers.setBearerAuth(token);
                    }
                })
                .retrieve()
                .body(new ParameterizedTypeReference<List<GithubChangedFile>>() {
                });
        return files == null ? List.of() : files;
    }
}
