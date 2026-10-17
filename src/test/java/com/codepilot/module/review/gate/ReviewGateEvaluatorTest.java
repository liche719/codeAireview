package com.codepilot.module.review.gate;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R17：门禁判定。
 *
 * <p>这里最要紧的一条是"LLM finding 不参与门禁"——用不确定的输出阻塞他人合并，
 * 是这个能力最可能被误用的方式，也是它一旦出错就会被直接撤掉的原因。</p>
 */
class ReviewGateEvaluatorTest {

    @Test
    void shouldNotWriteStatusWhenGateIsDisabled() {
        ReviewProperties properties = new ReviewProperties();

        ReviewGateEvaluator.GateDecision decision = evaluator(properties).evaluate(
                new ReviewGateEvaluator.ReviewTaskFacts(List.of(issue("HIGH", "TOOL"))));

        assertThat(properties.isGateEnabled()).as("默认必须关闭").isFalse();
        assertThat(decision.writeStatus()).isFalse();
        assertThat(decision.decision()).isEqualTo(ReviewGateEvaluator.DECISION_NEUTRAL);
    }

    /** 有 finding 但都没到阈值 → 不阻塞。这是对标工具刻意保留的"有发现 ≠ 该阻塞"。 */
    @Test
    void shouldStayNeutralWhenNoFindingReachesTheThreshold() {
        ReviewProperties properties = gateEnabled("HIGH");

        ReviewGateEvaluator.GateDecision decision = evaluator(properties).evaluate(
                new ReviewGateEvaluator.ReviewTaskFacts(List.of(
                        issue("MEDIUM", "TOOL"), issue("LOW", "TOOL"))));

        assertThat(decision.decision()).isEqualTo(ReviewGateEvaluator.DECISION_NEUTRAL);
        assertThat(decision.commitStatus()).isEqualTo(ReviewGateEvaluator.STATUS_SUCCESS);
    }

    @Test
    void shouldFailOnDeterministicFindingAtOrAboveTheThreshold() {
        ReviewProperties properties = gateEnabled("HIGH");

        ReviewGateEvaluator.GateDecision decision = evaluator(properties).evaluate(
                new ReviewGateEvaluator.ReviewTaskFacts(List.of(issue("HIGH", "TOOL"))));

        assertThat(decision.decision()).isEqualTo(ReviewGateEvaluator.DECISION_FAILURE);
        assertThat(decision.commitStatus()).isEqualTo(ReviewGateEvaluator.STATUS_FAILURE);
        assertThat(decision.reason()).contains("HIGH");
    }

    /**
     * 关键回归：LLM finding 即使 severity 很高也不能阻塞。
     *
     * <p>离线评估只有 6 个样本、Precision 85.71%；用它卡合并是不负责任的。</p>
     */
    @Test
    void shouldIgnoreLlmFindingsByDefault() {
        ReviewProperties properties = gateEnabled("HIGH");

        ReviewGateEvaluator.GateDecision decision = evaluator(properties).evaluate(
                new ReviewGateEvaluator.ReviewTaskFacts(List.of(issue("HIGH", "LLM"))));

        assertThat(properties.isGateIncludeLlmFindings()).as("默认不参与").isFalse();
        assertThat(decision.decision()).isEqualTo(ReviewGateEvaluator.DECISION_NEUTRAL);
    }

    /** 显式打开后 LLM finding 才参与——这是运维的选择，不是默认。 */
    @Test
    void shouldIncludeLlmFindingsWhenExplicitlyEnabled() {
        ReviewProperties properties = gateEnabled("HIGH");
        properties.setGateIncludeLlmFindings(true);

        ReviewGateEvaluator.GateDecision decision = evaluator(properties).evaluate(
                new ReviewGateEvaluator.ReviewTaskFacts(List.of(issue("HIGH", "LLM"))));

        assertThat(decision.decision()).isEqualTo(ReviewGateEvaluator.DECISION_FAILURE);
    }

    @Test
    void shouldSucceedWhenThereAreNoFindings() {
        ReviewProperties properties = gateEnabled("HIGH");

        ReviewGateEvaluator.GateDecision decision = evaluator(properties).evaluate(
                new ReviewGateEvaluator.ReviewTaskFacts(List.of()));

        assertThat(decision.decision()).isEqualTo(ReviewGateEvaluator.DECISION_SUCCESS);
        assertThat(decision.commitStatus()).isEqualTo(ReviewGateEvaluator.STATUS_SUCCESS);
    }

    /**
     * 认不出的 severity 不能阻塞。
     *
     * <p>误阻塞会挡住别人的工作；漏阻塞只是回到"没有门禁"的默认状态，方向明确。</p>
     */
    @Test
    void shouldNotBlockOnAnUnrecognizedSeverity() {
        ReviewProperties properties = gateEnabled("HIGH");

        ReviewGateEvaluator.GateDecision decision = evaluator(properties).evaluate(
                new ReviewGateEvaluator.ReviewTaskFacts(List.of(issue("CRITICAL", "TOOL"), issue(null, "TOOL"))));

        assertThat(decision.decision()).isEqualTo(ReviewGateEvaluator.DECISION_NEUTRAL);
    }

    /** 阈值可调：设成 MEDIUM 时 MEDIUM 也能触发。 */
    @Test
    void shouldRespectAConfigurableThreshold() {
        ReviewProperties properties = gateEnabled("MEDIUM");

        ReviewGateEvaluator.GateDecision decision = evaluator(properties).evaluate(
                new ReviewGateEvaluator.ReviewTaskFacts(List.of(issue("MEDIUM", "TOOL"))));

        assertThat(decision.decision()).isEqualTo(ReviewGateEvaluator.DECISION_FAILURE);
    }

    /**
     * commit status 只有 error/failure/pending/success，<b>没有 neutral</b>
     * （那是 Checks API 的 conclusion）。NEUTRAL 必须映射成 success 才是"不阻塞"。
     */
    @Test
    void shouldMapDecisionsOntoTheCommitStatusVocabulary() {
        List<String> commitStatusStates = List.of("error", "failure", "pending", "success");

        assertThat(ReviewGateEvaluator.toCommitStatus(ReviewGateEvaluator.DECISION_FAILURE)).isEqualTo("failure");
        assertThat(ReviewGateEvaluator.toCommitStatus(ReviewGateEvaluator.DECISION_NEUTRAL)).isEqualTo("success");
        assertThat(ReviewGateEvaluator.toCommitStatus(ReviewGateEvaluator.DECISION_SUCCESS)).isEqualTo("success");
        assertThat(commitStatusStates)
                .as("映射结果必须落在 commit status 的合法取值内")
                .contains(ReviewGateEvaluator.toCommitStatus(ReviewGateEvaluator.DECISION_NEUTRAL));
        // 关键：commit status 词表里没有 neutral（那是 Checks API 的 conclusion），
        // 所以本项目的 NEUTRAL 只能映射成 success。有人"顺手"改成 neutral 会直接 422。
        assertThat(commitStatusStates)
                .as("commit status 没有 neutral 这一取值")
                .doesNotContain("neutral");
        assertThat(ReviewGateEvaluator.toCommitStatus(ReviewGateEvaluator.DECISION_NEUTRAL)).isNotEqualTo("neutral");
    }

    private ReviewGateEvaluator evaluator(ReviewProperties properties) {
        return new ReviewGateEvaluator(properties);
    }

    private ReviewProperties gateEnabled(String minSeverity) {
        ReviewProperties properties = new ReviewProperties();
        properties.setGateEnabled(true);
        properties.setGateMinSeverity(minSeverity);
        return properties;
    }

    private ReviewIssue issue(String severity, String source) {
        ReviewIssue issue = new ReviewIssue();
        issue.setSeverity(severity);
        issue.setSource(source);
        issue.setIssueType("BUG_RISK");
        return issue;
    }
}
