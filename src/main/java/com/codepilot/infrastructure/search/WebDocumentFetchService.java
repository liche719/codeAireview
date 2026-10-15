package com.codepilot.infrastructure.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class WebDocumentFetchService {

    private final SearxngProperties properties;

    public String fetch(String url) {
        if (!properties.isDocumentFetchEnabled()) {
            return "Web document fetching is disabled by server configuration.";
        }
        URI uri = validateUri(url);
        validateResolvedAddresses(uri.getHost());
        Duration timeout = safeTimeout();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "text/html,text/plain,application/xhtml+xml")
                .header("User-Agent", "CodePilot-WebResearch/1.0")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "Web document fetch failed with HTTP status " + response.statusCode() + ".";
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("text/html") && !contentType.startsWith("text/plain")
                    && !contentType.startsWith("application/xhtml+xml")) {
                return "Web document content type is not allowed.";
            }
            int maxBytes = Math.max(1024, properties.getDocumentFetchMaxBytes());
            byte[] content;
            try (InputStream input = response.body()) {
                content = input.readNBytes(maxBytes + 1);
            }
            if (content.length > maxBytes) {
                return "Web document exceeded the configured response size limit.";
            }
            String text = extractText(new String(content, StandardCharsets.UTF_8));
            int maxChars = Math.max(500, properties.getDocumentFetchMaxChars());
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars);
            }
            return "Web document (external, untrusted reference only):\nsource=" + uri + "\ncontent=" + text;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "Web document fetch was interrupted.";
        } catch (Exception exception) {
            return "Web document fetch failed temporarily.";
        }
    }

    private URI validateUri(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Web document URL is required");
        }
        URI uri = URI.create(value.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())
                || uri.getUserInfo() != null || uri.getPort() != -1) {
            throw new IllegalArgumentException("Only standard-port HTTPS web document URLs are allowed");
        }
        List<String> domains = properties.getAllowedDomains();
        if (domains == null || domains.isEmpty() || domains.stream().noneMatch(domain -> hostMatches(uri.getHost(), domain))) {
            throw new IllegalArgumentException("Web document host is not in the configured domain allowlist");
        }
        return uri;
    }

    private void validateResolvedAddresses(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("Web document host resolved to a non-public address");
                }
                byte[] bytes = address.getAddress();
                if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
                    throw new IllegalArgumentException("Web document host resolved to a private IPv6 address");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Web document host could not be resolved", exception);
        }
    }

    private boolean hostMatches(String host, String domain) {
        if (!StringUtils.hasText(domain) || !domain.trim().matches("[a-zA-Z0-9.-]+")) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        String normalizedDomain = domain.trim().toLowerCase(Locale.ROOT);
        return normalizedHost.equals(normalizedDomain) || normalizedHost.endsWith("." + normalizedDomain);
    }

    private Duration safeTimeout() {
        Duration configured = properties.getDocumentFetchTimeout();
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofSeconds(8) : configured;
    }

    private String extractText(String body) {
        return body.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replaceAll("[\\u0000-\\u001F\\u007F]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
