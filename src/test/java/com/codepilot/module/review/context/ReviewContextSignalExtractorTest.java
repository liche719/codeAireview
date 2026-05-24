package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewContextSignalExtractorTest {

    private final ReviewContextSignalExtractor extractor = new ReviewContextSignalExtractor();

    @Test
    void shouldBuildFileSummaries() {
        ReviewFile skippedFile = reviewFile("package-lock.json", "+lock", 10, 0, true, "generated");
        skippedFile.setChangeType("modified");

        assertThat(extractor.fileSummaries(List.of(skippedFile)))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.filePath()).isEqualTo("package-lock.json");
                    assertThat(summary.changeType()).isEqualTo("modified");
                    assertThat(summary.additions()).isEqualTo(10);
                    assertThat(summary.deletions()).isZero();
                    assertThat(summary.patchChars()).isEqualTo("+lock".length());
                    assertThat(summary.reviewable()).isFalse();
                    assertThat(summary.skipReason()).isEqualTo("generated");
                });
    }

    @Test
    void shouldExtractPatchDerivedSemanticFileContext() {
        ReviewFile reviewFile = reviewFile(
                "src/main/java/com/example/UserController.java",
                """
                        @@ -1,3 +1,16 @@
                         package com.example;
                        +import org.springframework.web.bind.annotation.GetMapping;
                        +import org.springframework.web.bind.annotation.RestController;
                        +@RestController
                        +class UserController {
                        +    @GetMapping("/users/{id}")
                        +    public UserDto getUser(String id) {
                        +        return userService.getUser(id);
                        +    }
                        +}
                        -class OldController {}
                        """,
                8,
                1,
                false,
                null
        );

        assertThat(extractor.semanticFileContexts(List.of(reviewFile)))
                .singleElement()
                .satisfies(context -> {
                    assertThat(context.filePath()).isEqualTo("src/main/java/com/example/UserController.java");
                    assertThat(context.language()).isEqualTo("java");
                    assertThat(context.packageName()).isEqualTo("com.example");
                    assertThat(context.declaredSymbols()).contains("UserController");
                    assertThat(context.changedMethods()).contains("getUser");
                    assertThat(context.annotations()).contains("RestController", "GetMapping");
                    assertThat(context.imports()).contains(
                            "org.springframework.web.bind.annotation.GetMapping",
                            "org.springframework.web.bind.annotation.RestController"
                    );
                    assertThat(context.apiRoutes()).contains("/users/{id}");
                });
    }

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
                7,
                0,
                false,
                null
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
                6,
                0,
                false,
                null
        );
        ReviewFile test = reviewFile(
                "src/test/java/com/example/UserServiceTest.java",
                """
                        @@ -1,2 +1,6 @@
                        +package com.example;
                        +class UserServiceTest {
                        +}
                        """,
                3,
                0,
                false,
                null
        );

        List<ReviewContext.SemanticFileContext> semanticContexts =
                extractor.semanticFileContexts(List.of(controller, service, test));

        assertThat(extractor.repoRelationshipHints(List.of(controller, service, test), semanticContexts))
                .extracting(ReviewContext.RepoRelationshipHint::type)
                .contains("IMPORT_TARGET", "SOURCE_TEST_PAIR", "LAYERED_COMPONENT", "SAME_PACKAGE");
    }

    @Test
    void shouldDetectReviewPlanningSignals() {
        List<ReviewContext.ReviewSignal> signals = extractor.reviewSignals(List.of(
                reviewFile("src/main/java/AuthService.java", "+code", 20, 2, false, null),
                reviewFile("src/main/resources/db/migration/V2__users.sql", "+alter", 3, 0, false, null),
                reviewFile("src/main/resources/application.yml", "+flag", 1, 1, false, null),
                reviewFile("pom.xml", "+dependency", 1, 0, false, null),
                reviewFile("src/main/java/com/example/controller/UserController.java", "+endpoint", 5, 1, false, null)
        ));

        assertThat(signals)
                .extracting(ReviewContext.ReviewSignal::type)
                .contains(
                        "MISSING_TEST_CHANGE",
                        "DATABASE_CHANGE",
                        "SECURITY_SENSITIVE_CHANGE",
                        "CONFIG_CHANGE",
                        "DEPENDENCY_CHANGE",
                        "PUBLIC_API_CHANGE"
                );
    }

    @Test
    void shouldNotReportMissingTestSignalWhenTestFilesChanged() {
        List<ReviewContext.ReviewSignal> signals = extractor.reviewSignals(List.of(
                reviewFile("src/main/java/UserService.java", "+code", 10, 0, false, null),
                reviewFile("src/test/java/UserServiceTest.java", "+test", 8, 0, false, null)
        ));

        assertThat(signals)
                .extracting(ReviewContext.ReviewSignal::type)
                .doesNotContain("MISSING_TEST_CHANGE");
    }

    @Test
    void shouldDetectLargePullRequestSignal() {
        List<ReviewFile> files = IntStream.rangeClosed(1, 10)
                .mapToObj(index -> reviewFile("src/main/java/File" + index + ".java", "+code", 1, 0, false, null))
                .toList();

        assertThat(extractor.reviewSignals(files))
                .extracting(ReviewContext.ReviewSignal::type)
                .contains("LARGE_PR");
    }

    private static ReviewFile reviewFile(
            String filePath,
            String patch,
            Integer additions,
            Integer deletions,
            boolean skipped,
            String skipReason
    ) {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setFilePath(filePath);
        reviewFile.setPatch(patch);
        reviewFile.setAdditions(additions);
        reviewFile.setDeletions(deletions);
        reviewFile.setSkipped(skipped);
        reviewFile.setSkipReason(skipReason);
        return reviewFile;
    }
}
