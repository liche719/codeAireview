package com.codepilot.module.agent.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CodeFixAiAssistantPromptResourceTest {

    @Test
    void shouldContainRequiredPromptPlaceholdersAndJsonContract() throws Exception {
        String systemMessage = new ClassPathResource("prompts/code-fix-system-message.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        String userMessage = new ClassPathResource("prompts/code-fix-user-message.txt")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(systemMessage).contains("只返回 JSON");
        assertThat(systemMessage).contains("统一 diff");
        assertThat(systemMessage).contains("不要修改无关代码");
        assertThat(systemMessage).contains("所有自然语言输出都使用中文");
        assertThat(systemMessage).contains("commitMessage");
        assertThat(systemMessage).contains("fix(scope):");
        assertThat(userMessage).contains("{{issues}}");
        assertThat(userMessage).contains("{{snippets}}");
        assertThat(userMessage).contains("{{limits}}");
        assertThat(userMessage).contains("\"summary\"");
        assertThat(userMessage).contains("\"patch\"");
        assertThat(userMessage).contains("\"commitMessage\"");
        assertThat(userMessage).contains("请仅返回 JSON");
    }
}
