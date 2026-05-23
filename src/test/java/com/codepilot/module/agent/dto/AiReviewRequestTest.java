package com.codepilot.module.agent.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AiReviewRequestTest {

    @Test
    void shouldCreateContextFromChangedFilesForBackwardCompatibility() {
        AiReviewRequest request = new AiReviewRequest(
                1L,
                "src/main/java/Demo.java",
                "+code",
                List.of("src/main/java/Demo.java", " ", "src/test/java/DemoTest.java")
        );

        assertThat(request.allChangedFiles())
                .containsExactly("src/main/java/Demo.java", "src/test/java/DemoTest.java");
        assertThat(request.context().totalFileCount()).isEqualTo(2);
        assertThat(request.context().reviewableFileCount()).isEqualTo(2);
        assertThat(request.context().skippedFileCount()).isZero();
    }

    @Test
    void shouldDefensivelyCopyContextLists() {
        List<String> files = new ArrayList<>(List.of("src/main/java/Demo.java"));
        AiReviewContext context = AiReviewContext.fromChangedFiles(files);
        files.add("src/main/java/Other.java");

        assertThat(context.allChangedFiles())
                .containsExactly("src/main/java/Demo.java");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> context.allChangedFiles().add("src/main/java/Injected.java"));
    }
}
