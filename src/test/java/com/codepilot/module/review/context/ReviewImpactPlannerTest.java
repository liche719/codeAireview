package com.codepilot.module.review.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewImpactPlannerTest {

    private final ReviewImpactPlanner planner = new ReviewImpactPlanner();

    @Test
    void shouldBuildPatchDerivedImpactPlanFromSignalsSemanticContextAndRelationships() {
        ReviewContext.ReviewImpactPlan plan = planner.plan(
                List.of(
                        fileSummary("src/main/java/com/example/AuthController.java", true, null),
                        fileSummary("src/main/resources/db/migration/V2__auth.sql", true, null),
                        fileSummary(".github/workflows/ci.yml", false, "workflow skipped")
                ),
                List.of(new ReviewContext.SemanticFileContext(
                        "src/main/java/com/example/AuthController.java",
                        "java",
                        "com.example",
                        List.of("AuthController"),
                        List.of("login"),
                        List.of("PostMapping", "PreAuthorize"),
                        List.of("com.example.AuthService"),
                        List.of("POST /login")
                )),
                List.of(
                        new ReviewContext.RepoRelationshipHint(
                                "src/main/java/com/example/AuthController.java",
                                "src/main/java/com/example/AuthService.java",
                                "IMPORT_TARGET",
                                "Source imports target changed file."
                        ),
                        new ReviewContext.RepoRelationshipHint(
                                "src/main/java/com/example/AuthService.java",
                                "src/test/java/com/example/AuthServiceTest.java",
                                "SOURCE_TEST_PAIR",
                                "Source and matching test changed together."
                        )
                ),
                List.of(
                        new ReviewContext.ReviewSignal(
                                "SECURITY_SENSITIVE_CHANGE",
                                "HIGH",
                                "Security-sensitive files changed."
                        ),
                        new ReviewContext.ReviewSignal(
                                "DATABASE_CHANGE",
                                "HIGH",
                                "Database files changed."
                        )
                )
        );

        assertThat(plan.changeTypes())
                .contains(
                        "production-code-change",
                        "database-change",
                        "ci-workflow-change",
                        "public-api-change",
                        "security-boundary-change",
                        "security-sensitive-change"
                );
        assertThat(plan.impactAreas())
                .contains(
                        "runtime behavior",
                        "data compatibility and rollback safety",
                        "CI/CD execution permissions",
                        "API contract and clients",
                        "authorization and authentication boundary",
                        "cross-file API compatibility",
                        "test coverage alignment",
                        "auth/secrets/permission boundary"
                );
        assertThat(plan.priorityFocuses())
                .contains(
                        "Validate auth, backward compatibility, and response contract for changed routes.",
                        "Check whether the security boundary changed unintentionally.",
                        "Inspect changed importer/importee pairs for broken contracts.",
                        "Prioritize exploitable security regressions over cosmetic issues.",
                        "Review the patch as an impact set, not only as isolated file edits."
                );
        assertThat(plan.verificationHints())
                .contains(
                        "Check migration ordering, rollback strategy, and destructive SQL.",
                        "Check workflow permissions, secret exposure, and untrusted code execution.",
                        "Check whether changed tests assert the changed production behavior."
                )
                .anyMatch(hint -> hint.contains("workflow skipped"));
    }

    @Test
    void shouldReturnEmptyPlanWhenNoInputsExist() {
        assertThat(planner.plan(null, null, null, null).isEmpty()).isTrue();
    }

    private ReviewContext.FileSummary fileSummary(String filePath, boolean reviewable, String skipReason) {
        return new ReviewContext.FileSummary(
                filePath,
                "modified",
                1,
                0,
                10,
                reviewable,
                skipReason
        );
    }
}
