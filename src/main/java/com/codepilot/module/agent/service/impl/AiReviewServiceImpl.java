package com.codepilot.module.agent.service.impl;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.common.util.PromptInputSanitizer;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.dto.ReviewRuleContext;
import com.codepilot.module.agent.parser.AiReviewResultParser;
import com.codepilot.module.agent.prompt.ReviewPromptBuilder;
import com.codepilot.module.agent.review.DeterministicReviewToolRunner;
import com.codepilot.module.agent.review.ReviewIssueDeduplicator;
import com.codepilot.module.agent.review.ReviewLlmCallLogger;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.agent.service.CodeReviewAiAssistant;
import com.codepilot.module.agent.service.ReviewRagService;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewServiceImpl implements AiReviewService {

    private final LlmProperties llmProperties;

    private final ObjectProvider<CodeReviewAiAssistant> codeReviewAiAssistantProvider;

    private final AiReviewResultParser aiReviewResultParser;

    private final ReviewRagService reviewRagService;

    private final ReviewPromptBuilder reviewPromptBuilder;

    private final ReviewLlmCallLogger reviewLlmCallLogger;

    private final DeterministicReviewToolRunner deterministicReviewToolRunner;

    private final ReviewIssueDeduplicator reviewIssueDeduplicator;

    @Override
    public AiReviewResult reviewFile(Long taskId, String filePath, String patch, List<String> allChangedFiles) {
        if (!StringUtils.hasText(patch)) {
            log.info("Skip ai review because patch is empty, filePath={}", filePath);
            return AiReviewResult.empty();
        }

        String allChangedFilesText = buildAllChangedFilesText(allChangedFiles);
        AiReviewResult deterministicResult = deterministicReviewToolRunner.run(filePath, patch, allChangedFilesText);
        if (!llmProperties.isEnabled()) {
            log.info("Skip llm review because llm is disabled, filePath={}, deterministicIssueCount={}",
                    filePath, issueCount(deterministicResult));
            return deterministicResult;
        }
        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            log.info("Skip llm review because llm api key is missing, filePath={}, deterministicIssueCount={}",
                    filePath, issueCount(deterministicResult));
            return deterministicResult;
        }

        CodeReviewAiAssistant codeReviewAiAssistant = codeReviewAiAssistantProvider.getIfAvailable();
        if (codeReviewAiAssistant == null) {
            log.warn("Skip llm review because CodeReviewAiAssistant bean is unavailable, filePath={}, deterministicIssueCount={}",
                    filePath, issueCount(deterministicResult));
            return deterministicResult;
        }

        List<ReviewRuleContext> rules = reviewRagService.retrieveRelevantRules(filePath, patch);
        String rulesContext = reviewPromptBuilder.buildRulesContext(rules);
        log.info("AI review RAG context prepared, filePath={}, ruleCount={}, contextChars={}",
                filePath, rules.size(), rulesContext == null ? 0 : rulesContext.length());

        String responseText = null;
        String errorMessage = null;
        boolean success = false;
        long startTime = System.currentTimeMillis();

        try {
            Result<String> result = codeReviewAiAssistant.review(
                    promptSafe(filePath),
                    promptSafe(patch),
                    promptSafe(rulesContext),
                    promptSafe(allChangedFilesText)
            );
            responseText = result == null ? null : result.content();
            if (!StringUtils.hasText(responseText)) {
                errorMessage = "empty model response";
                log.warn("LangChain4j review returned empty content, filePath={}", filePath);
                throw new IllegalStateException(errorMessage);
            }

            AiReviewResult parsedResult = aiReviewResultParser.parse(responseText);
            success = true;
            return mergeResults(parsedResult, deterministicResult);
        } catch (Exception exception) {
            errorMessage = SensitiveDataSanitizer.redact(exception.getMessage());
            log.warn("LangChain4j ai review failed, filePath={}, errorType={}, message={}",
                    filePath, exception.getClass().getSimpleName(), errorMessage);
            throw exception;
        } finally {
            long costTimeMs = System.currentTimeMillis() - startTime;
            reviewLlmCallLogger.save(taskId, filePath, patch, rules.size(), costTimeMs, success, errorMessage, responseText);
        }
    }

    private String buildAllChangedFilesText(List<String> allChangedFiles) {
        if (allChangedFiles == null || allChangedFiles.isEmpty()) {
            return "No changed file list was provided.";
        }
        return String.join("\n", allChangedFiles);
    }

    private String promptSafe(String content) {
        return PromptInputSanitizer.escapeUntrustedBlockDelimiters(content);
    }

    private AiReviewResult mergeResults(AiReviewResult llmResult, AiReviewResult deterministicResult) {
        List<AiReviewIssue> mergedIssues = new ArrayList<>();
        if (llmResult != null && llmResult.getIssues() != null) {
            mergedIssues.addAll(llmResult.getIssues());
        }
        if (deterministicResult != null && deterministicResult.getIssues() != null) {
            mergedIssues.addAll(deterministicResult.getIssues());
        }
        AiReviewResult mergedResult = llmResult == null ? AiReviewResult.empty() : llmResult;
        mergedResult.setIssues(reviewIssueDeduplicator.dedupe(mergedIssues));
        if (!StringUtils.hasText(mergedResult.getSummary())
                && deterministicResult != null
                && StringUtils.hasText(deterministicResult.getSummary())) {
            mergedResult.setSummary(deterministicResult.getSummary());
        }
        return mergedResult;
    }

    private int issueCount(AiReviewResult result) {
        return result == null || result.getIssues() == null ? 0 : result.getIssues().size();
    }
}
