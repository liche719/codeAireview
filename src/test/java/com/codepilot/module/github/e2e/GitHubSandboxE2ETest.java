package com.codepilot.module.github.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("sandbox-e2e")
class GitHubSandboxE2ETest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String DEFAULT_SUMMARY_MARKER = "<!-- codepilot-ai-review:liche719/codeAireview -->";

    private static final String DEFAULT_INLINE_MARKER = "<!-- codepilot-inline-review -->";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void shouldVerifyWebhookPublishingAndLinkedIssueInGitHubSandbox() throws Exception {
        SandboxConfig config = SandboxConfig.load();
        assumeTrue(config.enabled(), "Set CODEPILOT_GITHUB_SANDBOX_E2E_ENABLED=true to run GitHub sandbox E2E.");
        config.assertValid();

        Instant startedAt = Instant.now().minusSeconds(5);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", startedAt.toString());
        report.put("owner", config.owner());
        report.put("repo", config.repo());
        report.put("pullNumber", config.pullNumber());
        report.put("headSha", config.headSha());

        WebhookResult inlineRun = sendPullRequestWebhook(config, "opened");
        report.put("inlineTaskId", inlineRun.taskId());
        waitForTaskSuccess(config, inlineRun.taskId());
        JsonNode inlineComment = waitForInlineComment(config, startedAt);
        report.put("inlineCommentUrl", inlineComment.path("html_url").asText());
        report.put("inlineCommentPath", inlineComment.path("path").asText());
        report.put("inlineCommentLine", inlineComment.path("line").isMissingNode()
                ? null
                : inlineComment.path("line").asInt());

        JsonNode linkedIssues = waitForLinkedIssues(config, inlineRun.taskId());
        report.put("linkedIssueCount", linkedIssues.size());
        if (config.expectedLinkedIssueNumber() != null) {
            assertThat(numberValues(linkedIssues))
                    .contains(config.expectedLinkedIssueNumber());
        }

        WebhookResult summaryRun = sendIssueCommentReviewWebhook(config);
        report.put("summaryTaskId", summaryRun.taskId());
        waitForTaskSuccess(config, summaryRun.taskId());
        JsonNode summaryComment = waitForSummaryComment(config, startedAt);
        report.put("summaryCommentUrl", summaryComment.path("html_url").asText());
        report.put("summaryCommentUpdatedAt", summaryComment.path("updated_at").asText());

        Path reportPath = writeReport(report);
        System.out.println("GitHub sandbox E2E report: " + reportPath.toAbsolutePath());
    }

    private WebhookResult sendPullRequestWebhook(SandboxConfig config, String action) throws Exception {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("action", action);
        ObjectNode repository = root.putObject("repository");
        repository.put("name", config.repo());
        repository.putObject("owner").put("login", config.owner());
        ObjectNode pullRequest = root.putObject("pull_request");
        pullRequest.put("number", config.pullNumber());
        pullRequest.put("html_url", pullRequestUrl(config));
        pullRequest.put("title", config.pullTitle());
        pullRequest.putObject("head").put("sha", config.headSha());
        String payload = OBJECT_MAPPER.writeValueAsString(root);
        return sendWebhook(config, "pull_request", payload, true);
    }

    private WebhookResult sendIssueCommentReviewWebhook(SandboxConfig config) throws Exception {
        long commentId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("action", "created");
        ObjectNode repository = root.putObject("repository");
        repository.put("name", config.repo());
        repository.putObject("owner").put("login", config.owner());
        ObjectNode issue = root.putObject("issue");
        issue.put("number", config.pullNumber());
        issue.put("html_url", pullRequestUrl(config));
        issue.put("title", config.pullTitle());
        issue.putObject("pull_request")
                .put("url", "https://api.github.com/repos/%s/%s/pulls/%d".formatted(
                        config.owner(),
                        config.repo(),
                        config.pullNumber()
                ));
        ObjectNode comment = root.putObject("comment");
        comment.put("id", commentId);
        comment.put("body", "/review");
        comment.put("author_association", "OWNER");
        comment.putObject("user").put("login", config.commandUserLogin());
        String payload = OBJECT_MAPPER.writeValueAsString(root);
        return sendWebhook(config, "issue_comment", payload, false);
    }

    private WebhookResult sendWebhook(
            SandboxConfig config,
            String event,
            String payload,
            boolean retryDuplicatePrEvent
    ) throws Exception {
        WebhookResult result = sendWebhookOnce(config, event, payload);
        if (result.ignored() && retryDuplicatePrEvent && result.reason().toLowerCase(Locale.ROOT).contains("duplicate")) {
            Thread.sleep(Duration.ofSeconds(31).toMillis());
            return sendWebhookOnce(config, event, payload);
        }
        assertThat(result.ignored())
                .as("webhook should be processed, reason=%s", result.reason())
                .isFalse();
        assertThat(result.taskId())
                .as("webhook response should contain a task id")
                .isNotNull();
        return result;
    }

    private WebhookResult sendWebhookOnce(SandboxConfig config, String event, String payload) throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(endpoint(config.appBaseUrl(), "/api/github/webhook"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header("X-GitHub-Event", event)
                        .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                        .header("X-Hub-Signature-256", signature(config.webhookSecret(), payload))
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(response.statusCode()).isBetween(200, 299);
        JsonNode root = OBJECT_MAPPER.readTree(response.body());
        assertThat(root.path("code").asInt()).isEqualTo(0);
        JsonNode data = root.path("data");
        Long taskId = data.path("taskId").isMissingNode() || data.path("taskId").isNull()
                ? null
                : data.path("taskId").asLong();
        String reason = data.path("reason").asText("");
        return new WebhookResult(taskId, root.path("message").asText(), reason);
    }

    private JsonNode waitForTaskSuccess(SandboxConfig config, long taskId) throws Exception {
        return waitFor("review task " + taskId + " success", config, () -> {
            JsonNode root = sendAppGet(config, "/api/reviews/" + taskId);
            JsonNode data = root.path("data");
            String status = data.path("status").asText("");
            if ("FAILED".equalsIgnoreCase(status)) {
                throw new AssertionError("review task failed: " + data.path("errorMessage").asText(""));
            }
            return "SUCCESS".equalsIgnoreCase(status) ? data : null;
        });
    }

    private JsonNode waitForLinkedIssues(SandboxConfig config, long taskId) throws Exception {
        return waitFor("linked issues for task " + taskId, config, () -> {
            JsonNode root = sendAppGet(config, "/api/reviews/" + taskId + "/linked-issues");
            JsonNode data = root.path("data");
            return data.isArray() ? data : null;
        });
    }

    private JsonNode waitForSummaryComment(SandboxConfig config, Instant startedAt) throws Exception {
        return waitFor("summary comment", config, () -> {
            JsonNode comments = sendGitHubGet(config, "/repos/" + config.owner() + "/" + config.repo()
                    + "/issues/" + config.pullNumber() + "/comments?per_page=100");
            for (JsonNode comment : comments) {
                if (comment.path("body").asText("").contains(config.summaryMarker())
                        && isAtOrAfter(comment.path("updated_at").asText(""), startedAt)) {
                    return comment;
                }
            }
            return null;
        });
    }

    private JsonNode waitForInlineComment(SandboxConfig config, Instant startedAt) throws Exception {
        return waitFor("inline comment", config, () -> {
            JsonNode comments = sendGitHubGet(config, "/repos/" + config.owner() + "/" + config.repo()
                    + "/pulls/" + config.pullNumber() + "/comments?per_page=100");
            for (JsonNode comment : comments) {
                if (!comment.path("body").asText("").contains(config.inlineMarker())) {
                    continue;
                }
                if (!isAtOrAfter(comment.path("created_at").asText(""), startedAt)) {
                    continue;
                }
                if (hasText(config.expectedInlinePath())
                        && !config.expectedInlinePath().equals(comment.path("path").asText())) {
                    continue;
                }
                if (config.expectedInlineLine() != null
                        && config.expectedInlineLine() != comment.path("line").asInt(-1)) {
                    continue;
                }
                return comment;
            }
            return null;
        });
    }

    private JsonNode sendAppGet(SandboxConfig config, String path) throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(endpoint(config.appBaseUrl(), path))
                        .timeout(Duration.ofSeconds(30))
                        .header("X-CodePilot-Api-Key", config.apiKey())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(response.statusCode()).isBetween(200, 299);
        JsonNode root = OBJECT_MAPPER.readTree(response.body());
        assertThat(root.path("code").asInt()).isEqualTo(0);
        return root;
    }

    private JsonNode sendGitHubGet(SandboxConfig config, String pathAndQuery) throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("https://api.github.com" + pathAndQuery))
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/vnd.github+json")
                        .header("Authorization", "Bearer " + config.githubToken())
                        .header("User-Agent", "codepilot-sandbox-e2e")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(response.statusCode()).isBetween(200, 299);
        return OBJECT_MAPPER.readTree(response.body());
    }

    private <T> T waitFor(String description, SandboxConfig config, ThrowingSupplier<T> supplier) throws Exception {
        Instant deadline = Instant.now().plus(config.timeout());
        Throwable lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                T value = supplier.get();
                if (value != null) {
                    return value;
                }
            } catch (AssertionError | Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(config.pollInterval().toMillis());
        }
        AssertionError timeout = new AssertionError("Timed out waiting for " + description);
        if (lastFailure != null) {
            timeout.initCause(lastFailure);
        }
        throw timeout;
    }

    private Path writeReport(Map<String, Object> report) throws Exception {
        Path directory = Path.of("target", "github-sandbox-e2e");
        Files.createDirectories(directory);
        Path path = directory.resolve("result-" + Instant.now().toString().replace(':', '-') + ".json");
        Files.writeString(path, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        return path;
    }

    private List<Integer> numberValues(JsonNode array) {
        List<Integer> numbers = new ArrayList<>();
        array.forEach(node -> numbers.add(node.path("number").asInt()));
        return numbers;
    }

    private URI endpoint(URI baseUri, String path) {
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private String signature(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder("sha256=");
        for (byte value : digest) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private boolean isAtOrAfter(String timestamp, Instant boundary) {
        if (!hasText(timestamp)) {
            return false;
        }
        return !Instant.parse(timestamp).isBefore(boundary);
    }

    private String pullRequestUrl(SandboxConfig config) {
        return "https://github.com/%s/%s/pull/%d".formatted(
                config.owner(),
                config.repo(),
                config.pullNumber()
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record WebhookResult(Long taskId, String message, String reason) {
        boolean ignored() {
            return hasText(reason) || "ignored".equalsIgnoreCase(message);
        }
    }

    private record SandboxConfig(
            boolean enabled,
            URI appBaseUrl,
            String owner,
            String repo,
            int pullNumber,
            String headSha,
            String pullTitle,
            String githubToken,
            String webhookSecret,
            String apiKey,
            String commandUserLogin,
            String expectedInlinePath,
            Integer expectedInlineLine,
            Integer expectedLinkedIssueNumber,
            String summaryMarker,
            String inlineMarker,
            Duration timeout,
            Duration pollInterval
    ) {

        static SandboxConfig load() {
            return new SandboxConfig(
                    bool("CODEPILOT_GITHUB_SANDBOX_E2E_ENABLED", "codepilot.github.sandbox.e2e.enabled"),
                    uri("CODEPILOT_GITHUB_SANDBOX_APP_BASE_URL", "codepilot.github.sandbox.app-base-url"),
                    value("CODEPILOT_GITHUB_SANDBOX_OWNER", "codepilot.github.sandbox.owner"),
                    value("CODEPILOT_GITHUB_SANDBOX_REPO", "codepilot.github.sandbox.repo"),
                    intValue("CODEPILOT_GITHUB_SANDBOX_PULL_NUMBER", "codepilot.github.sandbox.pull-number", 0),
                    value("CODEPILOT_GITHUB_SANDBOX_HEAD_SHA", "codepilot.github.sandbox.head-sha"),
                    valueOrDefault("CODEPILOT_GITHUB_SANDBOX_PULL_TITLE", "codepilot.github.sandbox.pull-title", "CodePilot sandbox PR"),
                    firstValue(
                            value("CODEPILOT_GITHUB_SANDBOX_TOKEN", "codepilot.github.sandbox.token"),
                            value("CODEPILOT_GITHUB_TOKEN", "codepilot.github.token"),
                            value("GITHUB_TOKEN", "github.token")
                    ),
                    firstValue(
                            value("CODEPILOT_GITHUB_SANDBOX_WEBHOOK_SECRET", "codepilot.github.sandbox.webhook-secret"),
                            value("CODEPILOT_GITHUB_WEBHOOK_SECRET", "codepilot.github.webhook-secret")
                    ),
                    value("CODEPILOT_API_AUTH_API_KEY", "codepilot.api-auth.api-key"),
                    valueOrDefault("CODEPILOT_GITHUB_SANDBOX_COMMAND_USER_LOGIN", "codepilot.github.sandbox.command-user-login", "sandbox-user"),
                    value("CODEPILOT_GITHUB_SANDBOX_EXPECTED_INLINE_PATH", "codepilot.github.sandbox.expected-inline-path"),
                    optionalInt("CODEPILOT_GITHUB_SANDBOX_EXPECTED_INLINE_LINE", "codepilot.github.sandbox.expected-inline-line"),
                    optionalInt("CODEPILOT_GITHUB_SANDBOX_EXPECTED_LINKED_ISSUE_NUMBER", "codepilot.github.sandbox.expected-linked-issue-number"),
                    valueOrDefault("CODEPILOT_GITHUB_SANDBOX_SUMMARY_MARKER", "codepilot.github.sandbox.summary-marker", DEFAULT_SUMMARY_MARKER),
                    valueOrDefault("CODEPILOT_GITHUB_SANDBOX_INLINE_MARKER", "codepilot.github.sandbox.inline-marker", DEFAULT_INLINE_MARKER),
                    Duration.ofSeconds(intValue("CODEPILOT_GITHUB_SANDBOX_TIMEOUT_SECONDS", "codepilot.github.sandbox.timeout-seconds", 300)),
                    Duration.ofSeconds(intValue("CODEPILOT_GITHUB_SANDBOX_POLL_INTERVAL_SECONDS", "codepilot.github.sandbox.poll-interval-seconds", 5))
            );
        }

        void assertValid() {
            assertThat(appBaseUrl).as("sandbox app base URL").isNotNull();
            assertThat(owner).as("sandbox owner").isNotBlank();
            assertThat(repo).as("sandbox repo").isNotBlank();
            assertThat(pullNumber).as("sandbox pull number").isPositive();
            assertThat(headSha).as("sandbox head sha").isNotBlank();
            assertThat(githubToken).as("GitHub token").isNotBlank();
            assertThat(webhookSecret).as("webhook secret").isNotBlank();
            assertThat(apiKey).as("CodePilot API key").isNotBlank();
        }

        private static URI uri(String envName, String propertyName) {
            String value = value(envName, propertyName);
            return hasText(value) ? URI.create(value.trim()) : null;
        }

        private static boolean bool(String envName, String propertyName) {
            return Boolean.parseBoolean(valueOrDefault(envName, propertyName, "false"));
        }

        private static int intValue(String envName, String propertyName, int defaultValue) {
            String value = value(envName, propertyName);
            return hasText(value) ? Integer.parseInt(value.trim()) : defaultValue;
        }

        private static Integer optionalInt(String envName, String propertyName) {
            String value = value(envName, propertyName);
            return hasText(value) ? Integer.parseInt(value.trim()) : null;
        }

        private static String valueOrDefault(String envName, String propertyName, String defaultValue) {
            String value = value(envName, propertyName);
            return hasText(value) ? value : defaultValue;
        }

        private static String firstValue(String... values) {
            for (String value : values) {
                if (hasText(value)) {
                    return value;
                }
            }
            return null;
        }

        private static String value(String envName, String propertyName) {
            String property = System.getProperty(propertyName);
            if (hasText(property)) {
                return property.trim();
            }
            String env = System.getenv(envName);
            return hasText(env) ? env.trim() : null;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
