package com.codepilot.module.agent.review;

import com.codepilot.module.agent.service.CodeReviewAiAssistant;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LangChain4jReviewLlmClient implements ReviewLlmClient {

    private static final String PROVIDER_NAME = "langchain4j";

    private final ObjectProvider<CodeReviewAiAssistant> codeReviewAiAssistantProvider;

    @Override
    public String providerName() {
        return PROVIDER_NAME;
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
        return result == null ? null : result.content();
    }
}
