package com.codepilot.module.git.parser;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.git.dto.GithubPrInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubPrUrlParserTest {

    private final GithubPrUrlParser parser = new GithubPrUrlParser();

    @Test
    void shouldParseValidGithubPrUrl() {
        GithubPrInfo info = parser.parse("https://github.com/liche719/codeAireview/pull/123");

        assertThat(info.getOwner()).isEqualTo("liche719");
        assertThat(info.getRepo()).isEqualTo("codeAireview");
        assertThat(info.getPullNumber()).isEqualTo(123);
    }

    @Test
    void shouldRejectInvalidGithubPrUrl() {
        assertThatThrownBy(() -> parser.parse("https://github.com/liche719/codeAireview/issues/123"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid GitHub PR URL");
    }
}

