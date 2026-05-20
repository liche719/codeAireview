package com.codepilot.module.command.parser;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface GithubCommandIntentAiAssistant {

    @SystemMessage(fromResource = "prompts/github-command-intent-system-message.txt")
    @UserMessage(fromResource = "prompts/github-command-intent-user-message.txt")
    String classify(
            @V("body") String body,
            @V("commandText") String commandText,
            @V("aliases") String aliases
    );
}
