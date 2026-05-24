package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewContextBuilderTest {

    private final ReviewContextBuilder builder = new ReviewContextBuilder(new ReviewContextSignalExtractor());

    @Test
    void shouldBuildPullRequestReviewContext() {
        ReviewContext context = builder.build(List.of(
                reviewFile("src/main/java/Demo.java", "+code", 2, 1, false, null),
                reviewFile(" ", "+ignored", 1, 0, false, null),
                reviewFile("package-lock.json", "+lock", 10, 3, true, "file type or generated path skipped"),
                reviewFile("src/test/java/DemoTest.java", "+test", 4, 0, false, null)
        ));

        assertThat(context.allChangedFiles())
                .containsExactly("src/main/java/Demo.java", "package-lock.json", "src/test/java/DemoTest.java");
        assertThat(context.totalFileCount()).isEqualTo(4);
        assertThat(context.reviewableFileCount()).isEqualTo(3);
        assertThat(context.skippedFileCount()).isEqualTo(1);
        assertThat(context.totalAdditions()).isEqualTo(17);
        assertThat(context.totalDeletions()).isEqualTo(4);
        assertThat(context.totalPatchChars()).isEqualTo("+code".length() + "+ignored".length() + "+lock".length() + "+test".length());
        assertThat(context.skippedFiles())
                .singleElement()
                .satisfies(skippedFile -> {
                    assertThat(skippedFile.filePath()).isEqualTo("package-lock.json");
                    assertThat(skippedFile.reason()).isEqualTo("file type or generated path skipped");
                });
        assertThat(context.fileSummaries())
                .extracting(ReviewContext.FileSummary::filePath)
                .containsExactly("src/main/java/Demo.java", "package-lock.json", "src/test/java/DemoTest.java");
        assertThat(context.reviewSignals())
                .extracting(ReviewContext.ReviewSignal::type)
                .contains("SKIPPED_FILES")
                .doesNotContain("CONFIG_CHANGE");

        assertThat(context.toAiReviewContext().allChangedFiles())
                .containsExactly("src/main/java/Demo.java", "package-lock.json", "src/test/java/DemoTest.java");
        assertThat(context.toAiReviewContext().skippedFiles())
                .singleElement()
                .satisfies(skippedFile -> assertThat(skippedFile.filePath()).isEqualTo("package-lock.json"));
        assertThat(context.toAiReviewContext().fileSummaries())
                .extracting(com.codepilot.module.agent.dto.AiReviewContext.FileSummary::filePath)
                .containsExactly("src/main/java/Demo.java", "package-lock.json", "src/test/java/DemoTest.java");
        assertThat(context.toAiReviewContext().semanticFileContexts())
                .extracting(com.codepilot.module.agent.dto.AiReviewContext.SemanticFileContext::filePath)
                .contains("src/main/java/Demo.java", "src/test/java/DemoTest.java");
    }

    @Test
    void shouldDetectReviewPlanningSignals() {
        ReviewContext context = builder.build(List.of(
                reviewFile("src/main/java/AuthService.java", "+code", 20, 2, false, null),
                reviewFile("src/main/resources/db/migration/V2__users.sql", "+alter", 3, 0, false, null),
                reviewFile("src/main/resources/application.yml", "+flag", 1, 1, false, null)
        ));

        assertThat(context.reviewSignals())
                .extracting(ReviewContext.ReviewSignal::type)
                .contains(
                        "MISSING_TEST_CHANGE",
                        "DATABASE_CHANGE",
                        "SECURITY_SENSITIVE_CHANGE",
                        "CONFIG_CHANGE"
                );
    }

    @Test
    void shouldReturnEmptyContextWhenFilesAreMissing() {
        assertThat(builder.build(null).allChangedFiles()).isEmpty();
        assertThat(builder.build(List.of()).allChangedFiles()).isEmpty();
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
