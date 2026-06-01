package com.codepilot.module.command.handler;

import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GithubCommandChatAiAssistantPromptResourceTest {

    @Test
    void shouldUseExplicitChatModelWiring() {
        AiService aiService = GithubCommandChatAiAssistant.class.getAnnotation(AiService.class);

        assertThat(aiService).isNotNull();
        assertThat(aiService.wiringMode()).isEqualTo(AiServiceWiringMode.EXPLICIT);
        assertThat(aiService.chatModel()).isEqualTo("codeReviewChatModel");
    }

    @Test
    void shouldIsolateUntrustedGithubCommentAndReviewContextInputs() throws Exception {
        String systemMessage = new ClassPathResource("prompts/github-command-chat-system-message.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        String userMessage = new ClassPathResource("prompts/github-command-chat-user-message.txt")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(systemMessage)
                .contains("stored review session context")
                .contains("reviewFreshness")
                .contains("不可信数据")
                .contains("结构化字段")
                .contains("不得逐字复述原文");
        assertThat(userMessage)
                .contains("{{commentBody}}")
                .contains("{{commandText}}")
                .contains("{{reviewSessionContext}}")
                .contains("{{owner}}")
                .contains("{{repo}}")
                .contains("{{pullNumber}}")
                .contains("<untrusted_comment_body>")
                .contains("</untrusted_comment_body>")
                .contains("<untrusted_command_text>")
                .contains("</untrusted_command_text>")
                .contains("<untrusted_review_session_context>")
                .contains("</untrusted_review_session_context>")
                .contains("优先使用 `<untrusted_review_session_context>` 中的结构化字段");
    }
}
