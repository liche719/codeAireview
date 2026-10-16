package com.codepilot.module.agent.review;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.service.CodeReviewAiAssistant;
import com.codepilot.module.review.metrics.ReviewLlmMetrics;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class LangChain4jReviewLlmClient implements ReviewLlmClient {

    private static final String PROVIDER_NAME = "openai-compatible";

    private final ObjectProvider<CodeReviewAiAssistant> codeReviewAiAssistantProvider;

    /**
     * 用 {@code ObjectProvider} 注入：这个类在若干测试里是直接 new 出来的，
     * 缺少这两个依赖时应当只是"不上报指标"，而不是构造失败。
     */
    private final ObjectProvider<ReviewLlmMetrics> reviewLlmMetricsProvider;

    private final ObjectProvider<LlmProperties> llmPropertiesProvider;

    /**
     * 只要 assistant 的便捷构造：手工 new 的场景（测试）不需要指标上报。
     * 与仓库里其它"测试用重载"一致——缺依赖时只降级为不上报，而不是构造失败。
     */
    public LangChain4jReviewLlmClient(ObjectProvider<CodeReviewAiAssistant> codeReviewAiAssistantProvider) {
        this(codeReviewAiAssistantProvider, null, null);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean supports(String provider) {
        return PROVIDER_NAME.equals(normalize(provider));
    }

    @Override
    public boolean isAvailable() {
        return codeReviewAiAssistantProvider.getIfAvailable() != null;
    }

    @Override
    public String review(ReviewLlmInput input) {
        CodeReviewAiAssistant codeReviewAiAssistant = codeReviewAiAssistantProvider.getObject();
        Result<String> result = codeReviewAiAssistant.review(
                input.filePath(),
                input.patch(),
                input.rulesContext(),
                input.changedFilesContext()
        );
        if (result == null) {
            return null;
        }
        // token 用量只有这一层能看到（LangChain4j 的 Result 携带 usage），
        // 所以在这里上报，而不是把它一路透传到上层。provider 不返回 usage 时
        // TokenUsage 为 null，此时不上报任何 token —— 而不是上报 0。
        recordUsage(result);
        return result.content();
    }

    private void recordUsage(Result<String> result) {
        TokenUsage usage = result.tokenUsage();
        if (usage == null) {
            return;
        }
        ReviewLlmMetrics metrics = reviewLlmMetricsProvider == null
                ? null
                : reviewLlmMetricsProvider.getIfAvailable();
        if (metrics == null) {
            return;
        }
        LlmProperties properties = llmPropertiesProvider == null
                ? null
                : llmPropertiesProvider.getIfAvailable();
        metrics.recordUsage(
                properties == null ? null : properties.getModel(),
                usage.inputTokenCount(),
                usage.outputTokenCount()
        );
    }

    private String normalize(String provider) {
        return StringUtils.hasText(provider)
                ? provider.trim().toLowerCase(Locale.ROOT)
                : "";
    }
}
