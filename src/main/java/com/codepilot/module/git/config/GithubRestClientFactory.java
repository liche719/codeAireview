package com.codepilot.module.git.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class GithubRestClientFactory {

    private GithubRestClientFactory() {
    }

    public static RestClient create(GithubProperties properties) {
        GithubProperties safeProperties = properties == null ? new GithubProperties() : properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(safeProperties.getConnectTimeout());
        requestFactory.setReadTimeout(safeProperties.getReadTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(safeProperties.getApiBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }
}
