package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewContextBuilderTest {

    private final ReviewContextBuilder builder = new ReviewContextBuilder();

    @Test
    void shouldBuildChangedFilePathContext() {
        ReviewContext context = builder.build(List.of(
                reviewFile("src/main/java/Demo.java"),
                reviewFile(" "),
                reviewFile("src/test/java/DemoTest.java")
        ));

        assertThat(context.allChangedFiles())
                .containsExactly("src/main/java/Demo.java", "src/test/java/DemoTest.java");
    }

    @Test
    void shouldReturnEmptyContextWhenFilesAreMissing() {
        assertThat(builder.build(null).allChangedFiles()).isEmpty();
        assertThat(builder.build(List.of()).allChangedFiles()).isEmpty();
    }

    private static ReviewFile reviewFile(String filePath) {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setFilePath(filePath);
        return reviewFile;
    }
}
