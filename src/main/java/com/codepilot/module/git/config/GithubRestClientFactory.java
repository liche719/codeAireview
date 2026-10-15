package com.codepilot.module.git.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

public final class GithubRestClientFactory {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    private GithubRestClientFactory() {
    }

    public static RestClient create(GithubProperties properties) {
        GithubProperties safeProperties = properties == null ? new GithubProperties() : properties;

        Duration connectTimeout = positiveOr(safeProperties.getConnectTimeout(), DEFAULT_CONNECT_TIMEOUT);
        Duration readTimeout = positiveOr(safeProperties.getReadTimeout(), DEFAULT_READ_TIMEOUT);

        // 必须使用 JdkClientHttpRequestFactory，不能用 SimpleClientHttpRequestFactory。
        //
        // SimpleClientHttpRequestFactory 底层是 java.net.HttpURLConnection，它**不支持 PATCH**，
        // 会抛 ProtocolException("Invalid HTTP method: PATCH")。而 GitHub 编辑 issue 评论用的正是
        // PATCH /repos/{owner}/{repo}/issues/comments/{id}，于是：
        //   - Summary Comment 第一次能创建，之后**永远无法更新**
        //   - publish_status 进入 FAILED 并重试到 FAILED_FINAL
        // 也就是"marker 幂等更新"这个能力实际是坏的。
        //
        // JDK 自带的 java.net.http.HttpClient 支持 PATCH，因此改用它。
        // 项目内 SearxngSearchService 也一直用的是 JdkClientHttpRequestFactory。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(safeProperties.getApiBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
