package com.codepilot.module.command.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GithubCommandIntentResultParserTest {

    private final GithubCommandIntentResultParser parser = new GithubCommandIntentResultParser(new ObjectMapper());

    @Test
    void shouldParseValidJsonObject() {
        var result = parser.parse("""
                {
                  "type": "FIX",
                  "dryRun": true,
                  "reason": "user asked to fix"
                }
                """);

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo("FIX");
        assertThat(result.getDryRun()).isTrue();
        assertThat(result.getReason()).isEqualTo("user asked to fix");
    }

    @Test
    void shouldParseSingleJsonCodeFence() {
        var result = parser.parse("""
                ```json
                {
                  "type": "REVIEW",
                  "dryRun": false,
                  "reason": "user asked to review"
                }
                ```
                """);

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo("REVIEW");
    }

    @Test
    void shouldRejectResponseWithTextAroundJson() {
        assertThat(parser.parse("""
                Sure, here is the JSON:
                {"type":"CHAT","dryRun":false,"reason":"hello"}
                """)).isNull();
    }

    @Test
    void shouldRejectUnknownFields() {
        assertThat(parser.parse("""
                {
                  "type": "FIX",
                  "dryRun": false,
                  "reason": "fix it",
                  "patch": "do not allow model-controlled extras"
                }
                """)).isNull();
    }

    @Test
    void shouldRejectInvalidType() {
        assertThat(parser.parse("""
                {
                  "type": "DELETE_REPO",
                  "dryRun": false,
                  "reason": "malicious"
                }
                """)).isNull();
    }

    @Test
    void shouldRejectNonBooleanDryRun() {
        assertThat(parser.parse("""
                {
                  "type": "FIX",
                  "dryRun": "true",
                  "reason": "string boolean"
                }
                """)).isNull();
    }

    @Test
    void shouldRejectNonStringReason() {
        assertThat(parser.parse("""
                {
                  "type": "CHAT",
                  "dryRun": false,
                  "reason": {"text": "hello"}
                }
                """)).isNull();
    }
}
