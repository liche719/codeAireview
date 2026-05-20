package com.codepilot.module.command.handler;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService(tools = "githubCommandChatTool")
public interface GithubCommandChatAiAssistant {

    @SystemMessage(fromResource = "prompts/github-command-chat-system-message.txt")
    @UserMessage(fromResource = "prompts/github-command-chat-user-message.txt")
    String reply(
            @V("commentBody") String commentBody,
            @V("commandText") String commandText,
            @V("owner") String owner,
            @V("repo") String repo,
            @V("pullNumber") Integer pullNumber
    );
}
