package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewContextSignalExtractorTest {

    private final ReviewContextSignalExtractor extractor = new ReviewContextSignalExtractor();

    @Test
    void shouldBuildFileSummaries() {
        ReviewFile skippedFile = reviewFile("package-lock.json", "+lock", 10, 0, true, "generated");
        skippedFile.setChangeType("modified");

        assertThat(extractor.fileSummaries(List.of(skippedFile)))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.filePath()).isEqualTo("package-lock.json");
                    assertThat(summary.changeType()).isEqualTo("modified");
                    assertThat(summary.additions()).isEqualTo(10);
                    assertThat(summary.deletions()).isZero();
                    assertThat(summary.patchChars()).isEqualTo("+lock".length());
                    assertThat(summary.reviewable()).isFalse();
                    assertThat(summary.skipReason()).isEqualTo("generated");
                });
    }

    @Test
    void shouldDetectReviewPlanningSignals() {
        List<ReviewContext.ReviewSignal> signals = extractor.reviewSignals(List.of(
                reviewFile("src/main/java/AuthService.java", "+code", 20, 2, false, null),
                reviewFile("src/main/resources/db/migration/V2__users.sql", "+alter", 3, 0, false, null),
                reviewFile("src/main/resources/application.yml", "+flag", 1, 1, false, null),
                reviewFile("pom.xml", "+dependency", 1, 0, false, null),
                reviewFile("src/main/java/com/example/controller/UserController.java", "+endpoint", 5, 1, false, null)
        ));

        assertThat(signals)
                .extracting(ReviewContext.ReviewSignal::type)
                .contains(
                        "MISSING_TEST_CHANGE",
                        "DATABASE_CHANGE",
                        "SECURITY_SENSITIVE_CHANGE",
                        "CONFIG_CHANGE",
                        "DEPENDENCY_CHANGE",
                        "PUBLIC_API_CHANGE"
                );
    }

    @Test
    void shouldNotReportMissingTestSignalWhenTestFilesChanged() {
        List<ReviewContext.ReviewSignal> signals = extractor.reviewSignals(List.of(
                reviewFile("src/main/java/UserService.java", "+code", 10, 0, false, null),
                reviewFile("src/test/java/UserServiceTest.java", "+test", 8, 0, false, null)
        ));

        assertThat(signals)
                .extracting(ReviewContext.ReviewSignal::type)
                .doesNotContain("MISSING_TEST_CHANGE");
    }

    @Test
    void shouldDetectLargePullRequestSignal() {
        List<ReviewFile> files = IntStream.rangeClosed(1, 10)
                .mapToObj(index -> reviewFile("src/main/java/File" + index + ".java", "+code", 1, 0, false, null))
                .toList();

        assertThat(extractor.reviewSignals(files))
                .extracting(ReviewContext.ReviewSignal::type)
                .contains("LARGE_PR");
    }

    private static ReviewFile reviewFile(
            String filePath,
            String patch,
            Integer additions,
            Integer deletions,
            boolean skipped,
            String skipReason
    ) {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setFilePath(filePath);
        reviewFile.setPatch(patch);
        reviewFile.setAdditions(additions);
        reviewFile.setDeletions(deletions);
        reviewFile.setSkipped(skipped);
        reviewFile.setSkipReason(skipReason);
        return reviewFile;
    }
}
