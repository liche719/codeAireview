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
                List.of(new AiReviewContext.SemanticFileContext(
                        "src/main/java/Demo.java",
                        "java",
                        "com.example",
                        List.of("Demo"),
                        List.of("loadDemo"),
                        List.of("Service"),
                        List.of("java.util.List"),
                        List.of()
                )),
                List.of(new AiReviewContext.RepoRelationshipHint(
                        "src/main/java/Demo.java",
                        "src/test/java/DemoTest.java",
                        "SOURCE_TEST_PAIR",
                        "Source and matching test changed together; verify coverage matches the behavior change."
                )),
                new AiReviewContext.ReviewImpactPlan(
                        List.of("production-code-change", "test-change"),
                        List.of("runtime behavior", "test coverage"),
                        List.of("Review the patch as an impact set, not only as isolated file edits."),
                        List.of("Check whether changed tests assert the changed production behavior.")
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
                .contains("Semantic diff context (patch-derived, not a full repository graph):")
                .contains("- src/main/java/Demo.java (language=java, package=com.example)")
                .contains("  - symbols: Demo")
                .contains("  - methods: loadDemo")
                .contains("  - annotations: Service")
                .contains("  - imports: java.util.List")
                .contains("Repo relationship hints (patch-derived, not a full repository graph):")
                .contains("- src/main/java/Demo.java -> src/test/java/DemoTest.java [SOURCE_TEST_PAIR]: Source and matching test changed together; verify coverage matches the behavior change.")
                .contains("Review impact plan (patch-derived, not a full repository graph):")
                .contains("- change types: production-code-change; test-change")
                .contains("- impact areas: runtime behavior; test coverage")
                .contains("- priority focus: Review the patch as an impact set, not only as isolated file edits.")
                .contains("- verification hints: Check whether changed tests assert the changed production behavior.")
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

    @Test
    void shouldPrioritizeCurrentFileRepoRelationshipHints() {
        AiReviewContext context = new AiReviewContext(
                List.of(
                        "src/main/java/com/example/AuthController.java",
                        "src/main/java/com/example/AuthService.java",
                        "src/main/java/com/example/UnrelatedService.java"
                ),
                3,
                3,
                0,
                20,
                2,
                300,
                List.of(),
                List.of(
                        fileSummary("src/main/java/com/example/AuthController.java"),
                        fileSummary("src/main/java/com/example/AuthService.java"),
                        fileSummary("src/main/java/com/example/UnrelatedService.java")
                ),
                List.of(),
                List.of(
                        new AiReviewContext.RepoRelationshipHint(
                                "src/main/java/com/example/UnrelatedService.java",
                                "src/main/java/com/example/AuthController.java",
                                "SAME_PACKAGE",
                                "Both patch contexts declare package 'com.example'."
                        ),
                        new AiReviewContext.RepoRelationshipHint(
                                "src/main/java/com/example/AuthController.java",
                                "src/main/java/com/example/AuthService.java",
                                "IMPORT_TARGET",
                                "Source imports target changed file via 'com.example.AuthService'."
                        )
                ),
                List.of()
        );

        String formatted = formatter.formatForFile(context, "src/main/java/com/example/AuthService.java");
        String relationshipSection = formatted.substring(
                formatted.indexOf("Repo relationship hints"),
                formatted.indexOf("File summaries:")
        );

        assertThat(relationshipSection)
                .contains("Repo relationship hints (patch-derived, not a full repository graph):")
                .contains("src/main/java/com/example/AuthController.java -> src/main/java/com/example/AuthService.java [IMPORT_TARGET]")
                .contains("src/main/java/com/example/UnrelatedService.java -> src/main/java/com/example/AuthController.java [SAME_PACKAGE]");
        assertThat(relationshipSection.indexOf("AuthController.java -> src/main/java/com/example/AuthService.java"))
                .isLessThan(relationshipSection.indexOf("UnrelatedService.java -> src/main/java/com/example/AuthController.java"));
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
