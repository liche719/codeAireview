package com.codepilot.module.command.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    void shouldRedactSecretsFromParserErrorLogs(CapturedOutput output) throws Exception {
        String secret = "ghp_123456789012345678901234567890123456";
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.readTree(anyString())).thenThrow(new IllegalStateException("token=" + secret));
        GithubCommandIntentResultParser parserWithFailingMapper = new GithubCommandIntentResultParser(objectMapper);

        assertThat(parserWithFailingMapper.parse("{}")).isNull();

        assertThat(output.getOut() + output.getErr())
                .contains("[REDACTED]")
                .doesNotContain(secret);
    }
}
