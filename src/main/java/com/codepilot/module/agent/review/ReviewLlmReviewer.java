package com.codepilot.module.agent.review;

import com.codepilot.common.util.PromptInputSanitizer;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.dto.ReviewRuleContext;
import com.codepilot.module.agent.parser.AiReviewResultParser;
import com.codepilot.module.agent.prompt.ReviewPromptBuilder;
import com.codepilot.module.agent.review.cache.ReviewLlmCache;
import com.codepilot.module.review.metrics.ReviewLlmMetrics;
import com.codepilot.module.agent.service.ReviewRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ReviewLlmReviewer {

    private final ReviewLlmClientRegistry reviewLlmClientRegistry;

    private final AiReviewResultParser aiReviewResultParser;

    private final ReviewRagService reviewRagService;

    private final ReviewPromptBuilder reviewPromptBuilder;

    private final ReviewLlmInputLimiter reviewLlmInputLimiter;

    private final ReviewLlmCallLogger reviewLlmCallLogger;

    private final ReviewLlmCache reviewLlmCache;

    /**
     * 可为 null：手工 new 出来的测试不需要指标，此时跳过上报而不是失败。
     */
    private final ReviewLlmMetrics reviewLlmMetrics;

    /**
     * 不带指标的便捷构造：手工 new 的场景（测试）不需要上报。
     * 缺依赖时只降级为不上报，而不是构造失败。
     */
    public ReviewLlmReviewer(
            ReviewLlmClientRegistry reviewLlmClientRegistry,
            AiReviewResultParser aiReviewResultParser,
            ReviewRagService reviewRagService,
            ReviewPromptBuilder reviewPromptBuilder,
            ReviewLlmInputLimiter reviewLlmInputLimiter,
            ReviewLlmCallLogger reviewLlmCallLogger,
            ReviewLlmCache reviewLlmCache
    ) {
        this(reviewLlmClientRegistry, aiReviewResultParser, reviewRagService, reviewPromptBuilder,
                reviewLlmInputLimiter, reviewLlmCallLogger, reviewLlmCache, null);
    }

    public Optional<AiReviewResult> review(
            AiReviewRequest request,
            String allChangedFilesText,
            int deterministicIssueCount
    ) {
        Long taskId = request == null ? null : request.taskId();
        String filePath = request == null ? null : request.filePath();
        String patch = request == null ? null : request.patch();

        ReviewLlmClient reviewLlmClient = reviewLlmClientRegistry.select().orElse(null);
        if (reviewLlmClient == null) {
            log.warn("Skip llm review because no ReviewLlmClient is configured, filePath={}, deterministicIssueCount={}",
                    filePath, deterministicIssueCount);
            return Optional.empty();
        }
        if (!reviewLlmClient.isAvailable()) {
            log.warn("Skip llm review because ReviewLlmClient is unavailable, provider={}, filePath={}, deterministicIssueCount={}",
                    reviewLlmClient.providerName(), filePath, deterministicIssueCount);
            return Optional.empty();
        }
        if (!reviewLlmInputLimiter.isPatchWithinBudget(patch)) {
            log.warn("Skip llm review because patch exceeds llm input budget, filePath={}, patchChars={}, deterministicIssueCount={}",
                    filePath, length(patch), deterministicIssueCount);
            return Optional.empty();
        }

        List<ReviewRuleContext> rules = reviewRagService.retrieveRelevantRules(filePath, patch);
        String rulesContext = reviewPromptBuilder.buildRulesContext(rules);
        ReviewLlmInput limitedInput = reviewLlmInputLimiter.limit(filePath, patch, rulesContext, allChangedFilesText);
        log.info("AI review RAG context prepared, filePath={}, ruleCount={}, contextChars={}",
                filePath, rules.size(), rulesContext == null ? 0 : rulesContext.length());
        if (limitedInput.truncatedRulesContext() || limitedInput.truncatedChangedFilesContext()) {
            log.info("AI review input was truncated by budget, filePath={}, rulesTruncated={}, changedFilesTruncated={}",
                    filePath,
                    limitedInput.truncatedRulesContext(),
                    limitedInput.truncatedChangedFilesContext());
        }

        ReviewLlmInput promptSafeInput = promptSafe(limitedInput);
        Optional<AiReviewResult> cachedResult = reviewLlmCache.find(reviewLlmClient.providerName(), promptSafeInput);
        if (reviewLlmMetrics != null) {
            // 只在上报点计数，缓存实现本身不需要知道指标的存在。
            reviewLlmMetrics.recordLlmCache(cachedResult.isPresent());
        }
        if (cachedResult.isPresent()) {
            return cachedResult;
        }

        String responseText = null;
        String errorMessage = null;
        boolean success = false;
        long startTime = System.currentTimeMillis();

        try {
            responseText = reviewLlmClient.review(promptSafeInput);
            if (!StringUtils.hasText(responseText)) {
                errorMessage = "empty model response";
                log.warn("LLM review returned empty content, provider={}, filePath={}",
                        reviewLlmClient.providerName(), filePath);
                throw new IllegalStateException(errorMessage);
            }

            AiReviewResult parsedResult = normalizeLlmSources(aiReviewResultParser.parse(responseText));
            reviewLlmCache.save(reviewLlmClient.providerName(), promptSafeInput, parsedResult);
            success = true;
            return Optional.of(parsedResult);
        } catch (Exception exception) {
            errorMessage = SensitiveDataSanitizer.redact(exception.getMessage());
            log.warn("LLM review failed, provider={}, filePath={}, errorType={}, message={}",
                    reviewLlmClient.providerName(), filePath, exception.getClass().getSimpleName(), errorMessage);
            throw exception;
        } finally {
            long costTimeMs = System.currentTimeMillis() - startTime;
            reviewLlmCallLogger.save(
                    taskId,
                    filePath,
                    patch,
                    rules.size(),
                    limitedInput == null || limitedInput.truncatedRulesContext(),
                    limitedInput == null || limitedInput.truncatedChangedFilesContext(),
                    costTimeMs,
                    success,
                    errorMessage,
                    responseText
            );
        }
    }

    private AiReviewResult normalizeLlmSources(AiReviewResult result) {
        if (result.getIssues() == null) {
            return result;
        }
        for (AiReviewIssue issue : result.getIssues()) {
            if (issue != null) {
                issue.setSource("LLM");
            }
        }
        return result;
    }

    private ReviewLlmInput promptSafe(ReviewLlmInput input) {
        return new ReviewLlmInput(
                promptSafe(input.filePath()),
                promptSafe(input.patch()),
                promptSafe(input.rulesContext()),
                promptSafe(input.changedFilesContext()),
                input.truncatedRulesContext(),
                input.truncatedChangedFilesContext()
        );
    }

    private String promptSafe(String content) {
        return PromptInputSanitizer.escapeUntrustedBlockDelimiters(content);
    }

    private int length(String content) {
        return content == null ? 0 : content.length();
    }
}
