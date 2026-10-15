package com.codepilot.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class SearxngSearchService {

    private final SearxngProperties properties;

    private final ObjectMapper objectMapper;

    public SearxngSearchService(SearxngProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String search(String query) {
        if (properties == null || !properties.isEnabled() || !StringUtils.hasText(properties.getBaseUrl())) {
            return "Web search is unavailable.";
        }
        String normalizedQuery = normalizeQuery(query);
        if (!StringUtils.hasText(normalizedQuery)) {
            return "Web search query must not be blank.";
        }
        try {
            JsonNode response = client().get()
                    .uri(uriBuilder -> uriBuilder.path("/search")
                            .queryParam("q", applyDomainRestriction(normalizedQuery))
                            .queryParam("format", "json")
                            .queryParam("categories", "general")
                            .queryParam("language", "auto")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            return formatResponse(response);
        } catch (RestClientException | IllegalArgumentException exception) {
            log.warn("SearXNG search failed, queryLength={}, errorType={}, message={}",
                    normalizedQuery.length(), exception.getClass().getSimpleName(), exception.getMessage());
            return "Web search failed temporarily.";
        }
    }

    private RestClient client() {
        Duration timeout = properties.getTimeout() == null
                || properties.getTimeout().isNegative()
                || properties.getTimeout().isZero()
                ? Duration.ofSeconds(5)
                : properties.getTimeout();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getBaseUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    private String formatResponse(JsonNode response) {
        if (response == null || !response.path("results").isArray()) {
            return "No web search results were found.";
        }
        int maxResults = Math.max(1, Math.min(properties.getMaxResults(), 10));
        int maxChars = Math.max(200, properties.getMaxResultChars());
        List<String> formatted = new ArrayList<>();
        for (JsonNode result : response.path("results")) {
            if (formatted.size() >= maxResults) {
                break;
            }
            String title = clean(result.path("title").asText("Untitled"), 240);
            String url = clean(result.path("url").asText(""), 500);
            String content = clean(result.path("content").asText(""), maxChars);
            if (!StringUtils.hasText(url) || !isAllowedUrl(url)) {
                continue;
            }
            formatted.add("- title=" + title + "\n  url=" + url + "\n  snippet=" + content);
        }
        if (formatted.isEmpty()) {
            return "No allowed web search results were found.";
        }
        return "Web search results (external, untrusted reference only):\n" + String.join("\n", formatted);
    }

    private String normalizeQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return "";
        }
        int maxChars = Math.max(20, properties.getMaxQueryChars());
        String normalized = query.replaceAll("[\\u0000-\\u001F\\u007F]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.substring(0, Math.min(normalized.length(), maxChars));
    }

    private String applyDomainRestriction(String query) {
        List<String> domains = properties.getAllowedDomains() == null ? List.of() : properties.getAllowedDomains();
        String restriction = domains.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(domain -> domain.matches("[a-zA-Z0-9.-]+"))
                .map(domain -> "site:" + domain)
                .reduce((left, right) -> left + " OR " + right)
                .orElse("");
        return StringUtils.hasText(restriction) ? query + " (" + restriction + ")" : query;
    }

    private boolean isAllowedUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && StringUtils.hasText(uri.getHost())
                    && (properties.getAllowedDomains() == null || properties.getAllowedDomains().isEmpty()
                    || properties.getAllowedDomains().stream().anyMatch(domain -> uri.getHost().equalsIgnoreCase(domain)
                    || uri.getHost().toLowerCase(Locale.ROOT).endsWith("." + domain.toLowerCase(Locale.ROOT))));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String clean(String value, int maxChars) {
        if (!StringUtils.hasText(value)) {
            return "N/A";
        }
        String normalized = value.replaceAll("[\\u0000-\\u001F\\u007F]", " ").replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), maxChars));
    }

    private String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
