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
                List.of(new AiReviewContext.SkippedFile("package-lock.json", "generated file")),
                List.of(
                        new AiReviewContext.FileSummary(
                                "src/main/java/Demo.java",
                                "modified",
                                10,
                                3,
                                120,
                                true,
                                null
                        ),
                        new AiReviewContext.FileSummary(
                                "package-lock.json",
                                "modified",
                                100,
                                0,
                                900,
                                false,
                                "generated file"
                        )
                ),
                List.of(new AiReviewContext.ReviewSignal(
                        "MISSING_TEST_CHANGE",
                        "MEDIUM",
                        "Production code changed without matching test file changes."
                ))
        );

        String formatted = formatter.format(context);

        assertThat(formatted)
                .contains("Changed files (2 total, 1 reviewable, 1 skipped, +10 / -3, patchChars=120):")
                .contains("src/main/java/Demo.java")
                .contains("Review signals:")
                .contains("- [MEDIUM] MISSING_TEST_CHANGE: Production code changed without matching test file changes.")
                .contains("File summaries:")
                .contains("- src/main/java/Demo.java (modified, +10 / -3, patchChars=120, reviewable)")
                .contains("- package-lock.json (modified, +100 / -0, patchChars=900, skipped, reason=generated file)")
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
                skippedFiles,
                List.of(),
                List.of()
        );

        String formatted = formatter.format(context);

        assertThat(formatted)
                .contains("generated-20.js")
                .doesNotContain("generated-21.js")
                .contains("5 more skipped files omitted");
    }

    @Test
    void shouldLimitChangedFilesAndFileSummariesInPromptContext() {
        List<String> changedFiles = IntStream.rangeClosed(1, 105)
                .mapToObj(index -> "src/main/java/File" + index + ".java")
                .toList();
        List<AiReviewContext.FileSummary> fileSummaries = IntStream.rangeClosed(1, 35)
                .mapToObj(index -> new AiReviewContext.FileSummary(
                        "src/main/java/File" + index + ".java",
                        "modified",
                        1,
                        0,
                        10,
                        true,
                        null
                ))
                .toList();
        AiReviewContext context = new AiReviewContext(
                changedFiles,
                105,
                105,
                0,
                105,
                0,
                1050,
                List.of(),
                fileSummaries,
                List.of()
        );

        String formatted = formatter.format(context);
        String fileSummarySection = formatted.substring(formatted.indexOf("File summaries:"));

        assertThat(formatted)
                .contains("src/main/java/File100.java")
                .doesNotContain("src/main/java/File101.java")
                .contains("src/main/java/File31.java")
                .contains("5 more changed files omitted")
                .contains("src/main/java/File30.java")
                .contains("5 more file summaries omitted");
        assertThat(fileSummarySection)
                .contains("src/main/java/File30.java")
                .doesNotContain("src/main/java/File31.java");
    }

    @Test
    void shouldFormatCurrentFileRelatedChangedFiles() {
        AiReviewContext context = new AiReviewContext(
                List.of(
                        "src/main/java/com/example/UserService.java",
                        "src/test/java/com/example/UserServiceTest.java",
                        "src/main/java/com/example/UserController.java",
                        "src/main/java/com/example/order/OrderService.java"
                ),
                4,
                4,
                0,
                20,
                2,
                300,
                List.of(),
                List.of(
                        fileSummary("src/main/java/com/example/UserService.java"),
                        fileSummary("src/test/java/com/example/UserServiceTest.java"),
                        fileSummary("src/main/java/com/example/UserController.java"),
                        fileSummary("src/main/java/com/example/order/OrderService.java")
                ),
                List.of()
        );

        String formatted = formatter.formatForFile(context, "src/main/java/com/example/UserService.java");
        String currentFileFocusSection = formatted.substring(
                formatted.indexOf("Current file focus:"),
                formatted.indexOf("File summaries:")
        );

        assertThat(formatted)
                .contains("Current file focus:")
                .contains("- Current file: src/main/java/com/example/UserService.java")
                .contains("src/test/java/com/example/UserServiceTest.java (matching source/test pair)")
                .contains("src/main/java/com/example/UserController.java (same directory)");
        assertThat(currentFileFocusSection)
                .doesNotContain("src/main/java/com/example/order/OrderService.java (");
    }

    private AiReviewContext.FileSummary fileSummary(String filePath) {
        return new AiReviewContext.FileSummary(
                filePath,
                "modified",
                1,
                0,
                10,
                true,
                null
        );
    }
}
