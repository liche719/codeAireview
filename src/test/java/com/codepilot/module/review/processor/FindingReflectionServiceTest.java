package com.codepilot.module.review.processor;

import com.codepilot.module.agent.service.CodeReviewReflectionAssistant;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewIssue;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R18：自省闸门。
 *
 * <p>两条最容易做错的：自省<b>失败时默认丢弃</b>（与 PR-Agent 相反），
 * 以及只自省 <b>LLM</b> finding——让模型去复核确定性规则既浪费 token，
 * 又削弱"确定性规则可信"的立场。</p>
 */
class FindingReflectionServiceTest {

    private CodeReviewReflectionAssistant assistant;

    private ReviewProperties properties;

    private FindingReflectionService service;

    @BeforeEach
    void setUp() {
        assistant = mock(CodeReviewReflectionAssistant.class);
        properties = enabled();
        service = new FindingReflectionService(provider(assistant), properties);
    }

    @Test
    void shouldSuppressWhenScoreIsBelowThreshold() {
        givenScore("{\"score\": 3}");

        ReviewIssue issue = llmIssue();
        service.reflect("src/Demo.java", patch(), List.of(issue));

        assertThat(issue.getReflectionScore()).isEqualTo(3);
        assertThat(issue.getReflectionStatus()).isEqualTo(FindingReflectionService.STATUS_SCORED);
        assertThat(issue.getPublishDecision()).isEqualTo("SUPPRESS");
        assertThat(issue.getSuppressionReason()).isEqualTo(FindingReflectionService.REASON_BELOW_THRESHOLD);
    }

    /** 阈值边界：等于阈值保留，低于阈值丢弃。 */
    @Test
    void shouldKeepAtTheThresholdAndDropBelowIt() {
        givenScore("{\"score\": 5}");
        ReviewIssue atThreshold = llmIssue();
        service.reflect("src/Demo.java", patch(), List.of(atThreshold));
        assertThat(atThreshold.getPublishDecision()).isNull();

        givenScore("{\"score\": 4}");
        ReviewIssue belowThreshold = llmIssue();
        service.reflect("src/Demo.java", patch(), List.of(belowThreshold));
        assertThat(belowThreshold.getPublishDecision()).isEqualTo("SUPPRESS");
    }

    /**
     * 自省调用失败 → 取 {@code score-on-reflection-failure}（默认 0）→ 丢弃。
     *
     * <p>这是与 PR-Agent 取 7（失败时保留）刻意相反的选择。</p>
     */
    @Test
    void shouldDropWhenReflectionCallFails() {
        when(assistant.score(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("llm timeout"));

        ReviewIssue issue = llmIssue();
        service.reflect("src/Demo.java", patch(), List.of(issue));

        assertThat(issue.getReflectionStatus()).isEqualTo(FindingReflectionService.STATUS_FAILED);
        assertThat(issue.getReflectionScore()).isZero();
        assertThat(issue.getPublishDecision()).isEqualTo("SUPPRESS");
    }

    /** 输出无法解析同样按失败处理，不能"猜一个分"通过。 */
    @Test
    void shouldTreatUnparseableOutputAsFailure() {
        givenScore("抱歉，我无法判断");

        ReviewIssue issue = llmIssue();
        service.reflect("src/Demo.java", patch(), List.of(issue));

        assertThat(issue.getReflectionStatus()).isEqualTo(FindingReflectionService.STATUS_FAILED);
        assertThat(issue.getPublishDecision()).isEqualTo("SUPPRESS");
    }

    /** 越界分数要夹到 0–10，否则 11 分会被当成"通过"。 */
    @Test
    void shouldClampOutOfRangeScores() {
        assertThat(FindingReflectionService.parseScore("{\"score\": 11}")).isEqualTo(10);
        assertThat(FindingReflectionService.parseScore("{\"score\": -3}")).isZero();
        assertThat(FindingReflectionService.parseScore("7")).isEqualTo(7);
    }

    /** 关闭时不发起任何调用，finding 原样返回。 */
    @Test
    void shouldNotCallTheModelWhenDisabled() {
        ReviewProperties disabled = new ReviewProperties();
        FindingReflectionService off = new FindingReflectionService(provider(assistant), disabled);

        ReviewIssue issue = llmIssue();
        off.reflect("src/Demo.java", patch(), List.of(issue));

        verify(assistant, never()).score(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(issue.getReflectionScore()).isNull();
        assertThat(issue.getPublishDecision()).isNull();
    }

    /**
     * 确定性规则的 finding 不参与自省。
     *
     * <p>它们是可枚举的模式匹配，让模型复核既浪费 token，也削弱"确定性规则可信"的立场。</p>
     */
    @Test
    void shouldSkipDeterministicFindings() {
        ReviewIssue toolIssue = llmIssue();
        toolIssue.setSource("TOOL");

        service.reflect("src/Demo.java", patch(), List.of(toolIssue));

        verify(assistant, never()).score(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(toolIssue.getReflectionStatus()).isEqualTo(FindingReflectionService.STATUS_SKIPPED);
        assertThat(toolIssue.getPublishDecision()).isNull();
    }

    /** 超出条数预算的 finding 标 SKIPPED 但<b>绝不</b>因此被抑制——"没配额"不等于"不该发"。 */
    @Test
    void shouldNotSuppressFindingsBeyondTheReflectionBudget() {
        properties.setReflectionMaxFindings(1);
        givenScore("{\"score\": 9}");

        ReviewIssue first = llmIssue();
        ReviewIssue second = llmIssue();
        service.reflect("src/Demo.java", patch(), List.of(first, second));

        assertThat(first.getReflectionStatus()).isEqualTo(FindingReflectionService.STATUS_SCORED);
        assertThat(second.getReflectionStatus()).isEqualTo(FindingReflectionService.STATUS_SKIPPED);
        assertThat(second.getPublishDecision()).isNull();
    }

    /** 已有其它抑制原因时不覆盖——否则会把"已被维护者关闭"错写成"评分过低"。 */
    @Test
    void shouldNotOverwriteAnExistingSuppressionReason() {
        givenScore("{\"score\": 1}");
        ReviewIssue issue = llmIssue();
        issue.setPublishDecision("SUPPRESS");
        issue.setSuppressionReason("dismissed by a maintainer");

        service.reflect("src/Demo.java", patch(), List.of(issue));

        assertThat(issue.getSuppressionReason()).isEqualTo("dismissed by a maintainer");
    }

    /** 助手不可用时整段跳过，不影响 finding 的发布。 */
    @Test
    void shouldDoNothingWhenAssistantIsUnavailable() {
        FindingReflectionService noAssistant = new FindingReflectionService(provider(null), properties);
        ReviewIssue issue = llmIssue();

        noAssistant.reflect("src/Demo.java", patch(), List.of(issue));

        assertThat(issue.getReflectionScore()).isNull();
        assertThat(issue.getPublishDecision()).isNull();
    }

    private void givenScore(String content) {
        when(assistant.score(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new Result<>(content, null, List.of(), null, List.of()));
    }

    private ReviewProperties enabled() {
        ReviewProperties reviewProperties = new ReviewProperties();
        reviewProperties.setReflectionEnabled(true);
        reviewProperties.setSuggestionsScoreThreshold(5);
        reviewProperties.setScoreOnReflectionFailure(0);
        reviewProperties.setReflectionMaxFindings(10);
        return reviewProperties;
    }

    private static ReviewIssue llmIssue() {
        ReviewIssue issue = new ReviewIssue();
        issue.setFilePath("src/Demo.java");
        issue.setLineNumber(12);
        issue.setIssueType("BUG_RISK");
        issue.setSeverity("HIGH");
        issue.setTitle("可能的空指针");
        issue.setDescription("这里没有判空");
        issue.setSource("LLM");
        return issue;
    }

    private static String patch() {
        return "@@ -10,3 +10,4 @@\n public void run() {\n+    String value = null;\n+    log.info(value.length());\n }";
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CodeReviewReflectionAssistant> provider(CodeReviewReflectionAssistant assistant) {
        ObjectProvider<CodeReviewReflectionAssistant> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(assistant);
        return provider;
    }
}
