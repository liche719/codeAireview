package com.codepilot.module.agent.review;

import com.codepilot.infrastructure.llm.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewLlmGate {

    private final LlmProperties llmProperties;

    public boolean isAvailable(String filePath, int deterministicIssueCount) {
        if (!llmProperties.isEnabled()) {
            log.info("Skip llm review because llm is disabled, filePath={}, deterministicIssueCount={}",
                    filePath, deterministicIssueCount);
            return false;
        }
        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            log.info("Skip llm review because llm api key is missing, filePath={}, deterministicIssueCount={}",
                    filePath, deterministicIssueCount);
            return false;
        }
        return true;
    }
}
