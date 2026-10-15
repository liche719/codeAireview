package com.codepilot.module.git.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保证 GitHub 客户端真的支持 PATCH。
 *
 * <p>背景：GitHub 编辑 issue 评论用的是 {@code PATCH /repos/{owner}/{repo}/issues/comments/{id}}，
 * 而审查摘要的幂等更新正是走这条路径。</p>
 *
 * <p>{@code SimpleClientHttpRequestFactory} 底层是 {@code java.net.HttpURLConnection}，
 * <b>不支持 PATCH</b>，会抛 {@code ProtocolException: Invalid HTTP method: PATCH}。
 * 一旦工厂退回成它，摘要评论就变成"只能创建、永远无法更新"，
 * 并且会重试到 {@code FAILED_FINAL}——详见 docs/roadmap.md 的 F1。</p>
 *
 * <p>现有测试全部使用 mock，没有任何一个真的发出过 PATCH，因此这个缺陷此前无法被测试发现。
 * 本测试真的发一次 PATCH，是这条链路上唯一的防线。</p>
 */
class GithubRestClientFactoryTest {

    private HttpServer server;

    private final AtomicReference<String> receivedMethod = new AtomicReference<>();

    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            try (InputStream in = exchange.getRequestBody()) {
                receivedBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] response = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSupportPatchRequests() {
        RestClient client = GithubRestClientFactory.create(properties());

        String response = client.patch()
                .uri("/repos/owner/repo/issues/comments/1")
                .body("{\"body\":\"updated\"}")
                .retrieve()
                .body(String.class);

        assertThat(receivedMethod.get())
                .as("the GitHub client must be able to issue PATCH; SimpleClientHttpRequestFactory cannot")
                .isEqualTo("PATCH");
        assertThat(receivedBody.get()).contains("updated");
        assertThat(response).contains("id");
    }

    @Test
    void shouldStillSupportGetAndPost() {
        RestClient client = GithubRestClientFactory.create(properties());

        client.get().uri("/repos/owner/repo").retrieve().body(String.class);
        assertThat(receivedMethod.get()).isEqualTo("GET");

        client.post().uri("/repos/owner/repo/issues").body("{}").retrieve().body(String.class);
        assertThat(receivedMethod.get()).isEqualTo("POST");
    }

    private GithubProperties properties() {
        GithubProperties properties = new GithubProperties();
        properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofSeconds(5));
        return properties;
    }
}
