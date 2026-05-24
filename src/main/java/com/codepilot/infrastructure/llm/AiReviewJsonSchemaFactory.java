package com.codepilot.infrastructure.llm;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.springframework.stereotype.Component;

@Component
public class AiReviewJsonSchemaFactory {

    public ResponseFormat responseFormat() {
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(schema())
                .build();
    }

    public JsonSchema schema() {
        return JsonSchema.builder()
                .name("ai_review_result")
                .rootElement(rootSchema())
                .build();
    }

    private JsonObjectSchema rootSchema() {
        return JsonObjectSchema.builder()
                .addProperty("issues", JsonArraySchema.builder()
                        .items(issueSchema())
                        .build())
                .addStringProperty("summary", "Short Chinese review summary.")
                .required("issues", "summary")
                .additionalProperties(false)
                .build();
    }

    private JsonObjectSchema issueSchema() {
        return JsonObjectSchema.builder()
                .addProperty("filePath", nullableString("Changed file path or null when not applicable."))
                .addProperty("lineNumber", nullableInteger("Changed line number or null when not applicable."))
                .addEnumProperty("issueType", java.util.List.of(
                        "BUG_RISK",
                        "SECURITY",
                        "PERFORMANCE",
                        "STYLE",
                        "SQL_RISK",
                        "EXCEPTION_HANDLING",
                        "LOGGING",
                        "TEST_MISSING"
                ))
                .addStringProperty("issueTypeZh", "Chinese short label, for example SQL 风险 / 安全风险 / 代码风格.")
                .addEnumProperty("severity", java.util.List.of("HIGH", "MEDIUM", "LOW"))
                .addStringProperty("title", "Short issue title.")
                .addStringProperty("description", "Evidence-based issue description.")
                .addStringProperty("suggestion", "Actionable fix suggestion.")
                .addEnumProperty("source", java.util.List.of("LLM"), "LLM-only source. Tool findings are merged server-side.")
                .addProperty("ruleReference", nullableString("Short rule reference or null."))
                .required(
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
                )
                .additionalProperties(false)
                .build();
    }

    private JsonSchemaElement nullableString(String description) {
        return JsonAnyOfSchema.builder()
                .description(description)
                .anyOf(
                        JsonStringSchema.builder().description(description).build(),
                        new JsonNullSchema()
                )
                .build();
    }

    private JsonSchemaElement nullableInteger(String description) {
        return JsonAnyOfSchema.builder()
                .description(description)
                .anyOf(
                        JsonIntegerSchema.builder().description(description).build(),
                        new JsonNullSchema()
                )
                .build();
    }
}
