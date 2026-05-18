package com.codepilot.module.agent.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
                      "severity": "HIGH",
                      "title": "Potential null pointer",
                      "description": "Object may be null before invocation.",
                      "suggestion": "Add a null check.",
                      "ruleReference": null
                    }
                  ],
                  "summary": "Found one issue"
                }
                """;

        var result = parser.parse(content);

        assertThat(result.getIssues()).hasSize(1);
        assertThat(result.getIssues().getFirst().getSeverity()).isEqualTo("HIGH");
        assertThat(result.getSummary()).isEqualTo("Found one issue");
    }

    @Test
    void shouldParseFencedJson() {
        String content = """
                ```json
                {
                  "issues": [],
                  "summary": "No issues found"
                }
                ```
                """;

        var result = parser.parse(content);

        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getSummary()).isEqualTo("No issues found");
    }

    @Test
    void shouldReturnEmptyIssuesWhenJsonIsInvalid() {
        String content = """
                { invalid json }
                """;

        var result = parser.parse(content);

        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getSummary()).isNull();
    }
}

