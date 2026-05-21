package com.codepilot.module.agent.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiReviewResultParserTest {

    private final AiReviewResultParser parser = new AiReviewResultParser(new ObjectMapper());

    @Test
    void shouldParsePlainJson() {
        String content = """
                {
                  "issues": [
                    {
                      "filePath": "src/main/java/com/example/Demo.java",
                      "lineNumber": 12,
                      "issueType": "BUG_RISK",
                      "issueTypeZh": "潜在缺陷",
                      "severity": "HIGH",
                      "title": "Potential null pointer",
                      "description": "Object may be null before invocation.",
                      "suggestion": "Add a null check.",
                      "source": "TOOL",
                      "ruleReference": null
                    }
                  ],
                  "summary": "Found one issue"
                }
                """;

        var result = parser.parse(content);

        assertThat(result.getIssues()).hasSize(1);
        assertThat(result.getIssues().getFirst().getSeverity()).isEqualTo("HIGH");
        assertThat(result.getIssues().getFirst().getIssueTypeZh()).isEqualTo("潜在缺陷");
        assertThat(result.getIssues().getFirst().getSource()).isEqualTo("TOOL");
        assertThat(result.getSummary()).isEqualTo("Found one issue");
    }

    @Test
    void shouldParseFencedJson() {
        String content = """
                ```json
                {
                  "issues": [],
                  "summary": "未发现问题"
                }
                ```
                """;

        var result = parser.parse(content);

        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getSummary()).isEqualTo("未发现问题");
    }

    @Test
    void shouldThrowWhenJsonIsInvalid() {
        String content = """
                { invalid json }
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse AI review result as JSON");
    }

    @Test
    void shouldThrowWhenContentIsEmpty() {
        assertThatThrownBy(() -> parser.parse(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AI review result is empty");
    }
}
