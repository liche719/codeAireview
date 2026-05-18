package com.codepilot.module.agent.service.impl;

import com.codepilot.infrastructure.llm.LlmClient;
import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.infrastructure.llm.LlmReviewRequest;
import com.codepilot.infrastructure.llm.LlmReviewResponse;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.parser.AiReviewResultParser;
import com.codepilot.module.agent.prompt.ReviewPromptBuilder;
import com.codepilot.module.agent.service.AiReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewServiceImpl implements AiReviewService {

    private final LlmProperties llmProperties;

    private final LlmClient llmClient;

    private final ReviewPromptBuilder reviewPromptBuilder;

    private final AiReviewResultParser aiReviewResultParser;

    @Override
    public AiReviewResult reviewFile(Long taskId, String filePath, String patch) {
        if (!llmProperties.isEnabled()) {
            log.info("Skip ai review because llm is disabled, filePath={}", filePath);
            return AiReviewResult.empty();
        }
        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            log.info("Skip ai review because llm api key is missing, filePath={}", filePath);
            return AiReviewResult.empty();
        }

        String prompt = reviewPromptBuilder.buildReviewPrompt(filePath, patch);
        LlmReviewResponse response = llmClient.review(new LlmReviewRequest(taskId, filePath, prompt, patch == null ? 0 : patch.length()));
        if (!response.isSuccess() || !StringUtils.hasText(response.getContent())) {
            log.warn("Ai review failed, filePath={}, errorMessage={}", filePath, response.getErrorMessage());
            return AiReviewResult.empty();
        }

        return aiReviewResultParser.parse(response.getContent());
    }
}

