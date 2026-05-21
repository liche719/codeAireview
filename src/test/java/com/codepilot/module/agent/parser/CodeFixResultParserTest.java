package com.codepilot.module.agent.parser;

import com.codepilot.module.agent.dto.CodeFixResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void shouldReturnChineseSummaryWhenResponseIsEmpty() {
        CodeFixResult result = parser.parse("");

        assertThat(result.getSummary()).isEqualTo("未生成补丁。");
        assertThat(result.getPatch()).isNull();
    }

    @Test
    void shouldRejectRawDiffWithoutJsonEnvelope() {
        String content = """
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                --- a/src/main/java/Demo.java
                +++ b/src/main/java/Demo.java
                @@ -1 +1 @@
                -old
                +new
                """;

        CodeFixResult result = parser.parse(content);

        assertThat(result.getSummary()).isEqualTo("未生成补丁。");
        assertThat(result.getPatch()).isNull();
        assertThat(result.getCommitMessage()).isNull();
    }

    @Test
    void shouldRejectFencedRawDiffWithoutJsonEnvelope() {
        String content = """
                ```diff
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                --- a/src/main/java/Demo.java
                +++ b/src/main/java/Demo.java
                ```
                """;

        CodeFixResult result = parser.parse(content);

        assertThat(result.getSummary()).isEqualTo("未生成补丁。");
        assertThat(result.getPatch()).isNull();
    }

    @Test
    void shouldRejectInvalidJsonEvenWhenItContainsDiff() {
        String content = """
                {
                  "summary": "bad",
                  "patch": "diff --git a/Demo.java b/Demo.java",
                }
                """;

        CodeFixResult result = parser.parse(content);

        assertThat(result.getSummary()).isEqualTo("未生成补丁。");
        assertThat(result.getPatch()).isNull();
    }
}
