package com.codepilot.module.command.parser;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommand;
import com.codepilot.module.command.dto.GithubCommandType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GithubCommandParserTest {

    private final GithubCommandParser parser = new GithubCommandParser(new GithubCommandProperties());

    @Test
    void shouldParseLegacyReviewCommand() {
        GithubCommand command = parser.parse("/review");

        assertThat(command.getType()).isEqualTo(GithubCommandType.REVIEW);
        assertThat(command.isMentionedBot()).isFalse();
    }

    @Test
    void shouldParseMentionReviewCommand() {
        GithubCommand command = parser.parse("@x-pilotx 帮我review一下");

        assertThat(command.getType()).isEqualTo(GithubCommandType.REVIEW);
        assertThat(command.isMentionedBot()).isTrue();
    }

    @Test
    void shouldParseMentionFixDryRunCommand() {
        GithubCommand command = parser.parse("@x-pilotx 帮我解决上述问题 dry-run");

        assertThat(command.getType()).isEqualTo(GithubCommandType.FIX);
        assertThat(command.isDryRun()).isTrue();
    }

    @Test
    void shouldParseMentionHelpCommand() {
        GithubCommand command = parser.parse("@x-pilotx help");

        assertThat(command.getType()).isEqualTo(GithubCommandType.HELP);
    }

    @Test
    void shouldParseMentionUnknownCommand() {
        GithubCommand command = parser.parse("@x-pilotx hello");

        assertThat(command.getType()).isEqualTo(GithubCommandType.UNKNOWN);
    }

    @Test
    void shouldIgnoreCommentWithoutMention() {
        GithubCommand command = parser.parse("please review");

        assertThat(command.shouldIgnore()).isTrue();
    }
}
