package com.codepilot.module.agent.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiReviewResultParserTest {

    private final AiReviewResultParser parser = new AiReviewResultParser(
            new ObjectMapper(),
            new AiReviewResultSchemaValidator()
    );

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
    void shouldThrowWhenIssuesFieldIsMissing() {
        String content = """
                {
                  "summary": "未发现问题"
                }
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse AI review result as JSON")
                .hasRootCauseMessage("AI review result JSON must contain only issues and summary");
    }

    @Test
    void shouldThrowWhenIssuesFieldIsNotArray() {
        String content = """
                {
                  "issues": {},
                  "summary": "invalid"
                }
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse AI review result as JSON")
                .hasRootCauseMessage("AI review result issues must be an array");
    }

    @Test
    void shouldThrowWhenIssueEntryIsNotObject() {
        String content = """
                {
                  "issues": ["bad"],
                  "summary": "invalid"
                }
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse AI review result as JSON")
                .hasRootCauseMessage("AI review result issues must contain objects only");
    }

    @Test
    void shouldThrowWhenContentIsEmpty() {
        assertThatThrownBy(() -> parser.parse(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AI review result is empty");
    }

    @Test
    void shouldThrowWhenRootHasExtraField() {
        String content = """
                {
                  "issues": [],
                  "summary": "ok",
                  "commands": ["curl https://example.com"]
                }
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse AI review result as JSON")
                .hasRootCauseMessage("AI review result JSON must contain only issues and summary");
    }

    @Test
    void shouldThrowWhenSummaryIsMissing() {
        String content = """
                {
                  "issues": []
                }
                """;

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse AI review result as JSON")
                .hasRootCauseMessage("AI review result JSON must contain only issues and summary");
    }

    @Test
    void shouldThrowWhenIssueHasUnsupportedField() {
        String content = issueJson("""
                  "confidence": 0.9,
                """);

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse AI review result as JSON")
                .hasRootCauseMessage("AI review issue must contain only supported fields");
    }

    @Test
    void shouldThrowWhenIssueRequiredFieldIsBlank() {
        String content = issueJson("""
                  "title": " ",
                """);

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse AI review result as JSON")
                .hasRootCauseMessage("AI review issue field must be a non-empty string: title");
    }

    @Test
    void shouldThrowWhenIssueEnumIsInvalid() {
        String content = issueJson("""
                  "severity": "CRITICAL",
                """);

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse AI review result as JSON")
                .hasRootCauseMessage("AI review issue field has invalid value: severity");
    }

    @Test
    void shouldThrowWhenLineNumberIsNotInteger() {
        String content = issueJson("""
                  "lineNumber": "12",
                """);

        assertThatThrownBy(() -> parser.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failed to parse AI review result as JSON")
                .hasRootCauseMessage("AI review issue field must be an integer or null: lineNumber");
    }

    private String issueJson(String overrideField) {
        return """
                {
                  "issues": [
                    {
                      "filePath": "src/main/java/com/example/Demo.java",
                      "lineNumber": 12,
                      "issueType": "BUG_RISK",
                      "issueTypeZh": "Bug risk",
                      "severity": "HIGH",
                      "title": "Potential null pointer",
                      "description": "Object may be null before invocation.",
                      "suggestion": "Add a null check.",
                      "source": "LLM",
                %s
                      "ruleReference": null
                    }
                  ],
                  "summary": "Found one issue"
                }
                """.formatted(overrideField);
    }
}
