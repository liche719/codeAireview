package com.codepilot.module.agent.prompt;

import com.codepilot.module.agent.dto.AiReviewContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AiReviewContextFormatterTest {

    private final AiReviewContextFormatter formatter = new AiReviewContextFormatter();

    @Test
    void shouldFormatReviewContextForPrompt() {
        AiReviewContext context = new AiReviewContext(
                List.of("src/main/java/Demo.java", "package-lock.json"),
                2,
                1,
                1,
                10,
                3,
                120,
                List.of(new AiReviewContext.SkippedFile("package-lock.json", "generated file"))
        );

        String formatted = formatter.format(context);

        assertThat(formatted)
                .contains("Changed files (2 total, 1 reviewable, 1 skipped, +10 / -3, patchChars=120):")
                .contains("src/main/java/Demo.java")
                .contains("- package-lock.json: generated file");
    }

    @Test
    void shouldLimitSkippedFilesInPromptContext() {
        List<AiReviewContext.SkippedFile> skippedFiles = IntStream.rangeClosed(1, 25)
                .mapToObj(index -> new AiReviewContext.SkippedFile("generated-" + index + ".js", "generated"))
                .toList();
        AiReviewContext context = new AiReviewContext(
                List.of("src/main/java/Demo.java"),
                26,
                1,
                25,
                0,
                0,
                0,
                skippedFiles
        );

        String formatted = formatter.format(context);

        assertThat(formatted)
                .contains("generated-20.js")
                .doesNotContain("generated-21.js")
                .contains("5 more skipped files omitted");
    }
}
