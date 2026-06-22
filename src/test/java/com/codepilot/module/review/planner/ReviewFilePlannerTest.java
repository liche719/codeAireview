package com.codepilot.module.review.planner;

import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewFilePlannerTest {

    @Test
    void shouldMapReviewableChangedFile() {
        ReviewFilePlanner planner = new ReviewFilePlanner(new ReviewProperties());

        List<ReviewFile> reviewFiles = planner.plan(7L, List.of(changedFile("src/main/java/Demo.java", "modified", "+line")));

        assertThat(reviewFiles).hasSize(1);
        ReviewFile reviewFile = reviewFiles.getFirst();
        assertThat(reviewFile.getTaskId()).isEqualTo(7L);
        assertThat(reviewFile.getFilePath()).isEqualTo("src/main/java/Demo.java");
        assertThat(reviewFile.getChangeType()).isEqualTo("modified");
        assertThat(reviewFile.getSkipped()).isFalse();
        assertThat(reviewFile.getSkipReason()).isNull();
        assertThat(reviewFile.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldSkipEmptyPatchAndGeneratedPaths() {
        ReviewFilePlanner planner = new ReviewFilePlanner(new ReviewProperties());

        List<ReviewFile> reviewFiles = planner.plan(1L, List.of(
                changedFile("src/main/java/Binary.class", "modified", null),
                changedFile("dist/app.js", "modified", "+bundle"),
                changedFile("frontend/package-lock.json", "modified", "+lock")
        ));

        assertThat(reviewFiles).extracting(ReviewFile::getSkipped).containsExactly(true, true, true);
        assertThat(reviewFiles).extracting(ReviewFile::getSkipReason).containsExactly(
                "patch is empty or file is binary/too large",
                "file type or generated path skipped",
                "file type or generated path skipped"
        );
    }

    @Test
    void shouldApplyFileAndPatchLimitsInOrder() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMaxFilesPerTask(1);
        properties.setMaxPatchCharsPerFile(5);
        properties.setMaxTotalPatchChars(10);
        ReviewFilePlanner planner = new ReviewFilePlanner(properties);

        List<ReviewFile> reviewFiles = planner.plan(1L, List.of(
                changedFile("src/A.java", "modified", "12345"),
                changedFile("src/B.java", "modified", "123456"),
                changedFile("src/C.java", "modified", "1234")
        ));

        assertThat(reviewFiles).extracting(ReviewFile::getSkipped).containsExactly(false, true, true);
        assertThat(reviewFiles).extracting(ReviewFile::getSkipReason).containsExactly(
                null,
                "patch exceeds per-file review limit",
                "review file count limit exceeded"
        );
    }

    @Test
    void shouldApplyTotalPatchLimit() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMaxFilesPerTask(10);
        properties.setMaxPatchCharsPerFile(10);
        properties.setMaxTotalPatchChars(6);
        ReviewFilePlanner planner = new ReviewFilePlanner(properties);

        List<ReviewFile> reviewFiles = planner.plan(1L, List.of(
                changedFile("src/A.java", "modified", "1234"),
                changedFile("src/B.java", "modified", "1234")
        ));

        assertThat(reviewFiles).extracting(ReviewFile::getSkipped).containsExactly(false, true);
        assertThat(reviewFiles.get(1).getSkipReason()).isEqualTo("review total patch length limit exceeded");
    }

    @Test
    void shouldPrioritizeHighImpactFilesWhenFileBudgetIsLimited() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMaxFilesPerTask(1);
        ReviewFilePlanner planner = new ReviewFilePlanner(properties);

        List<ReviewFile> reviewFiles = planner.plan(1L, List.of(
                changedFile("README.md", "modified", "+docs"),
                changedFile("src/main/java/com/example/security/AuthService.java", "modified", "+boolean allow = true;")
        ));

        assertThat(reviewFiles).extracting(ReviewFile::getFilePath)
                .containsExactly(
                        "README.md",
                        "src/main/java/com/example/security/AuthService.java"
                );
        assertThat(reviewFiles).extracting(ReviewFile::getSkipped).containsExactly(true, false);
        assertThat(reviewFiles.getFirst().getSkipReason()).isEqualTo("review file count limit exceeded");
    }

    @Test
    void shouldPrioritizeHighImpactFilesWhenTotalPatchBudgetIsLimited() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMaxFilesPerTask(10);
        properties.setMaxPatchCharsPerFile(20);
        properties.setMaxTotalPatchChars(10);
        ReviewFilePlanner planner = new ReviewFilePlanner(properties);

        List<ReviewFile> reviewFiles = planner.plan(1L, List.of(
                changedFile("docs/usage.md", "modified", "123456"),
                changedFile("src/main/resources/db/migration/V2__auth.sql", "modified", "123456")
        ));

        assertThat(reviewFiles).extracting(ReviewFile::getSkipped).containsExactly(true, false);
        assertThat(reviewFiles.getFirst().getSkipReason()).isEqualTo("review total patch length limit exceeded");
    }

    @Test
    void shouldKeepLargePullRequestReviewWithinBudgetAndSelectHighestRiskFiles() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMaxFilesPerTask(5);
        properties.setMaxPatchCharsPerFile(1000);
        properties.setMaxTotalPatchChars(5000);
        ReviewFilePlanner planner = new ReviewFilePlanner(properties);

        List<GithubChangedFile> changedFiles = largePullRequestFixture();

        List<ReviewFile> reviewFiles = planner.plan(42L, changedFiles);

        List<ReviewFile> selectedFiles = reviewFiles.stream()
                .filter(reviewFile -> !Boolean.TRUE.equals(reviewFile.getSkipped()))
                .toList();
        List<String> selectedPaths = selectedFiles.stream()
                .map(ReviewFile::getFilePath)
                .toList();

        assertThat(reviewFiles).hasSize(200);
        assertThat(selectedFiles).hasSize(5);
        assertThat(selectedPaths).containsExactlyInAnyOrder(
                "src/main/java/com/example/security/AuthService.java",
                "src/main/resources/db/migration/V2__payment.sql",
                "src/main/java/com/example/controller/UserController.java",
                "src/main/resources/application.yml",
                "src/main/java/com/example/service/PaymentService.java"
        );
        assertThat(reviewFiles)
                .filteredOn(reviewFile -> "dist/app.bundle.js".equals(reviewFile.getFilePath()))
                .singleElement()
                .extracting(ReviewFile::getSkipReason)
                .isEqualTo("file type or generated path skipped");
        assertThat(reviewFiles)
                .filteredOn(reviewFile -> "frontend/package-lock.json".equals(reviewFile.getFilePath()))
                .singleElement()
                .extracting(ReviewFile::getSkipReason)
                .isEqualTo("file type or generated path skipped");
        assertThat(reviewFiles)
                .filteredOn(reviewFile -> "docs/guide-0.md".equals(reviewFile.getFilePath()))
                .singleElement()
                .extracting(ReviewFile::getSkipReason)
                .isEqualTo("review file count limit exceeded");
        assertThat(reviewFiles)
                .filteredOn(reviewFile -> "src/main/java/com/example/service/NormalService0.java".equals(reviewFile.getFilePath()))
                .singleElement()
                .extracting(ReviewFile::getSkipReason)
                .isEqualTo("review file count limit exceeded");
    }

    private GithubChangedFile changedFile(String filename, String status, String patch) {
        GithubChangedFile changedFile = new GithubChangedFile();
        changedFile.setFilename(filename);
        changedFile.setStatus(status);
        changedFile.setPatch(patch);
        changedFile.setAdditions(1);
        changedFile.setDeletions(0);
        return changedFile;
    }

    private List<GithubChangedFile> largePullRequestFixture() {
        List<GithubChangedFile> changedFiles = new ArrayList<>();
        changedFiles.add(changedFile(
                "docs/guide-0.md",
                "modified",
                "+revise usage notes"
        ));
        changedFiles.add(changedFile(
                "dist/app.bundle.js",
                "modified",
                "+minified bundle output"
        ));
        changedFiles.add(changedFile(
                "frontend/package-lock.json",
                "modified",
                "+lockfile update"
        ));
        changedFiles.add(changedFile(
                "src/main/java/com/example/service/NormalService0.java",
                "modified",
                "+void run0() {}"
        ));
        changedFiles.add(changedFile(
                "src/main/java/com/example/security/AuthService.java",
                "modified",
                "+String token = request.getHeader(\"Authorization\");\n+boolean permission = verify(token);"
        ));
        changedFiles.add(changedFile(
                "src/main/resources/db/migration/V2__payment.sql",
                "modified",
                "+alter table payment add column risk_level varchar(16);\n+update payment set risk_level = 'LOW';"
        ));
        changedFiles.add(changedFile(
                "src/main/java/com/example/controller/UserController.java",
                "modified",
                "+@PostMapping(\"/users\")\n+public UserResponse create(UserRequest request) { return service.create(request); }"
        ));
        changedFiles.add(changedFile(
                "src/main/resources/application.yml",
                "modified",
                "+security:\n+  oauth-token-ttl: 300"
        ));
        changedFiles.add(changedFile(
                "src/main/java/com/example/service/PaymentService.java",
                "modified",
                "+public void settle(Payment payment) {\n+    permissionService.assertCanCharge(payment);\n+    gateway.charge(payment);\n+}"
        ));

        for (int i = 1; i < 92; i++) {
            changedFiles.add(changedFile(
                    "src/main/java/com/example/service/NormalService" + i + ".java",
                    "modified",
                    "+void run" + i + "() {}"
            ));
        }
        for (int i = 1; i < 51; i++) {
            changedFiles.add(changedFile(
                    "docs/guide-" + i + ".md",
                    "modified",
                    "+revise section " + i
            ));
        }
        for (int i = 0; i < 30; i++) {
            changedFiles.add(changedFile(
                    "src/test/java/com/example/NormalService" + i + "Test.java",
                    "modified",
                    "+@Test void covers" + i + "() {}"
            ));
        }
        for (int i = 0; i < 20; i++) {
            changedFiles.add(changedFile(
                    "build/generated/source-" + i + ".java",
                    "modified",
                    "+generated content " + i
            ));
        }

        return changedFiles;
    }
}
