package com.codepilot.module.agent.service;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface CodeFixAiAssistant {

    @SystemMessage(fromResource = "prompts/code-fix-system-message.txt")
    @UserMessage(fromResource = "prompts/code-fix-user-message.txt")
    Result<String> generateFix(
            @V("issues") String issues,
            @V("snippets") String snippets,
            @V("limits") String limits
    );
}
