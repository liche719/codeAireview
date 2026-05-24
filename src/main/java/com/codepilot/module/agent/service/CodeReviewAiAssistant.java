package com.codepilot.module.agent.service;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService(chatModel = "structuredCodeReviewChatModel")
public interface CodeReviewAiAssistant {

    @SystemMessage(fromResource = "prompts/ai-review-system-message.txt")
    @UserMessage(fromResource = "prompts/ai-review-user-message.txt")
    Result<String> review(
            @V("filePath") String filePath,
            @V("patch") String patch,
            @V("rules") String rules,
            @V("allChangedFilesText") String allChangedFilesText
    );
}
