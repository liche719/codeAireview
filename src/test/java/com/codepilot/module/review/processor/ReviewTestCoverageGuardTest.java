package com.codepilot.module.review.processor;

import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「PR 里补了测试，模型还说缺测试」的确定性拦截（2026-10-29）。
 *
 * <p>规则很窄：只拦 **LLM 报的 TEST_MISSING**，且只在**本次 PR 的变更文件里存在同名测试**时才拦。
 * 确定性工具报的 TEST_MISSING 不碰——那是它自己扫出来的结论，规则不该越权改。
 */
class ReviewTestCoverageGuardTest {

    private static ReviewFile file(String path) {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setFilePath(path);
        return reviewFile;
    }

    private static ReviewIssue issue(String source, String type, String path) {
        ReviewIssue issue = new ReviewIssue();
        issue.setSource(source);
        issue.setIssueType(type);
        issue.setFilePath(path);
        issue.setTitle("缺少测试");
        return issue;
    }

    @Test
    @DisplayName("PR 同时改了 BillingService 和 BillingServiceTest：丢掉 LLM 的 TEST_MISSING")
    void suppressesWhenMatchingTestIsPartOfThePullRequest() {
        List<ReviewIssue> issues = List.of(
                issue("LLM", "TEST_MISSING", "src/main/java/com/example/service/BillingService.java"));
        List<ReviewFile> files = List.of(
                file("src/main/java/com/example/service/BillingService.java"),
                file("src/test/java/com/example/service/BillingServiceTest.java"));

        List<ReviewIssue> kept = ReviewTestCoverageGuard.suppressUnfoundedTestMissing(issues, files);

        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("PR 没带测试：模型报的 TEST_MISSING 保留")
    void keepsWhenNoTestInThePullRequest() {
        List<ReviewIssue> issues = List.of(
                issue("LLM", "TEST_MISSING", "src/main/java/com/example/service/BillingService.java"));
        List<ReviewFile> files = List.of(file("src/main/java/com/example/service/BillingService.java"));

        assertThat(ReviewTestCoverageGuard.suppressUnfoundedTestMissing(issues, files)).hasSize(1);
    }

    @Test
    @DisplayName("确定性工具报的 TEST_MISSING 一律不碰")
    void neverTouchesDeterministicFindings() {
        List<ReviewIssue> issues = List.of(
                issue("TOOL", "TEST_MISSING", "src/main/java/com/example/service/BillingService.java"));
        List<ReviewFile> files = List.of(
                file("src/main/java/com/example/service/BillingService.java"),
                file("src/test/java/com/example/service/BillingServiceTest.java"));

        assertThat(ReviewTestCoverageGuard.suppressUnfoundedTestMissing(issues, files)).hasSize(1);
    }

    @Test
    @DisplayName("测试文件名字对不上时不拦（避免误伤无关测试）")
    void ignoresUnrelatedTests() {
        List<ReviewIssue> issues = List.of(
                issue("LLM", "TEST_MISSING", "src/main/java/com/example/service/BillingService.java"));
        List<ReviewFile> files = List.of(
                file("src/main/java/com/example/service/BillingService.java"),
                file("src/test/java/com/example/service/OrderServiceTest.java"));

        assertThat(ReviewTestCoverageGuard.suppressUnfoundedTestMissing(issues, files)).hasSize(1);
    }

    @Test
    @DisplayName("非 TEST_MISSING 的问题原样保留，顺序不变")
    void keepsOtherIssuesInOrder() {
        List<ReviewIssue> issues = List.of(
                issue("LLM", "BUG_RISK", "src/main/java/com/example/service/BillingService.java"),
                issue("LLM", "TEST_MISSING", "src/main/java/com/example/service/BillingService.java"),
                issue("LLM", "SECURITY", "src/main/java/com/example/service/BillingService.java"));
        List<ReviewFile> files = List.of(
                file("src/main/java/com/example/service/BillingService.java"),
                file("src/test/java/com/example/service/BillingServiceTest.java"));

        List<ReviewIssue> kept = ReviewTestCoverageGuard.suppressUnfoundedTestMissing(issues, files);

        assertThat(kept).hasSize(2);
        assertThat(kept.get(0).getIssueType()).isEqualTo("BUG_RISK");
        assertThat(kept.get(1).getIssueType()).isEqualTo("SECURITY");
    }
}
