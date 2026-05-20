package com.codepilot.module.command.parser;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommand;
import com.codepilot.module.command.dto.GithubCommandType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GithubCommandParserTest {

    private static final String CHINESE_REVIEW_BODY = "@X-PilotX \u4f60\u597d\uff0c\u5e2e\u6211\u770b\u770b\u8fd8\u6709\u4ec0\u4e48\u95ee\u9898\u5417";

    private static final String CHINESE_REVIEW_COMMAND = "\u4f60\u597d\uff0c\u5e2e\u6211\u770b\u770b\u8fd8\u6709\u4ec0\u4e48\u95ee\u9898\u5417";

    private static final String CHINESE_FIX_COMMAND = "\u5e2e\u6211\u89e3\u51b3\u4e0a\u8ff0\u95ee\u9898";

    @Test
    void shouldParseLegacyReviewCommandWhenLlmIsAvailable() {
        GithubCommand command = parserWithoutClassifierButLlmAvailable().parse("/review");

        assertThat(command.getType()).isEqualTo(GithubCommandType.REVIEW);
        assertThat(command.isMentionedBot()).isFalse();
    }

    @Test
    void shouldReturnUnavailableForLegacyReviewCommandWhenLlmIsUnavailable() {
        GithubCommand command = parserWithLlmUnavailable().parse("/review");

        assertThat(command.getType()).isEqualTo(GithubCommandType.UNAVAILABLE);
        assertThat(command.isMentionedBot()).isFalse();
    }

    @Test
    void shouldUseAiClassifierForNaturalReviewCommandWhenLlmIsAvailable() {
        GithubCommandIntentAiAssistant assistant = mockAssistantReturning("""
                {
                  "type": "REVIEW",
                  "dryRun": false,
                  "reason": "asks the bot to review the PR"
                }
                """);

        GithubCommand command = parserWithAssistant(assistant).parse(CHINESE_REVIEW_BODY);

        assertThat(command.getType()).isEqualTo(GithubCommandType.REVIEW);
        assertThat(command.isMentionedBot()).isTrue();
        verify(assistant).classify(
                eq(CHINESE_REVIEW_BODY),
                eq(CHINESE_REVIEW_COMMAND),
                eq("@x-pilotx,@X-PilotX")
        );
    }

    @Test
    void shouldUseAiClassifierForFixDryRunCommandWhenLlmIsAvailable() {
        GithubCommandIntentAiAssistant assistant = mockAssistantReturning("""
                {
                  "type": "FIX",
                  "dryRun": true,
                  "reason": "asks the bot to fix the previous issue"
                }
                """);

        GithubCommand command = parserWithAssistant(assistant).parse("@x-pilotx " + CHINESE_FIX_COMMAND + " dry-run");

        assertThat(command.getType()).isEqualTo(GithubCommandType.FIX);
        assertThat(command.isMentionedBot()).isTrue();
        assertThat(command.isDryRun()).isTrue();
    }

    @Test
    void shouldUseAiClassifierForChatGreetingWhenLlmIsAvailable() {
        GithubCommandIntentAiAssistant assistant = mockAssistantReturning("""
                {
                  "type": "CHAT",
                  "dryRun": false,
                  "reason": "greets the bot"
                }
                """);

        GithubCommand command = parserWithAssistant(assistant).parse("@x-pilotx \u4f60\u597d");

        assertThat(command.getType()).isEqualTo(GithubCommandType.CHAT);
        assertThat(command.isMentionedBot()).isTrue();
    }

    @Test
    void shouldUseAiClassifierForChatSummaryCommandWhenLlmIsAvailable() {
        GithubCommandIntentAiAssistant assistant = mockAssistantReturning("""
                {
                  "type": "CHAT",
                  "dryRun": false,
                  "reason": "asks for a PR summary"
                }
                """);

        GithubCommand command = parserWithAssistant(assistant).parse("@x-pilotx \u603b\u7ed3\u4e00\u4e0b\u8fd9\u4e2apr\u4e3b\u8981\u505a\u4e86\u4ec0\u4e48");

        assertThat(command.getType()).isEqualTo(GithubCommandType.CHAT);
        assertThat(command.isMentionedBot()).isTrue();
    }

    @Test
    void shouldReturnUnavailableForChatWhenLlmIsUnavailable() {
        GithubCommand command = parserWithLlmUnavailable().parse("@x-pilotx \u603b\u7ed3\u4e00\u4e0b\u8fd9\u4e2apr\u4e3b\u8981\u505a\u4e86\u4ec0\u4e48");

        assertThat(command.getType()).isEqualTo(GithubCommandType.UNAVAILABLE);
        assertThat(command.isMentionedBot()).isTrue();
    }

    @Test
    void shouldReturnUnknownWhenAiClassifierReturnsUnknown() {
        GithubCommandIntentAiAssistant assistant = mockAssistantReturning("""
                {
                  "type": "UNKNOWN",
                  "dryRun": false,
                  "reason": "only greeting"
                }
                """);

        GithubCommand command = parserWithAssistant(assistant).parse("@x-pilotx hello");

        assertThat(command.getType()).isEqualTo(GithubCommandType.UNKNOWN);
        assertThat(command.isMentionedBot()).isTrue();
    }

    @Test
    void shouldReturnUnavailableForMentionCommandWhenLlmIsUnavailable() {
        GithubCommand command = parserWithLlmUnavailable().parse("@x-pilotx " + CHINESE_FIX_COMMAND);

        assertThat(command.getType()).isEqualTo(GithubCommandType.UNAVAILABLE);
        assertThat(command.isMentionedBot()).isTrue();
    }

    @Test
    void shouldReturnUnavailableWhenAiClassifierIsMissing() {
        GithubCommand command = parserWithoutClassifierButLlmAvailable().parse(CHINESE_REVIEW_BODY);

        assertThat(command.getType()).isEqualTo(GithubCommandType.UNAVAILABLE);
        assertThat(command.isMentionedBot()).isTrue();
    }

    @Test
    void shouldReturnUnavailableWhenAiClassifierFails() {
        GithubCommandIntentAiAssistant assistant = mock(GithubCommandIntentAiAssistant.class);
        when(assistant.classify(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("llm unavailable"));

        GithubCommand command = parserWithAssistant(assistant).parse("@x-pilotx " + CHINESE_FIX_COMMAND);

        assertThat(command.getType()).isEqualTo(GithubCommandType.UNAVAILABLE);
        assertThat(command.isMentionedBot()).isTrue();
    }

    @Test
    void shouldReturnUnavailableWhenAiClassifierResponseCannotBeParsed() {
        GithubCommandIntentAiAssistant assistant = mockAssistantReturning("not json");

        GithubCommand command = parserWithAssistant(assistant).parse(CHINESE_REVIEW_BODY);

        assertThat(command.getType()).isEqualTo(GithubCommandType.UNAVAILABLE);
        assertThat(command.isMentionedBot()).isTrue();
    }

    @Test
    void shouldIgnoreCommentWithoutMention() {
        GithubCommand command = parserWithAssistant(mockAssistantReturning("{}")).parse("please review");

        assertThat(command.shouldIgnore()).isTrue();
    }

    private GithubCommandIntentAiAssistant mockAssistantReturning(String response) {
        GithubCommandIntentAiAssistant assistant = mock(GithubCommandIntentAiAssistant.class);
        when(assistant.classify(anyString(), anyString(), anyString())).thenReturn(response);
        return assistant;
    }

    private GithubCommandParser parserWithAssistant(GithubCommandIntentAiAssistant assistant) {
        @SuppressWarnings("unchecked")
        ObjectProvider<GithubCommandIntentAiAssistant> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(assistant);
        return new GithubCommandParser(
                new GithubCommandProperties(),
                provider,
                new GithubCommandIntentResultParser(new ObjectMapper()),
                enabledLlmProperties()
        );
    }

    private GithubCommandParser parserWithoutClassifierButLlmAvailable() {
        return new GithubCommandParser(
                new GithubCommandProperties(),
                null,
                null,
                enabledLlmProperties()
        );
    }

    private GithubCommandParser parserWithLlmUnavailable() {
        return new GithubCommandParser(new GithubCommandProperties());
    }

    private LlmProperties enabledLlmProperties() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        return properties;
    }
}
