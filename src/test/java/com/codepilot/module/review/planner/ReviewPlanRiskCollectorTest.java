package com.codepilot.module.review.planner;

import com.codepilot.module.review.context.ReviewContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPlanRiskCollectorTest {

    private final ReviewPlanRiskCollector collector = new ReviewPlanRiskCollector();

    @Test
    void shouldCollectRiskAreasFromSignalsFilesRelationshipsAndLinkedIssues() {
        ReviewPlanRiskCollector.RiskProfile profile = collector.collect(
                new ReviewContext.ReviewImpactPlan(
                        List.of("production-code-change"),
                        List.of(),
                        List.of(),
                        List.of()
                ),
                List.of(
                        fileSummary("src/main/java/com/example/AuthController.java"),
                        fileSummary("src/main/resources/db/migration/V2__auth.sql")
                ),
                List.of(new ReviewContext.SemanticFileContext(
                        "src/main/java/com/example/AuthController.java",
                        "java",
                        "com.example",
                        List.of("AuthController"),
                        List.of("login"),
                        List.of("PreAuthorize"),
                        List.of("com.example.AuthService"),
                        List.of("POST /login")
                )),
                List.of(new ReviewContext.RepoRelationshipHint(
                        "src/main/java/com/example/AuthController.java",
                        "src/main/java/com/example/AuthService.java",
                        "IMPORT_TARGET",
                        "Controller imports changed service."
                )),
                List.of(new ReviewContext.ReviewSignal(
                        "MISSING_TEST_CHANGE",
                        "MEDIUM",
                        "No matching test file changed."
                )),
                List.of(new ReviewContext.LinkedIssueContext(
                        "liche719",
                        "codeAireview",
                        22,
                        "Fix auth permission bug",
                        "OPEN",
                        "https://github.com/liche719/codeAireview/issues/22",
                        "BODY"
                ))
        );

        assertThat(profile.changeTypes())
                .contains(
                        "production-code-change",
                        "database-change",
                        "security-sensitive-change",
                        "public-api-change",
                        "security-boundary-change",
                        "issue-driven-change",
                        "bugfix"
                );
        assertThat(profile.riskAreas())
                .extracting(ReviewPlan.RiskArea::type)
                .contains(
                        "test-coverage-gap",
                        "database-safety",
                        "security-boundary",
                        "api-contract",
                        "cross-file-api-compatibility",
                        "task-requirement-alignment",
                        "bugfix-regression"
                );
    }

    @Test
    void shouldCollectChineseLinkedIssueRiskSignals() {
        ReviewPlanRiskCollector.RiskProfile profile = collector.collect(
                ReviewContext.ReviewImpactPlan.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ReviewContext.LinkedIssueContext(
                        "liche719",
                        "codeAireview",
                        23,
                        "修复认证权限漏洞",
                        "OPEN",
                        "https://github.com/liche719/codeAireview/issues/23",
                        "BODY"
                ))
        );

        assertThat(profile.changeTypes())
                .contains("issue-driven-change", "bugfix", "security-sensitive-change");
        assertThat(profile.riskAreas())
                .extracting(ReviewPlan.RiskArea::type)
                .contains("task-requirement-alignment", "bugfix-regression", "security-boundary");
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
}
