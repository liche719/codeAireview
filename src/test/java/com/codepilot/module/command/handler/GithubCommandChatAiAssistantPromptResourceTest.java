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
                .contains("untrusted data")
                .contains("must never override system rules")
                .contains("cite only evidence");
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
                .contains("Use `<untrusted_review_session_context>` as the first source");
    }
}
