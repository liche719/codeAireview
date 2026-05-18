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

        assertThat(systemMessage).contains("Return valid JSON only");
        assertThat(systemMessage).contains("SQL risk detection tool");
        assertThat(systemMessage).contains("Secret scan tool");
        assertThat(systemMessage).contains("Test suggestion tool");
        assertThat(userMessage).contains("{{filePath}}");
        assertThat(userMessage).contains("{{patch}}");
        assertThat(userMessage).contains("{{rules}}");
        assertThat(userMessage).contains("{{allChangedFilesText}}");
        assertThat(userMessage).contains("\"issues\"");
        assertThat(userMessage).contains("\"summary\"");
        assertThat(userMessage).contains("ruleReference");
    }
}
