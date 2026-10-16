package com.codepilot.module.review.metrics;

import com.codepilot.infrastructure.llm.LlmProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R10：LLM 相关的可观测性指标。
 *
 * <p>最要紧的两条不是"计数器加了没有"，而是两个<b>容易做错、错了还会误导人</b>的点：</p>
 * <ol>
 *   <li>拿不到 token 数时<b>不能上报 0</b>——那看起来像"这次调用不花 token"；</li>
 *   <li>没有单价时 cost 必须是 {@code unavailable} 而<b>不是 0</b>——
 *       错的钱数比没有钱数更可信，因而更危险。</li>
 * </ol>
 */
class ReviewLlmMetricsTest {

    private SimpleMeterRegistry registry;

    private LlmProperties properties;

    private ReviewLlmMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        properties = new LlmProperties();
        metrics = new ReviewLlmMetrics(registry, properties);
    }

    @Test
    void shouldCountLlmCacheHitsAndMissesSeparately() {
        metrics.recordLlmCache(true);
        metrics.recordLlmCache(true);
        metrics.recordLlmCache(false);

        assertThat(counter("codepilot.review.llm.cache", "result", "hit")).isEqualTo(2);
        assertThat(counter("codepilot.review.llm.cache", "result", "miss")).isEqualTo(1);
    }

    /** RAG 缓存与 LLM 缓存必须是两条独立的序列，否则无法判断省下的 token 从哪来。 */
    @Test
    void shouldKeepRagCacheSeparateFromTheLlmCache() {
        metrics.recordRagCache(false);

        assertThat(counter("codepilot.review.rag.cache", "result", "miss")).isEqualTo(1);
        assertThat(counter("codepilot.review.llm.cache", "result", "miss")).isZero();
    }

    @Test
    void shouldCountInputAndOutputTokens() {
        metrics.recordUsage("gpt-4o-mini", 1200, 300);

        assertThat(counter("codepilot.review.llm.tokens", "kind", "input")).isEqualTo(1200);
        assertThat(counter("codepilot.review.llm.tokens", "kind", "output")).isEqualTo(300);
    }

    /** provider 没返回某个方向的 token 时，那条序列就不该出现——更不该是 0。 */
    @Test
    void shouldNotEmitATokenSeriesWhenTheProviderDidNotReportIt() {
        metrics.recordUsage("gpt-4o-mini", null, 300);

        assertThat(counter("codepilot.review.llm.tokens", "kind", "output")).isEqualTo(300);
        assertThat(registry.find("codepilot.review.llm.tokens").tag("kind", "input").counter()).isNull();
    }

    @Test
    void shouldReportCostAsUnavailableWhenNoPriceIsConfigured() {
        metrics.recordUsage("gpt-4o-mini", 1000, 1000);

        assertThat(counter("codepilot.review.cost.status", "status",
                ReviewLlmMetrics.COST_STATUS_UNAVAILABLE)).isEqualTo(1);
        // 关键：不能出现一条金额为 0 的 cost 序列，否则看起来像"这次不花钱"。
        assertThat(registry.find("codepilot.review.cost").counter()).isNull();
    }

    @Test
    void shouldPriceTokensWhenAPriceIsConfigured() {
        properties.getPricing().put("deepseek-chat", price(0.27D, 1.10D));

        metrics.recordUsage("deepseek-chat", 1_000_000, 1_000_000);

        assertThat(registry.find("codepilot.review.cost").tag("model", "deepseek-chat").counter().count())
                .isCloseTo(1.37D, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(counter("codepilot.review.cost.status", "status",
                ReviewLlmMetrics.COST_STATUS_ESTIMATED)).isEqualTo(1);
    }

    @Test
    void shouldPriceOnlyTheConfiguredModel() {
        properties.getPricing().put("priced-model", price(1D, 2D));

        metrics.recordUsage("priced-model", 1_000_000, 0);
        metrics.recordUsage("unpriced-model", 1_000_000, 0);

        assertThat(registry.find("codepilot.review.cost").tag("model", "priced-model").counter().count())
                .isCloseTo(1D, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(counter("codepilot.review.cost.status", "status",
                ReviewLlmMetrics.COST_STATUS_UNAVAILABLE)).isEqualTo(1);
        assertThat(registry.find("codepilot.review.cost").tag("model", "unpriced-model").counter()).isNull();
    }

    /** 一个 token 数都没拿到时，不应把它算成 unavailable——那是"没单价"的含义，不是"没用量"。 */
    @Test
    void shouldRecordNothingWhenNoUsageAtAllWasReported() {
        properties.getPricing().put("deepseek-chat", price(1D, 1D));

        metrics.recordUsage("deepseek-chat", null, null);

        assertThat(registry.find("codepilot.review.cost.status").counter()).isNull();
        assertThat(registry.find("codepilot.review.cost").counter()).isNull();
    }

    @Test
    void shouldTolerateUnknownModelNames() {
        metrics.recordUsage(null, 10, 10);
        metrics.recordUsage("  ", 10, 10);

        assertThat(counter("codepilot.review.cost.status", "status",
                ReviewLlmMetrics.COST_STATUS_UNAVAILABLE)).isEqualTo(2);
    }

    private static LlmProperties.ModelPrice price(double input, double output) {
        LlmProperties.ModelPrice price = new LlmProperties.ModelPrice();
        price.setInputPerMillion(input);
        price.setOutputPerMillion(output);
        return price;
    }

    private double counter(String name, String tagKey, String tagValue) {
        Counter counter = registry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0D : counter.count();
    }
}
