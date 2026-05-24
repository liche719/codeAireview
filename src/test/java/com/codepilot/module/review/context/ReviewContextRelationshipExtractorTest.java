package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewContextRelationshipExtractorTest {

    private final ReviewContextSignalExtractor signalExtractor = new ReviewContextSignalExtractor();

    private final ReviewContextRelationshipExtractor relationshipExtractor = new ReviewContextRelationshipExtractor();

    @Test
    void shouldBuildPatchDerivedRepoRelationshipHints() {
        ReviewFile controller = reviewFile(
                "src/main/java/com/example/UserController.java",
                """
                        @@ -1,3 +1,12 @@
                         package com.example;
                        +import com.example.UserService;
                        +@RestController
                        +class UserController {
                        +    public UserDto getUser(String id) {
                        +        return userService.getUser(id);
                        +    }
                        +}
                        """,
                false
        );
        ReviewFile service = reviewFile(
                "src/main/java/com/example/UserService.java",
                """
                        @@ -1,3 +1,8 @@
                         package com.example;
                        +class UserService {
                        +    public UserDto getUser(String id) {
                        +        return repository.find(id);
                        +    }
                        +}
                        """,
                false
        );
        ReviewFile test = reviewFile(
                "src/test/java/com/example/UserServiceTest.java",
                """
                        @@ -1,2 +1,6 @@
                        +package com.example;
                        +class UserServiceTest {
                        +}
                        """,
                false
        );

        List<ReviewContext.SemanticFileContext> semanticContexts =
                signalExtractor.semanticFileContexts(List.of(controller, service, test));

        assertThat(relationshipExtractor.repoRelationshipHints(List.of(controller, service, test), semanticContexts))
                .extracting(ReviewContext.RepoRelationshipHint::type)
                .contains("IMPORT_TARGET", "SOURCE_TEST_PAIR", "LAYERED_COMPONENT", "SAME_PACKAGE");
    }

    @Test
    void shouldIgnoreSkippedFilesWhenBuildingRelationshipHints() {
        ReviewFile source = reviewFile("src/main/java/com/example/UserService.java", "+class UserService {}", false);
        ReviewFile skippedTest = reviewFile("src/test/java/com/example/UserServiceTest.java", "+class UserServiceTest {}", true);
        List<ReviewContext.SemanticFileContext> semanticContexts =
                signalExtractor.semanticFileContexts(List.of(source, skippedTest));

        assertThat(relationshipExtractor.repoRelationshipHints(List.of(source, skippedTest), semanticContexts))
                .isEmpty();
    }

    private static ReviewFile reviewFile(String filePath, String patch, boolean skipped) {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setFilePath(filePath);
        reviewFile.setPatch(patch);
        reviewFile.setSkipped(skipped);
        return reviewFile;
    }
}
