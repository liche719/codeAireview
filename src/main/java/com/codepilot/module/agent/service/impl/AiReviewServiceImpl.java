package com.codepilot.module.agent.service.impl;

import com.codepilot.module.agent.dto.AiReviewContext;
import com.codepilot.module.agent.dto.AiReviewRequest;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.prompt.AiReviewContextFormatter;
import com.codepilot.module.agent.review.DeterministicReviewToolRunner;
import com.codepilot.module.agent.review.ReviewLlmGate;
import com.codepilot.module.agent.review.ReviewLlmInputLimiter;
import com.codepilot.module.agent.review.ReviewLlmReviewer;
import com.codepilot.module.agent.review.ReviewResultMerger;
import com.codepilot.module.agent.service.AiReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewServiceImpl implements AiReviewService {

    private final DeterministicReviewToolRunner deterministicReviewToolRunner;

    private final AiReviewContextFormatter aiReviewContextFormatter;

    private final ReviewLlmGate reviewLlmGate;

    private final ReviewLlmInputLimiter reviewLlmInputLimiter;

    private final ReviewLlmReviewer reviewLlmReviewer;

    private final ReviewResultMerger reviewResultMerger;

    @Override
    public AiReviewResult reviewFile(AiReviewRequest request) {
        String filePath = request == null ? null : request.filePath();
        String patch = request == null ? null : request.patch();
        AiReviewContext context = request == null ? AiReviewContext.empty() : request.context();
        if (!StringUtils.hasText(patch)) {
            log.info("Skip ai review because patch is empty, filePath={}", filePath);
            return AiReviewResult.empty();
        }

        String allChangedFilesText = aiReviewContextFormatter.format(context);
        AiReviewResult deterministicResult = deterministicReviewToolRunner.run(filePath, patch, allChangedFilesText);
        if (!reviewLlmGate.isAvailable(filePath, issueCount(deterministicResult))) {
            return deterministicResult;
        }
        if (!reviewLlmInputLimiter.isPatchWithinBudget(patch)) {
            log.warn("Skip llm review because patch exceeds llm input budget, filePath={}, patchChars={}, deterministicIssueCount={}",
                    filePath, patch.length(), issueCount(deterministicResult));
            return withFallbackSummary(
                    deterministicResult,
                    "LLM review skipped because this file patch exceeds the configured input budget; deterministic tool findings only."
            );
        }

        AiReviewResult llmResult = reviewLlmReviewer
                .review(request, allChangedFilesText, issueCount(deterministicResult))
                .orElse(null);
        if (llmResult == null) {
            return deterministicResult;
        }
        return reviewResultMerger.merge(llmResult, deterministicResult);
    }

    private int issueCount(AiReviewResult result) {
        return result == null || result.getIssues() == null ? 0 : result.getIssues().size();
    }

    private AiReviewResult withFallbackSummary(AiReviewResult result, String summary) {
        AiReviewResult safeResult = result == null ? AiReviewResult.empty() : result;
        if (StringUtils.hasText(safeResult.getSummary())) {
            safeResult.setSummary(safeResult.getSummary() + " " + summary);
        } else {
            safeResult.setSummary(summary);
        }
        return safeResult;
    }
}
