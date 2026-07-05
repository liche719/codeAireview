package com.codepilot.module.review.planner;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.graph.RepositoryGraphSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPlanPriorityFileScorerTest {

    private final ReviewPlanPriorityFileScorer scorer = new ReviewPlanPriorityFileScorer();

    @Test
    void shouldPrioritizeSecurityAndApiCodeOverDocumentationOnlyChanges() {
        String authPath = "src/main/java/com/example/AuthController.java";
        String authKey = ReviewFileClassifier.normalizePath(authPath);
        String readmeKey = ReviewFileClassifier.normalizePath("README.md");

        List<ReviewPlan.PriorityFile> priorityFiles = scorer.priorityFiles(
                List.of(fileSummary(authPath), fileSummary("README.md")),
                Map.of(
                        authKey, reviewFile(authPath, "+String token = request.getHeader(\"Authorization\");"),
                        readmeKey, reviewFile("README.md", "+Document a setup step.")
                ),
                Map.of(authKey, new ReviewContext.SemanticFileContext(
                        authPath,
                        "java",
                        "com.example",
                        List.of("AuthController"),
                        List.of("login"),
                        List.of(),
                        List.of(),
                        List.of("POST /login")
                )),
                Map.of(authKey, List.of(new ReviewContext.RepoRelationshipHint(
                        authPath,
                        "src/main/java/com/example/AuthService.java",
                        "IMPORT_TARGET",
                        "Controller imports service."
                ))),
                RepositoryGraphSnapshot.empty()
        );

        assertThat(priorityFiles)
                .singleElement()
                .satisfies(priorityFile -> {
                    assertThat(priorityFile.filePath()).isEqualTo(authPath);
                    assertThat(priorityFile.score()).isGreaterThan(0);
                    assertThat(priorityFile.reasons())
                            .contains(
                                    "security-sensitive path or patch keyword",
                                    "public API contract change",
                                    "production code change"
                            );
                });
    }

    private ReviewContext.FileSummary fileSummary(String filePath) {
        return new ReviewContext.FileSummary(
                filePath,
                "modified",
                10,
                1,
                300,
                true,
                null
        );
    }

    private ReviewFile reviewFile(String filePath, String patch) {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setFilePath(filePath);
        reviewFile.setPatch(patch);
        reviewFile.setSkipped(false);
        return reviewFile;
    }
}
