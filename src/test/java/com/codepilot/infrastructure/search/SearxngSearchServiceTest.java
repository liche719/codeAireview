package com.codepilot.infrastructure.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearxngSearchServiceTest {

    @Test
    void shouldStayOfflineWhenSearchIsDisabled() {
        SearxngProperties properties = new SearxngProperties();

        String result = new SearxngSearchService(properties, new ObjectMapper()).search("Spring Boot 3");

        assertThat(result).isEqualTo("Web search is unavailable.");
    }

    @Test
    void shouldReturnOnlyAllowedHttpsSearchResults() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            String json = """
                    {"results":[
                      {"title":"Spring Docs","url":"https://docs.spring.io/spring-boot/index.html","content":"Official reference"},
                      {"title":"Other","url":"https://example.com/post","content":"Untrusted mirror"}
                    ]}
                    """;
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            SearxngProperties properties = new SearxngProperties();
            properties.setEnabled(true);
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setTimeout(Duration.ofSeconds(2));
            properties.setAllowedDomains(List.of("docs.spring.io"));

            String result = new SearxngSearchService(properties, new ObjectMapper()).search("Spring Boot reference");

            assertThat(result)
                    .contains("Spring Docs")
                    .contains("https://docs.spring.io/spring-boot/index.html")
                    .doesNotContain("example.com");
        } finally {
            server.stop(0);
        }
    }
}
