package com.codepilot.module.agent.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CodeReviewAiAssistantPromptResourceTest {

    @Test
    void shouldContainRequiredPromptPlaceholdersAndJsonContract() throws Exception {
        String systemMessage = new ClassPathResource("prompts/ai-review-system-message.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        String userMessage = new ClassPathResource("prompts/ai-review-user-message.txt")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(systemMessage).contains("只返回有效 JSON");
        assertThat(systemMessage).contains("SQL 风险检测工具");
        assertThat(systemMessage).contains("敏感信息扫描工具");
        assertThat(systemMessage).contains("测试建议工具");
        assertThat(systemMessage).contains("issueTypeZh");
        assertThat(userMessage).contains("{{filePath}}");
        assertThat(userMessage).contains("{{patch}}");
        assertThat(userMessage).contains("{{rules}}");
        assertThat(userMessage).contains("{{allChangedFilesText}}");
        assertThat(userMessage).contains("\"issues\"");
        assertThat(userMessage).contains("\"summary\"");
        assertThat(userMessage).contains("issueTypeZh");
        assertThat(userMessage).contains("\"source\"");
        assertThat(userMessage).contains("LLM | TOOL");
        assertThat(userMessage).contains("ruleReference");
    }
}
