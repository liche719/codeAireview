package com.codepilot.module.review.graph;

import com.codepilot.module.agent.dto.AiReviewContext;
import com.codepilot.module.review.context.ReviewContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryGraphSnapshotTest {

    @Test
    void shouldBuildBoundedSymbolAwareGraphFromReviewContext() {
        ReviewContext.FileSummary authController = new ReviewContext.FileSummary(
                "src/main/java/com/example/AuthController.java",
                "modified",
                12,
                4,
                600,
                true,
                null
        );
        ReviewContext.FileSummary authService = new ReviewContext.FileSummary(
                "src/main/java/com/example/AuthService.java",
                "modified",
                8,
                2,
                420,
                true,
                null
        );
        ReviewContext.SemanticFileContext semantic = new ReviewContext.SemanticFileContext(
                "src/main/java/com/example/AuthController.java",
                "java",
                "com.example",
                List.of("AuthController"),
                List.of("login"),
                List.of("PostMapping", "PreAuthorize"),
                List.of("com.example.AuthService"),
                List.of("POST /login")
        );
        ReviewContext.RepoRelationshipHint relationship = new ReviewContext.RepoRelationshipHint(
                "src/main/java/com/example/AuthController.java",
                "src/main/java/com/example/AuthService.java",
                "IMPORT_TARGET",
                "Source imports target changed file."
        );

        RepositoryGraphSnapshot snapshot = RepositoryGraphSnapshotBuilder.buildReviewContextGraph(
                List.of(authController, authService),
                List.of(semantic),
                List.of(relationship),
                "src/main/java/com/example/AuthController.java"
        );

        assertThat(snapshot.isEmpty()).isFalse();
        assertThat(snapshot.focusFiles())
                .contains("src/main/java/com/example/AuthController.java");
        assertThat(snapshot.focusSymbols())
                .contains("AuthController", "login", "POST /login");
        assertThat(snapshot.nodeFor("src/main/java/com/example/AuthController.java"))
                .isPresent()
                .get()
                .satisfies(node -> {
                    assertThat(node.kind()).isEqualTo("security");
                    assertThat(node.language()).isEqualTo("java");
                    assertThat(node.score()).isGreaterThan(0);
                    assertThat(node.degree()).isGreaterThan(0);
                });
        assertThat(snapshot.relatedFilesFor("src/main/java/com/example/AuthController.java"))
                .contains("src/main/java/com/example/AuthService.java");
        assertThat(snapshot.edgesFor("src/main/java/com/example/AuthController.java"))
                .singleElement()
                .satisfies(edge -> {
                    assertThat(edge.type()).isEqualTo("IMPORT_TARGET");
                    assertThat(edge.reason()).contains("imports target changed file");
                });
    }

    @Test
    void shouldBuildBoundedSymbolAwareGraphFromAiReviewContext() {
        AiReviewContext context = new AiReviewContext(
                List.of("src/main/java/com/example/UserController.java"),
                1,
                1,
                0,
                12,
                1,
                240,
                List.of(),
                List.of(new AiReviewContext.FileSummary(
                        "src/main/java/com/example/UserController.java",
                        "modified",
                        12,
                        1,
                        240,
                        true,
                        null
                )),
                List.of(new AiReviewContext.SemanticFileContext(
                        "src/main/java/com/example/UserController.java",
                        "java",
                        "com.example",
                        List.of("UserController"),
                        List.of("create"),
                        List.of("PostMapping"),
                        List.of("com.example.UserService"),
                        List.of("POST /users")
                )),
                List.of(new AiReviewContext.RepoRelationshipHint(
                        "src/main/java/com/example/UserController.java",
                        "src/main/java/com/example/UserService.java",
                        "IMPORT_TARGET",
                        "Source imports target changed file."
                )),
                List.of()
        );

        RepositoryGraphSnapshot snapshot = RepositoryGraphSnapshotBuilder.buildAiReviewContextGraph(
                context.fileSummaries(),
                context.semanticFileContexts(),
                context.repoRelationshipHints(),
                "src/main/java/com/example/UserController.java"
        );

        assertThat(snapshot.focusFiles()).contains("src/main/java/com/example/UserController.java");
        assertThat(snapshot.focusSymbols()).contains("UserController", "create", "POST /users");
    }

    @Test
    void shouldDeduplicateRelatedFilesByNormalizedPath() {
        RepositoryGraphSnapshot snapshot = new RepositoryGraphSnapshot(
                List.of(
                        new RepositoryGraphSnapshot.GraphNode(
                                "src/main/java/com/example/AuthController.java",
                                "security",
                                "java",
                                "com.example",
                                true,
                                List.of("AuthController"),
                                List.of("login"),
                                List.of("PostMapping"),
                                List.of("com.example.AuthService"),
                                List.of("POST /login"),
                                80,
                                2
                        ),
                        new RepositoryGraphSnapshot.GraphNode(
                                "src/main/java/com/example/AuthService.java",
                                "service",
                                "java",
                                "com.example",
                                true,
                                List.of("AuthService"),
                                List.of("authenticate"),
                                List.of(),
                                List.of(),
                                List.of(),
                                70,
                                1
                        )
                ),
                List.of(
                        new RepositoryGraphSnapshot.GraphEdge(
                                "src\\main\\java\\com\\example\\AuthController.java",
                                "SRC/MAIN/JAVA/COM/EXAMPLE/AuthService.java",
                                "IMPORT_TARGET",
                                "Source imports target changed file."
                        ),
                        new RepositoryGraphSnapshot.GraphEdge(
                                "src/main/java/com/example/AuthController.java",
                                "src/main/java/com/example/AuthService.java",
                                "IMPORT_TARGET",
                                "Source imports target changed file."
                        )
                ),
                List.of("src/main/java/com/example/AuthController.java"),
                List.of("AuthController")
        );

        assertThat(snapshot.relatedFilesFor("src/main/java/com/example/AuthController.java"))
                .containsExactly("src/main/java/com/example/AuthService.java");
    }
}
