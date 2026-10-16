package com.codepilot.module.agent.review;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.service.CodeReviewAiAssistant;
import com.codepilot.module.review.metrics.ReviewLlmMetrics;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangChain4jReviewLlmClientTest {

    @Test
    void shouldDelegateReviewInputToLangChain4jAssistant() {
        CodeReviewAiAssistant assistant = mock(CodeReviewAiAssistant.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CodeReviewAiAssistant> assistantProvider = mock(ObjectProvider.class);
        when(assistantProvider.getIfAvailable()).thenReturn(assistant);
        when(assistantProvider.getObject()).thenReturn(assistant);
        when(assistant.review("src/Demo.java", "+code", "rules", "files"))
                .thenReturn(new Result<>("{}", null, List.of(), null, List.of()));
        LangChain4jReviewLlmClient client = new LangChain4jReviewLlmClient(assistantProvider);

        String response = client.review(new ReviewLlmInput(
                "src/Demo.java",
                "+code",
                "rules",
                "files",
                false,
                false
        ));

        assertThat(client.providerName()).isEqualTo("openai-compatible");
        assertThat(client.supports("OPENAI-COMPATIBLE")).isTrue();
        assertThat(client.isAvailable()).isTrue();
        assertThat(response).isEqualTo("{}");
        verify(assistant).review("src/Demo.java", "+code", "rules", "files");
    }

    @Test
    void shouldReportUnavailableWhenAssistantBeanIsMissing() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CodeReviewAiAssistant> assistantProvider = mock(ObjectProvider.class);
        when(assistantProvider.getIfAvailable()).thenReturn(null);
        LangChain4jReviewLlmClient client = new LangChain4jReviewLlmClient(assistantProvider);

        assertThat(client.isAvailable()).isFalse();
    }

    /**
     * R10 的接线断言：{@code Result} 上携带的 token 用量必须真的被上报。
     *
     * <p>只测 {@code ReviewLlmMetrics} 本身不够——那样证明不了这条数据从 provider
     * 一路走到了指标里。</p>
     */
    @Test
    void shouldReportTokenUsageCarriedByTheResult() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmProperties properties = new LlmProperties();
        properties.setModel("deepseek-chat");
        ReviewLlmMetrics metrics = new ReviewLlmMetrics(registry, properties);
        LangChain4jReviewLlmClient client = clientReturning(
                new Result<>("{}", new TokenUsage(1200, 300), List.of(), null, List.of()),
                metrics, properties);

        client.review(reviewInput());

        assertThat(counter(registry, "codepilot.review.llm.tokens", "kind", "input")).isEqualTo(1200);
        assertThat(counter(registry, "codepilot.review.llm.tokens", "kind", "output")).isEqualTo(300);
    }

    /** provider 不返回 usage 时不上报——不能把"不知道"报成 0。 */
    @Test
    void shouldNotReportTokensWhenTheProviderGaveNoUsage() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmProperties properties = new LlmProperties();
        ReviewLlmMetrics metrics = new ReviewLlmMetrics(registry, properties);
        LangChain4jReviewLlmClient client = clientReturning(
                new Result<>("{}", null, List.of(), null, List.of()), metrics, properties);

        client.review(reviewInput());

        assertThat(registry.find("codepilot.review.llm.tokens").counter()).isNull();
        assertThat(registry.find("codepilot.review.cost.status").counter()).isNull();
    }

    /** 缺指标依赖（手工 new 的场景）只应降级为不上报，不能抛。 */
    @Test
    void shouldStillReturnContentWhenMetricsAreAbsent() {
        CodeReviewAiAssistant assistant = mock(CodeReviewAiAssistant.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CodeReviewAiAssistant> assistantProvider = mock(ObjectProvider.class);
        when(assistantProvider.getObject()).thenReturn(assistant);
        when(assistant.review(any(), any(), any(), any()))
                .thenReturn(new Result<>("{}", new TokenUsage(1, 1), List.of(), null, List.of()));

        String response = new LangChain4jReviewLlmClient(assistantProvider).review(reviewInput());

        assertThat(response).isEqualTo("{}");
    }

    private static LangChain4jReviewLlmClient clientReturning(
            Result<String> result,
            ReviewLlmMetrics metrics,
            LlmProperties properties
    ) {
        CodeReviewAiAssistant assistant = mock(CodeReviewAiAssistant.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CodeReviewAiAssistant> assistantProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewLlmMetrics> metricsProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmProperties> propertiesProvider = mock(ObjectProvider.class);
        when(assistantProvider.getObject()).thenReturn(assistant);
        when(assistant.review(any(), any(), any(), any())).thenReturn(result);
        when(metricsProvider.getIfAvailable()).thenReturn(metrics);
        when(propertiesProvider.getIfAvailable()).thenReturn(properties);
        return new LangChain4jReviewLlmClient(assistantProvider, metricsProvider, propertiesProvider);
    }

    private static ReviewLlmInput reviewInput() {
        return new ReviewLlmInput("src/Demo.java", "+code", "rules", "files", false, false);
    }

    private static double counter(SimpleMeterRegistry registry, String name, String tagKey, String tagValue) {
        return registry.find(name).tag(tagKey, tagValue).counter().count();
    }
}
