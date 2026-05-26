package com.codepilot.module.agent.prompt;

import com.codepilot.module.agent.dto.AiReviewContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiReviewContextFormatterGraphTest {

    private final AiReviewContextFormatter formatter = new AiReviewContextFormatter();

    @Test
    void shouldRenderRepositoryGraphSnapshotForCurrentFile() {
        AiReviewContext context = new AiReviewContext(
                List.of(
                        "src/main/java/com/example/AuthController.java",
                        "src/main/java/com/example/AuthService.java"
                ),
                2,
                2,
                0,
                20,
                2,
                300,
                List.of(),
                List.of(
                        new AiReviewContext.FileSummary(
                                "src/main/java/com/example/AuthController.java",
                                "modified",
                                12,
                                2,
                                180,
                                true,
                                null
                        ),
                        new AiReviewContext.FileSummary(
                                "src/main/java/com/example/AuthService.java",
                                "modified",
                                8,
                                0,
                                120,
                                true,
                                null
                        )
                ),
                List.of(new AiReviewContext.SemanticFileContext(
                        "src/main/java/com/example/AuthController.java",
                        "java",
                        "com.example",
                        List.of("AuthController"),
                        List.of("login"),
                        List.of("PostMapping", "PreAuthorize"),
                        List.of("com.example.AuthService"),
                        List.of("POST /login")
                )),
                List.of(new AiReviewContext.RepoRelationshipHint(
                        "src/main/java/com/example/AuthController.java",
                        "src/main/java/com/example/AuthService.java",
                        "IMPORT_TARGET",
                        "Source imports target changed file via 'com.example.AuthService'."
                )),
                List.of()
        );

        String formatted = formatter.formatForFile(context, "src/main/java/com/example/AuthController.java");

        assertThat(formatted)
                .contains("Repository graph snapshot (symbol-aware, patch-derived, bounded):")
                .contains("focus files:")
                .contains("focus symbols:")
                .contains("graph nodes:")
                .contains("graph edges:")
                .contains("AuthController")
                .contains("AuthService")
                .contains("IMPORT_TARGET");
    }
}
