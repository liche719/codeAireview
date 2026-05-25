package com.codepilot.module.agent.review;

import com.codepilot.module.tool.dto.ToolCheckRequest;
import com.codepilot.module.tool.dto.ToolCheckResult;
import com.codepilot.module.tool.rule.DeterministicReviewRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicReviewToolRunnerTest {

    private final ReviewIssueDeduplicator deduplicator = new ReviewIssueDeduplicator();

    @Test
    void shouldRunRulesInStableOrderAndAttachRuleReference() {
        DeterministicReviewToolRunner runner = new DeterministicReviewToolRunner(
                List.of(
                        rule("RULE_B", 20, result("BUG_RISK", "B finding")),
                        rule("RULE_A", 10, result("SECURITY", "A finding"))
                ),
                deduplicator
        );

        var reviewResult = runner.run(
                "src/main/java/Demo.java",
                "+class Demo {}",
                List.of("src/main/java/Demo.java"),
                "context"
        );

        assertThat(reviewResult.getIssues())
                .extracting("ruleReference")
                .containsExactly("RULE_A", "RULE_B");
        assertThat(reviewResult.getSummary()).contains("2");
    }

    @Test
    void shouldPreferResultRuleIdWhenRuleReturnsSpecificReference() {
        ToolCheckResult result = result("SECURITY", "Secret finding");
        result.setRuleId("SECRET_SCAN_RULE:GITHUB_TOKEN");
        DeterministicReviewToolRunner runner = new DeterministicReviewToolRunner(
                List.of(rule("SECRET_SCAN_RULE", 10, result)),
                deduplicator
        );

        var reviewResult = runner.run(
                "src/main/java/Demo.java",
                "+String token = \"ghp_1234567890abcdefghijklmnopqr\";",
                List.of("src/main/java/Demo.java"),
                "context"
        );

        assertThat(reviewResult.getIssues())
                .singleElement()
                .satisfies(issue -> assertThat(issue.getRuleReference())
                        .isEqualTo("SECRET_SCAN_RULE:GITHUB_TOKEN"));
    }

    @Test
    void shouldContinueWhenOneRuleFails() {
        DeterministicReviewToolRunner runner = new DeterministicReviewToolRunner(
                List.of(
                        failingRule("BROKEN_RULE", 1),
                        rule("WORKING_RULE", 2, result("TEST_MISSING", "Missing tests"))
                ),
                deduplicator
        );

        var reviewResult = runner.run(
                "src/main/java/DemoService.java",
                "+public void save() {}",
                List.of("src/main/java/DemoService.java"),
                "context"
        );

        assertThat(reviewResult.getIssues())
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.getIssueType()).isEqualTo("TEST_MISSING");
                    assertThat(issue.getRuleReference()).isEqualTo("WORKING_RULE");
                });
    }

    private DeterministicReviewRule rule(String id, int order, ToolCheckResult result) {
        return new DeterministicReviewRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public List<ToolCheckResult> check(ToolCheckRequest request) {
                return List.of(result);
            }
        };
    }

    private DeterministicReviewRule failingRule(String id, int order) {
        return new DeterministicReviewRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public List<ToolCheckResult> check(ToolCheckRequest request) {
                throw new IllegalStateException("token=ghp_123456789012345678901234567890123456");
            }
        };
    }

    private ToolCheckResult result(String issueType, String title) {
        return ToolCheckResult.atLine(
                12,
                issueType,
                "HIGH",
                title,
                "description",
                "suggestion"
        );
    }
}
