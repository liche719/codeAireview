package com.codepilot.module.agent.service.impl;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.agent.config.RagProperties;
import com.codepilot.module.rag.dto.RuleSearchRecord;
import com.codepilot.module.rag.dto.RuleSearchResponse;
import com.codepilot.module.rag.service.RuleSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewRagServiceImplTest {

    @Test
    void shouldReturnEmptyListWhenRagIsDisabled() {
        AtomicBoolean searched = new AtomicBoolean(false);
        RagProperties properties = defaultProperties();
        properties.setEnabled(false);
        RuleSearchService ruleSearchService = request -> {
            searched.set(true);
            return new RuleSearchResponse(List.of());
        };

        ReviewRagServiceImpl service = new ReviewRagServiceImpl(properties, ruleSearchService);

        assertThat(service.retrieveRelevantRules("src/main/java/Demo.java", "+String name = request.getName();"))
                .isEmpty();
        assertThat(searched).isFalse();
    }

    @Test
    void shouldReturnEmptyListWhenPatchIsBlank() {
        RagProperties properties = defaultProperties();
        RuleSearchService ruleSearchService = request -> {
            throw new AssertionError("search should not be called");
        };

        ReviewRagServiceImpl service = new ReviewRagServiceImpl(properties, ruleSearchService);

        assertThat(service.retrieveRelevantRules("src/main/java/Demo.java", "   ")).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenRuleSearchFails() {
        RagProperties properties = defaultProperties();
        RuleSearchService ruleSearchService = request -> {
            throw new BusinessException("embedding api key is missing");
        };

        ReviewRagServiceImpl service = new ReviewRagServiceImpl(properties, ruleSearchService);

        assertThat(service.retrieveRelevantRules("src/main/java/Demo.java", "+String name = request.getName();"))
                .isEmpty();
    }

    @Test
    void shouldBuildQueryFromAddedLinesAndKeywords() {
        RagProperties properties = defaultProperties();
        ReviewRagServiceImpl service = new ReviewRagServiceImpl(properties, request -> new RuleSearchResponse(List.of()));
        String patch = """
                --- a/src/main/java/DemoMapper.java
                +++ b/src/main/java/DemoMapper.java
                +String sql = "select * from user where name = '" + name + "'";
                -String old = "";
                +redisTemplate.opsForValue().set("token", token);
                """;

        String query = service.buildRuleSearchQuery("src/main/java/DemoMapper.java", patch);

        assertThat(query).contains("filePath: src/main/java/DemoMapper.java");
        assertThat(query).contains("String sql");
        assertThat(query).doesNotContain("++ b/");
        assertThat(query).contains("Java Spring Boot 编码规范");
        assertThat(query).contains("SQL 规范 参数绑定 SQL 注入");
        assertThat(query).contains("Redis 缓存规范");
        assertThat(query).contains("安全规范 敏感信息");
    }

    @Test
    void shouldLimitReturnedRuleContextLength() {
        RagProperties properties = defaultProperties();
        properties.setMaxContextChars(10);
        RuleSearchRecord record = new RuleSearchRecord();
        record.setChunkId(1L);
        record.setDocumentId(2L);
        record.setType("SQL_RULE");
        record.setContent("禁止字符串拼接 SQL，应使用参数绑定。");
        record.setDistance(0.12D);
        RuleSearchService ruleSearchService = request -> new RuleSearchResponse(List.of(record));

        ReviewRagServiceImpl service = new ReviewRagServiceImpl(properties, ruleSearchService);

        var contexts = service.retrieveRelevantRules("src/main/java/DemoMapper.java", "+String sql = \"select * from user\";");

        assertThat(contexts).hasSize(1);
        assertThat(contexts.getFirst().getContent()).hasSize(10);
    }

    private RagProperties defaultProperties() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.setTopK(3);
        properties.setMaxContextChars(2000);
        properties.setMinContentLength(1);
        return properties;
    }
}
