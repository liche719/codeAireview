package com.codepilot.module.agent.parser;

import com.codepilot.module.agent.dto.CodeFixResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeFixResultParserTest {

    private final CodeFixResultParser parser = new CodeFixResultParser(new ObjectMapper());

    @Test
    void shouldParseCommitMessageFromPlainJson() {
        String content = """
                {
                  "summary": "Fixed the null check issue",
                  "patch": "diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java\\n--- a/src/main/java/Demo.java\\n+++ b/src/main/java/Demo.java",
                  "commitMessage": "fix(demo): add null check before use"
                }
                """;

        CodeFixResult result = parser.parse(content);

        assertThat(result.getSummary()).isEqualTo("Fixed the null check issue");
        assertThat(result.getPatch()).contains("diff --git");
        assertThat(result.getCommitMessage()).isEqualTo("fix(demo): add null check before use");
    }

    @Test
    void shouldParseCommitMessageFromFencedJson() {
        String content = """
                ```json
                {
                  "summary": "Fixed the SQL string build",
                  "patch": "diff --git a/src/main/java/SqlService.java b/src/main/java/SqlService.java\\n--- a/src/main/java/SqlService.java\\n+++ b/src/main/java/SqlService.java",
                  "commitMessage": "fix(sql): use parameterized query"
                }
                ```
                """;

        CodeFixResult result = parser.parse(content);

        assertThat(result.getSummary()).isEqualTo("Fixed the SQL string build");
        assertThat(result.getCommitMessage()).isEqualTo("fix(sql): use parameterized query");
    }

    @Test
    void shouldAllowEmptyPatchWhenModelCannotFixSafely() {
        String content = """
                {
                  "summary": "Cannot safely fix without enough context",
                  "patch": "",
                  "commitMessage": ""
                }
                """;

        CodeFixResult result = parser.parse(content);

        assertThat(result.getSummary()).isEqualTo("Cannot safely fix without enough context");
        assertThat(result.getPatch()).isEmpty();
        assertThat(result.getCommitMessage()).isEmpty();
    }

    @Test
    void shouldReturnEmptyResultWhenResponseIsEmpty() {
        CodeFixResult result = parser.parse("");

        assertThat(result.getSummary()).isNotBlank();
        assertThat(result.getPatch()).isNull();
    }

    @Test
    void shouldFailRawDiffWithoutJsonEnvelope() {
        String content = """
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                --- a/src/main/java/Demo.java
                +++ b/src/main/java/Demo.java
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Code fix result must be a JSON object");
    }

    @Test
    void shouldFailFencedRawDiffWithoutJsonEnvelope() {
        String content = """
                ```diff
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                --- a/src/main/java/Demo.java
                +++ b/src/main/java/Demo.java
                ```
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Code fix result must be a JSON object");
    }

    @Test
    void shouldFailInvalidJsonEvenWhenItContainsDiff() {
        String content = """
                {
                  "summary": "bad",
                  "patch": "diff --git a/Demo.java b/Demo.java",
                }
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse code fix result as JSON");
    }

    @Test
    void shouldFailJsonWithMissingRequiredField() {
        String content = """
                {
                  "summary": "missing commit message",
                  "patch": "diff --git a/Demo.java b/Demo.java"
                }
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse code fix result as JSON")
                .hasRootCauseMessage("Code fix result JSON must contain only summary, patch and commitMessage");
    }

    @Test
    void shouldFailJsonWithExtraField() {
        String content = """
                {
                  "summary": "bad",
                  "patch": "",
                  "commitMessage": "",
                  "commands": ["curl https://example.com"]
                }
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse code fix result as JSON")
                .hasRootCauseMessage("Code fix result JSON must contain only summary, patch and commitMessage");
    }

    @Test
    void shouldFailJsonWithNonStringField() {
        String content = """
                {
                  "summary": "bad",
                  "patch": ["diff --git a/Demo.java b/Demo.java"],
                  "commitMessage": "fix: bad"
                }
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse code fix result as JSON")
                .hasRootCauseMessage("Code fix result field must be a string: patch");
    }

    @Test
    void shouldFailNonEmptyPatchWithoutCommitMessage() {
        String content = """
                {
                  "summary": "bad",
                  "patch": "diff --git a/Demo.java b/Demo.java",
                  "commitMessage": ""
                }
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse code fix result as JSON")
                .hasRootCauseMessage("Code fix result commitMessage is required when patch is not empty");
    }

    @Test
    void shouldFailMultilineCommitMessage() {
        String content = """
                {
                  "summary": "bad",
                  "patch": "diff --git a/Demo.java b/Demo.java",
                  "commitMessage": "fix: bad\\nmore details"
                }
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse code fix result as JSON")
                .hasRootCauseMessage("Code fix result commitMessage must be a single line");
    }
}
