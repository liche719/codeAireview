package com.codepilot.module.review.processor;

import com.codepilot.module.review.context.ReviewContext;
import com.codepilot.module.review.diff.DiffLineMapper;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.planner.ReviewPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewIssuePatchVerifierTest {

    private final ReviewIssuePatchVerifier verifier = new ReviewIssuePatchVerifier(new DiffLineMapper());

    @Test
    void shouldKeepAndAnnotateIssueGroundedOnChangedLine() {
        ReviewIssue issue = issue(
                "src/main/java/AuthService.java",
                2,
                "SECURITY",
                "HIGH",
                "Hard-coded token",
                "The changed line introduces a hard-coded token.",
                "Move token to a secret manager."
        );

        List<ReviewIssue> verified = verifier.keepVerified(
                "src/main/java/AuthService.java",
                """
                        @@ -1,1 +1,2 @@
                         class AuthService {
                        +  String token = "abc";
                        """,
                ReviewContext.empty(),
                List.of(issue)
        );

        assertThat(verified)
                .singleElement()
                .satisfies(kept -> {
                    assertThat(kept.getRuleReference()).contains("PATCH_VERIFIED:PATCH_LINE");
                    assertThat(kept.getDescription()).contains("Evidence: changed line 2 on RIGHT.");
                });
    }

    @Test
    void shouldDropUngroundedLlmIssueWhenPatchAndPlanDoNotSupportIt() {
        ReviewIssue issue = issue(
                "src/main/java/DemoService.java",
                null,
                "STYLE",
                "LOW",
                "Generic cleanup",
                "This implementation may be improved later.",
                "Consider improving the implementation."
        );

        List<ReviewIssue> verified = verifier.keepVerified(
                "src/main/java/DemoService.java",
                """
                        @@ -1,1 +1,2 @@
                         class DemoService {
                        +  void run() {}
                        """,
                ReviewContext.empty(),
                List.of(issue)
        );

        assertThat(verified).isEmpty();
    }

    @Test
    void shouldKeepIssueGroundedBySemanticReviewPlanRisk() {
        ReviewIssue issue = issue(
                "src/main/java/AuthService.java",
                null,
                "SECURITY",
                "HIGH",
                "Auth permission boundary changed",
                "The auth permission boundary may allow unauthorized access.",
                "Verify the permission check before accepting this change."
        );

        List<ReviewIssue> verified = verifier.keepVerified(
                "src/main/java/AuthService.java",
                """
                        @@ -1,1 +1,2 @@
                         class AuthService {
                        +  void run() {}
                        """,
                contextWithSecurityPlan(),
                List.of(issue)
        );

        assertThat(verified)
                .singleElement()
                .satisfies(kept -> assertThat(kept.getRuleReference()).contains("PATCH_VERIFIED:REVIEW_PLAN"));
    }

    @Test
    void shouldKeepHighSignalIssueGroundedByPatchRiskArea() {
        ReviewIssue issue = issue(
                "src/main/resources/db/migration/V2__users.sql",
                null,
                "SQL_RISK",
                "HIGH",
                "Unsafe query construction",
                "The change may concatenate request input before persistence.",
                "Use parameter binding before executing the query."
        );

        List<ReviewIssue> verified = verifier.keepVerified(
                "src/main/resources/db/migration/V2__users.sql",
                """
                        @@ -1,1 +1,2 @@
                         -- users migration
                        +-- accepts a new caller-provided filter
                        """,
                contextWithDatabasePlan(),
                List.of(issue)
        );

        assertThat(verified)
                .singleElement()
                .satisfies(kept -> assertThat(kept.getRuleReference()).contains("PATCH_VERIFIED:PATCH_RISK_AREA"));
    }

    @Test
    void shouldKeepToolIssuesWithoutLlmPatchVerification() {
        ReviewIssue issue = issue(
                "src/main/java/DemoRepository.java",
                null,
                "SQL_RISK",
                "HIGH",
                "SQL risk",
                "Tool finding from deterministic scanner.",
                "Use parameter binding."
        );
        issue.setSource("TOOL");

        List<ReviewIssue> verified = verifier.keepVerified(
                "src/main/java/DemoRepository.java",
                """
                        @@ -1,1 +1,2 @@
                         class DemoRepository {
                        +  String query = "select * from users";
                        """,
                ReviewContext.empty(),
                List.of(issue)
        );

        assertThat(verified).containsExactly(issue);
        assertThat(issue.getRuleReference()).isNull();
    }

    private ReviewContext contextWithSecurityPlan() {
        ReviewPlan plan = new ReviewPlan(
                List.of("security-sensitive-change"),
                List.of(new ReviewPlan.RiskArea(
                        "security-boundary",
                        "HIGH",
                        "Auth permission boundary changed."
                )),
                List.of(new ReviewPlan.PriorityFile(
                        "src/main/java/AuthService.java",
                        1000,
                        List.of("security-sensitive path or patch keyword")
                )),
                List.of(new ReviewPlan.FileFocus(
                        "src/main/java/AuthService.java",
                        List.of("Prioritize exploitable auth and permission regressions."),
                        List.of("Tie any auth finding to the changed permission boundary."),
                        List.of()
                )),
                List.of(),
                List.of("Prioritize exploitable auth/secrets/permission regressions."),
                true,
                0.8,
                List.of()
        );
        return new ReviewContext(
                List.of("src/main/java/AuthService.java"),
                1,
                1,
                0,
                1,
                0,
                100,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ReviewContext.ReviewImpactPlan.empty(),
                plan,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ReviewContext contextWithDatabasePlan() {
        ReviewPlan plan = new ReviewPlan(
                List.of("database-change"),
                List.of(new ReviewPlan.RiskArea(
                        "database-safety",
                        "HIGH",
                        "Database migration changed."
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of("Check migration ordering, rollback strategy, and destructive SQL."),
                true,
                0.8,
                List.of()
        );
        return new ReviewContext(
                List.of("src/main/resources/db/migration/V2__users.sql"),
                1,
                1,
                0,
                1,
                0,
                100,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ReviewContext.ReviewImpactPlan.empty(),
                plan,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ReviewIssue issue(
            String filePath,
            Integer lineNumber,
            String issueType,
            String severity,
            String title,
            String description,
            String suggestion
    ) {
        ReviewIssue issue = new ReviewIssue();
        issue.setFilePath(filePath);
        issue.setLineNumber(lineNumber);
        issue.setIssueType(issueType);
        issue.setSeverity(severity);
        issue.setTitle(title);
        issue.setDescription(description);
        issue.setSuggestion(suggestion);
        issue.setSource("LLM");
        return issue;
    }
}
