package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewContextBuilderTest {

    private final ReviewContextBuilder builder = new ReviewContextBuilder();

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

        assertThat(context.toAiReviewContext().allChangedFiles())
                .containsExactly("src/main/java/Demo.java", "package-lock.json", "src/test/java/DemoTest.java");
        assertThat(context.toAiReviewContext().skippedFiles())
                .singleElement()
                .satisfies(skippedFile -> assertThat(skippedFile.filePath()).isEqualTo("package-lock.json"));
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
