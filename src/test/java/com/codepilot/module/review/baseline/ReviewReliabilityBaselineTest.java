package com.codepilot.module.review.baseline;

import com.codepilot.common.security.FixedWindowRateLimiter;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.planner.ReviewFilePlanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewReliabilityBaselineTest {

    private static final Path REPORT_PATH = Path.of(
            "target",
            "codepilot-baseline",
            "review-reliability-baseline.json"
    );

    @Test
    void shouldGenerateLocalReliabilityBaselineReport() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        report.put("scenarios", List.of(
                concurrentRateLimitBaseline(),
                largePullRequestPlannerBaseline()
        ));

        Files.createDirectories(REPORT_PATH.getParent());
        new ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(REPORT_PATH.toFile(), report);

        assertThat(REPORT_PATH).exists();
    }

    private Map<String, Object> concurrentRateLimitBaseline() throws Exception {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(
                Clock.fixed(Instant.parse("2026-06-22T12:00:00Z"), ZoneOffset.UTC)
        );
        int requestCount = 500;
        int maxRequests = 120;
        int workerThreads = 32;
        ExecutorService executor = Executors.newFixedThreadPool(workerThreads);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> results = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(ignored -> executor.submit(() -> {
                        start.await();
                        return limiter.tryConsume(
                                "webhook-ip:127.0.0.1",
                                maxRequests,
                                Duration.ofMinutes(1)
                        ).allowed();
                    }))
                    .toList();

            long startedAt = System.nanoTime();
            start.countDown();

            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            long limited = requestCount - allowed;

            assertThat(allowed).isEqualTo(maxRequests);
            assertThat(limited).isEqualTo(requestCount - maxRequests);

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("name", "concurrent-rate-limit");
            metrics.put("requestCount", requestCount);
            metrics.put("workerThreads", workerThreads);
            metrics.put("maxRequestsPerWindow", maxRequests);
            metrics.put("allowedRequests", allowed);
            metrics.put("rateLimitedRequests", limited);
            metrics.put("elapsedMillis", elapsedMillis);
            return metrics;
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<String, Object> largePullRequestPlannerBaseline() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMaxFilesPerTask(5);
        properties.setMaxPatchCharsPerFile(1000);
        properties.setMaxTotalPatchChars(5000);
        ReviewFilePlanner planner = new ReviewFilePlanner(properties);

        List<ReviewFile> plannedFiles = planner.plan(9001L, largePullRequestFixture());
        List<String> selectedPaths = plannedFiles.stream()
                .filter(reviewFile -> !Boolean.TRUE.equals(reviewFile.getSkipped()))
                .map(ReviewFile::getFilePath)
                .toList();
        long skipped = plannedFiles.stream()
                .filter(reviewFile -> Boolean.TRUE.equals(reviewFile.getSkipped()))
                .count();

        assertThat(plannedFiles).hasSize(200);
        assertThat(selectedPaths).containsExactlyInAnyOrder(
                "src/main/java/com/example/security/AuthService.java",
                "src/main/resources/db/migration/V2__payment.sql",
                "src/main/java/com/example/controller/UserController.java",
                "src/main/resources/application.yml",
                "src/main/java/com/example/service/PaymentService.java"
        );
        assertThat(skipped).isEqualTo(195);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("name", "large-pr-planner");
        metrics.put("changedFiles", plannedFiles.size());
        metrics.put("reviewBudgetFiles", properties.getMaxFilesPerTask());
        metrics.put("reviewedFiles", selectedPaths.size());
        metrics.put("skippedFiles", skipped);
        metrics.put("selectedPaths", selectedPaths);
        return metrics;
    }

    private List<GithubChangedFile> largePullRequestFixture() {
        List<GithubChangedFile> changedFiles = new ArrayList<>();
        changedFiles.add(changedFile(
                "docs/guide-0.md",
                "+revise usage notes"
        ));
        changedFiles.add(changedFile(
                "dist/app.bundle.js",
                "+minified bundle output"
        ));
        changedFiles.add(changedFile(
                "frontend/package-lock.json",
                "+lockfile update"
        ));
        changedFiles.add(changedFile(
                "src/main/java/com/example/service/NormalService0.java",
                "+void run0() {}"
        ));
        changedFiles.add(changedFile(
                "src/main/java/com/example/security/AuthService.java",
                "+String token = request.getHeader(\"Authorization\");\n+boolean permission = verify(token);"
        ));
        changedFiles.add(changedFile(
                "src/main/resources/db/migration/V2__payment.sql",
                "+alter table payment add column risk_level varchar(16);\n+update payment set risk_level = 'LOW';"
        ));
        changedFiles.add(changedFile(
                "src/main/java/com/example/controller/UserController.java",
                "+@PostMapping(\"/users\")\n+public UserResponse create(UserRequest request) { return service.create(request); }"
        ));
        changedFiles.add(changedFile(
                "src/main/resources/application.yml",
                "+security:\n+  oauth-token-ttl: 300"
        ));
        changedFiles.add(changedFile(
                "src/main/java/com/example/service/PaymentService.java",
                "+public void settle(Payment payment) {\n+    permissionService.assertCanCharge(payment);\n+    gateway.charge(payment);\n+}"
        ));

        for (int i = 1; i < 92; i++) {
            changedFiles.add(changedFile(
                    "src/main/java/com/example/service/NormalService" + i + ".java",
                    "+void run" + i + "() {}"
            ));
        }
        for (int i = 1; i < 51; i++) {
            changedFiles.add(changedFile(
                    "docs/guide-" + i + ".md",
                    "+revise section " + i
            ));
        }
        for (int i = 0; i < 30; i++) {
            changedFiles.add(changedFile(
                    "src/test/java/com/example/NormalService" + i + "Test.java",
                    "+@Test void covers" + i + "() {}"
            ));
        }
        for (int i = 0; i < 20; i++) {
            changedFiles.add(changedFile(
                    "build/generated/source-" + i + ".java",
                    "+generated content " + i
            ));
        }
        return changedFiles;
    }

    private GithubChangedFile changedFile(String filename, String patch) {
        GithubChangedFile changedFile = new GithubChangedFile();
        changedFile.setFilename(filename);
        changedFile.setStatus("modified");
        changedFile.setPatch(patch);
        changedFile.setAdditions(1);
        changedFile.setDeletions(0);
        return changedFile;
    }
}
