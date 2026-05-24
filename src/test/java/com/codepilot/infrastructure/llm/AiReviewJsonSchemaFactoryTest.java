package com.codepilot.infrastructure.llm;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiReviewJsonSchemaFactoryTest {

    private final AiReviewJsonSchemaFactory factory = new AiReviewJsonSchemaFactory();

    @Test
    void shouldBuildStrictAiReviewResponseFormat() {
        ResponseFormat responseFormat = factory.responseFormat();
        JsonSchema schema = responseFormat.jsonSchema();
        JsonObjectSchema root = (JsonObjectSchema) schema.rootElement();

        assertThat(responseFormat.type()).isEqualTo(ResponseFormatType.JSON);
        assertThat(schema.name()).isEqualTo("ai_review_result");
        assertThat(root.required()).containsExactly("issues", "summary");
        assertThat(root.additionalProperties()).isFalse();
        assertThat(root.properties()).containsOnlyKeys("issues", "summary");
    }

    @Test
    void shouldRestrictIssueFieldsEnumsAndNullableFields() {
        JsonObjectSchema root = (JsonObjectSchema) factory.schema().rootElement();
        JsonArraySchema issues = (JsonArraySchema) root.properties().get("issues");
        JsonObjectSchema issue = (JsonObjectSchema) issues.items();

        assertThat(issue.required()).containsExactly(
                "filePath",
                "lineNumber",
                "issueType",
                "issueTypeZh",
                "severity",
                "title",
                "description",
                "suggestion",
                "source",
                "ruleReference"
        );
        assertThat(issue.additionalProperties()).isFalse();
        assertThat(((JsonEnumSchema) issue.properties().get("issueType")).enumValues())
                .containsExactlyInAnyOrder(
                        "BUG_RISK",
                        "SECURITY",
                        "PERFORMANCE",
                        "STYLE",
                        "SQL_RISK",
                        "EXCEPTION_HANDLING",
                        "LOGGING",
                        "TEST_MISSING"
                );
        assertThat(((JsonEnumSchema) issue.properties().get("severity")).enumValues())
                .containsExactly("HIGH", "MEDIUM", "LOW");
        assertThat(((JsonEnumSchema) issue.properties().get("source")).enumValues())
                .containsExactly("LLM");
        assertThat(((JsonAnyOfSchema) issue.properties().get("filePath")).anyOf()).hasSize(2);
        assertThat(((JsonAnyOfSchema) issue.properties().get("lineNumber")).anyOf()).hasSize(2);
        assertThat(((JsonAnyOfSchema) issue.properties().get("ruleReference")).anyOf()).hasSize(2);
        assertThat(issue.properties().keySet()).containsExactlyInAnyOrderElementsOf(List.of(
                "filePath",
                "lineNumber",
                "issueType",
                "issueTypeZh",
                "severity",
                "title",
                "description",
                "suggestion",
                "source",
                "ruleReference"
        ));
    }
}
