package com.codepilot.module.review.metrics;

import com.codepilot.infrastructure.llm.LlmProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * LLM 相关的可观测性指标（R10）。
 *
 * <h2>为什么 cache 与 token 分开暴露</h2>
 * 这两件事语义完全不同，混在一起会得出错误结论：
 * <ul>
 *   <li>{@code codepilot.review.llm.cache} 是<b>应用级响应缓存</b>（{@code ai_review_cache} 表，
 *       key 由 prompt 内容决定）的命中情况；</li>
 *   <li>{@code codepilot.review.llm.tokens} 是<b>真的调用了模型</b>时消耗的 token。</li>
 * </ul>
 * 缓存命中率上升会让 token 消耗下降——这正是要靠两个指标一起看才能确认的事。
 *
 * <h2>刻意不暴露的两个指标</h2>
 * <ol>
 *   <li><b>{@code cache_read} / {@code cache_creation} token tag。</b>
 *       PR-Agent 有这两个 tag，是因为它面向 Anthropic 的 provider 侧 prompt caching。
 *       我们的 provider 抽象是 LangChain4j 的 {@code TokenUsage}，它<b>只有</b>
 *       input / output / total。拿不到就是拿不到——<b>发一条恒为 0 的序列比不发更糟</b>，
 *       因为它看起来像"缓存一次都没命中"，而真相是"我们不知道"。</li>
 *   <li><b>内置的模型单价表。</b>见 {@link LlmProperties#getPricing()}：单价会变，
 *       内置一份必然会在某天变成错的，而错的钱数比没有钱数更可信、因而更危险。
 *       因此只有运维显式配置后才产生 cost。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ReviewLlmMetrics {

    /** cost 状态：算出了金额。 */
    public static final String COST_STATUS_ESTIMATED = "estimated";

    /** cost 状态：没有该模型的单价，金额未知——<b>不是 0</b>。 */
    public static final String COST_STATUS_UNAVAILABLE = "unavailable";

    private static final double TOKENS_PER_MILLION = 1_000_000D;

    private final MeterRegistry meterRegistry;

    private final LlmProperties llmProperties;

    /** 应用级 LLM 响应缓存的命中情况。 */
    public void recordLlmCache(boolean hit) {
        meterRegistry.counter("codepilot.review.llm.cache", "result", hit ? "hit" : "miss").increment();
    }

    /** RAG 召回缓存的命中情况。 */
    public void recordRagCache(boolean hit) {
        meterRegistry.counter("codepilot.review.rag.cache", "result", hit ? "hit" : "miss").increment();
    }

    /**
     * 记录一次真实模型调用的 token 用量，并在可定价时换算成金额。
     *
     * @param model         模型名，用于查单价与打 tag
     * @param inputTokens   输入 token；provider 未返回时传 {@code null}
     * @param outputTokens  输出 token；provider 未返回时传 {@code null}
     */
    public void recordUsage(String model, Integer inputTokens, Integer outputTokens) {
        if (inputTokens != null && inputTokens > 0) {
            meterRegistry.counter("codepilot.review.llm.tokens", "kind", "input").increment(inputTokens);
        }
        if (outputTokens != null && outputTokens > 0) {
            meterRegistry.counter("codepilot.review.llm.tokens", "kind", "output").increment(outputTokens);
        }
        recordCost(model, inputTokens, outputTokens);
    }

    private void recordCost(String model, Integer inputTokens, Integer outputTokens) {
        if (inputTokens == null && outputTokens == null) {
            // provider 一个 token 数都没给：没有可定价的对象，既不算 estimated 也不算 unavailable。
            return;
        }
        LlmProperties.ModelPrice price = priceFor(model);
        if (price == null) {
            // 明确区分"算不出来"与"算出来是 0"：缺单价时记 unavailable，绝不记 0。
            meterRegistry.counter("codepilot.review.cost.status", "status", COST_STATUS_UNAVAILABLE).increment();
            return;
        }
        double cost = safe(inputTokens) / TOKENS_PER_MILLION * price.getInputPerMillion()
                + safe(outputTokens) / TOKENS_PER_MILLION * price.getOutputPerMillion();
        meterRegistry.counter("codepilot.review.cost", "model", StringUtils.hasText(model) ? model : "unknown")
                .increment(cost);
        meterRegistry.counter("codepilot.review.cost.status", "status", COST_STATUS_ESTIMATED).increment();
    }

    private LlmProperties.ModelPrice priceFor(String model) {
        if (!StringUtils.hasText(model) || llmProperties == null || llmProperties.getPricing() == null) {
            return null;
        }
        return llmProperties.getPricing().get(model);
    }

    private int safe(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
