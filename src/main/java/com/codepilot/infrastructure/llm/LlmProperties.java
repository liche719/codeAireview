package com.codepilot.infrastructure.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.llm")
public class LlmProperties {

    private boolean enabled = true;

    private String provider = "openai-compatible";

    private String baseUrl = "https://api.openai.com/v1";

    private String apiKey = "";

    private String model = "gpt-4o-mini";

    private double temperature = 0.2D;

    private int timeoutSeconds = 60;

    private int maxReviewPatchChars = 12000;

    private int maxReviewRulesChars = 4000;

    private int maxReviewContextChars = 8000;

    private boolean reviewStructuredOutputEnabled = true;

    private boolean reviewCacheEnabled = true;

    private int reviewCacheTtlDays = 7;

    private boolean reviewCacheCleanupEnabled = true;

    private boolean callLogCleanupEnabled = true;

    private int callLogRetentionDays = 30;

    /**
     * 模型单价表，用于把 token 数换算成金额（R10）。
     *
     * <p><b>刻意默认为空。</b>单价随厂商调价而变化，在代码里内置一份"当前价格"必然会在某个时间点
     * 悄悄变成错的——而错的钱数比没有钱数更糟：它看起来是可信的。
     * 因此这里不给任何默认值，只有运维显式配置了单价才会产生 cost 指标；
     * 未配置时 cost 状态为 {@code unavailable}，而不是 0。</p>
     *
     * <p>配置形态：{@code codepilot.llm.pricing.<model>.input-per-million} /
     * {@code .output-per-million}（单位：美元 / 百万 token）。</p>
     */
    private Map<String, ModelPrice> pricing = new LinkedHashMap<>();

    /** 单个模型的单价，单位美元 / 百万 token。 */
    @Data
    public static class ModelPrice {

        private double inputPerMillion;

        private double outputPerMillion;
    }
}
