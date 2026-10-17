package com.codepilot.module.agent.service;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * finding 的自省复核（R18）。
 *
 * <p><b>刻意用 {@code codeReviewChatModel} 而不是 {@code structuredCodeReviewChatModel}。</b>
 * 后者在模型层绑定了审查结果的 JSON schema，用它做自省会被强制套上那套结构；
 * 自省只需要一个极小的对象（{@code {"score": N}}），因此用无 schema 的普通模型。</p>
 *
 * <p>与主审查分开成两个接口而不是合并进同一个：合并会让模型"自己给自己打分"，
 * 失去独立视角——这正是本能力想要的东西。</p>
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "codeReviewChatModel")
public interface CodeReviewReflectionAssistant {

    @SystemMessage(fromResource = "prompts/ai-review-reflection-system-message.txt")
    @UserMessage(fromResource = "prompts/ai-review-reflection-user-message.txt")
    Result<String> score(
            @V("filePath") String filePath,
            @V("patch") String patch,
            @V("issueType") String issueType,
            @V("severity") String severity,
            @V("title") String title,
            @V("description") String description,
            @V("lineNumber") String lineNumber
    );
}
