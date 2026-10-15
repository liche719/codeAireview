package com.codepilot.module.command.parser;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommand;
import com.codepilot.module.command.dto.GithubCommandType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GithubCommandParserTest {

    @Test
    void shouldParseLegacyReviewCommandWhenLlmIsAvailable() {
        GithubCommand command = parserWithAvailableLlm().parse("/review");

        assertThat(command.getType()).isEqualTo(GithubCommandType.REVIEW);
        assertThat(command.isMentionedBot()).isFalse();
    }

    @Test
    void shouldReturnUnavailableForLegacyReviewCommandWhenLlmIsUnavailable() {
        GithubCommand command = parserWithUnavailableLlm().parse("/review");

        assertThat(command.getType()).isEqualTo(GithubCommandType.UNAVAILABLE);
        assertThat(command.isMentionedBot()).isFalse();
    }

    @Test
    void shouldRouteEveryMentionToAgentWithoutPreclassification() {
        GithubCommand review = parserWithAvailableLlm().parse("@x-pilotx please review this PR");
        GithubCommand fix = parserWithAvailableLlm().parse("@x-pilotx fix the null pointer in UserService");
        GithubCommand greeting = parserWithAvailableLlm().parse("@x-pilotx hello");

        assertAgentCommand(review, "please review this PR");
        assertAgentCommand(fix, "fix the null pointer in UserService");
        assertAgentCommand(greeting, "hello");
    }

    @Test
    void shouldPreserveCompleteNaturalLanguageRequestForAgent() {
        GithubCommand command = parserWithAvailableLlm().parse(
                "@X-PilotX inspect the Redis change, search current guidance, then prepare a fix preview"
        );

        assertAgentCommand(command, "inspect the Redis change, search current guidance, then prepare a fix preview");
    }

    @Test
    void shouldNotTurnFixWordsIntoAWriteEnabledTask() {
        GithubCommand command = parserWithAvailableLlm().parse("@x-pilotx fix this and push it");

        assertAgentCommand(command, "fix this and push it");
        assertThat(command.isDryRun()).isFalse();
    }

    @Test
    void shouldReturnUnavailableForMentionWhenLlmIsUnavailable() {
        GithubCommand command = parserWithUnavailableLlm().parse("@x-pilotx summarize this PR");

        assertThat(command.getType()).isEqualTo(GithubCommandType.UNAVAILABLE);
        assertThat(command.isMentionedBot()).isTrue();
    }

    @Test
    void shouldIgnoreCommentWithoutMention() {
        GithubCommand command = parserWithAvailableLlm().parse("please review");

        assertThat(command.shouldIgnore()).isTrue();
    }

    private void assertAgentCommand(GithubCommand command, String expectedText) {
        assertThat(command.getType()).isEqualTo(GithubCommandType.CHAT);
        assertThat(command.getText()).isEqualTo(expectedText);
        assertThat(command.isMentionedBot()).isTrue();
    }

    private GithubCommandParser parserWithAvailableLlm() {
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setApiKey("test-key");
        return new GithubCommandParser(new GithubCommandProperties(), llmProperties);
    }

    private GithubCommandParser parserWithUnavailableLlm() {
        return new GithubCommandParser(new GithubCommandProperties(), new LlmProperties());
    }
}
