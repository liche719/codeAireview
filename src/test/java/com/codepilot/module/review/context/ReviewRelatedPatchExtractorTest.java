package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewRelatedPatchExtractorTest {

    private final ReviewRelatedPatchExtractor extractor = new ReviewRelatedPatchExtractor();

    @Test
    void shouldBuildBidirectionalPatchExcerptsFromRelationshipHints() {
        ReviewFile controller = reviewFile(
                "src/main/java/com/example/AuthController.java",
                """
                        @@ -1,3 +1,8 @@
                         class AuthController {
                        +  String login() {
                        +    return authService.login();
                        +  }
                         }
                        """
        );
        ReviewFile service = reviewFile(
                "src/main/java/com/example/AuthService.java",
                """
                        @@ -1,3 +1,8 @@
                         class AuthService {
                        +  String login() {
                        +    return tokenIssuer.issue();
                        +  }
                         }
                        """
        );

        List<ReviewContext.RelatedPatchExcerpt> excerpts = extractor.relatedPatchExcerpts(
                List.of(controller, service),
                List.of(new ReviewContext.RepoRelationshipHint(
                        controller.getFilePath(),
                        service.getFilePath(),
                        "IMPORT_TARGET",
                        "controller imports service"
                ))
        );

        assertThat(excerpts)
                .hasSize(2)
                .anySatisfy(excerpt -> {
                    assertThat(excerpt.sourceFile()).isEqualTo(controller.getFilePath());
                    assertThat(excerpt.relatedFile()).isEqualTo(service.getFilePath());
                    assertThat(excerpt.reason()).contains("IMPORT_TARGET");
                    assertThat(excerpt.excerpt()).contains("tokenIssuer.issue()");
                })
                .anySatisfy(excerpt -> {
                    assertThat(excerpt.sourceFile()).isEqualTo(service.getFilePath());
                    assertThat(excerpt.relatedFile()).isEqualTo(controller.getFilePath());
                    assertThat(excerpt.excerpt()).contains("authService.login()");
                });
    }

    @Test
    void shouldBuildFallbackExcerptsForMatchingSourceAndTestFiles() {
        ReviewFile source = reviewFile("src/main/java/com/example/UserService.java", "+class UserService {}");
        ReviewFile test = reviewFile("src/test/java/com/example/UserServiceTest.java", "+class UserServiceTest {}");

        List<ReviewContext.RelatedPatchExcerpt> excerpts =
                extractor.relatedPatchExcerpts(List.of(source, test), List.of());

        assertThat(excerpts)
                .extracting(ReviewContext.RelatedPatchExcerpt::reason)
                .containsOnly("matching source/test pair");
    }

    @Test
    void shouldIgnoreSkippedFilesAndEmptyPatches() {
        ReviewFile source = reviewFile("src/main/java/com/example/UserService.java", "+class UserService {}");
        ReviewFile skipped = reviewFile("src/test/java/com/example/UserServiceTest.java", "+class UserServiceTest {}");
        skipped.setSkipped(true);
        ReviewFile empty = reviewFile("src/main/java/com/example/UserController.java", " ");

        assertThat(extractor.relatedPatchExcerpts(List.of(source, skipped, empty), List.of()))
                .isEmpty();
    }

    private ReviewFile reviewFile(String filePath, String patch) {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setFilePath(filePath);
        reviewFile.setPatch(patch);
        reviewFile.setSkipped(false);
        return reviewFile;
    }
}
