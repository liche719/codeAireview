package com.codepilot.module.agent.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPromptBuilderTest {

    private final ReviewPromptBuilder reviewPromptBuilder = new ReviewPromptBuilder();

    @Test
    void shouldContainFilePathAndPatch() {
        String filePath = "src/main/java/com/example/DemoService.java";
        String patch = "@@ -1,3 +1,5 @@\n+log.info(\"hello\");";

        String prompt = reviewPromptBuilder.buildReviewPrompt(filePath, patch);

        assertThat(prompt).contains(filePath);
        assertThat(prompt).contains(patch);
        assertThat(prompt).contains("请严格按照 JSON 格式输出");
        assertThat(prompt).contains("\"issues\"");
        assertThat(prompt).contains("\"severity\"");
    }
}
