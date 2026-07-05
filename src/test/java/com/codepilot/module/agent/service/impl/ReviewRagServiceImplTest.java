package com.codepilot.module.agent.service.impl;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.agent.config.RagProperties;
import com.codepilot.module.rag.dto.RuleSearchRecord;
import com.codepilot.module.rag.dto.RuleSearchResponse;
import com.codepilot.module.rag.service.RuleSearchService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertThat(service.inferRuleTypes("src/main/java/DemoMapper.java", patch))
                .contains("SQL_RULE", "SECURITY_RULE", "REDIS_RULE", "JAVA_STYLE", "LOG_EXCEPTION_RULE", "TEST_RULE");
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

    @Test
    void shouldSearchByInferredRuleTypesAndMergeTopK() {
        RagProperties properties = defaultProperties();
        properties.setTopK(2);
        List<String> requestedTypes = new ArrayList<>();
        RuleSearchService ruleSearchService = request -> {
            requestedTypes.add(request.getType());
            if ("SQL_RULE".equals(request.getType())) {
                return new RuleSearchResponse(List.of(ruleRecord(1L, "SQL_RULE", "SQL rule", 0.10D)));
            }
            if ("SECURITY_RULE".equals(request.getType())) {
                return new RuleSearchResponse(List.of(ruleRecord(2L, "SECURITY_RULE", "Security rule", 0.20D)));
            }
            if ("REDIS_RULE".equals(request.getType())) {
                return new RuleSearchResponse(List.of(ruleRecord(3L, "REDIS_RULE", "Redis rule", 0.30D)));
            }
            return new RuleSearchResponse(List.of());
        };

        ReviewRagServiceImpl service = new ReviewRagServiceImpl(properties, ruleSearchService);
        var contexts = service.retrieveRelevantRules(
                "src/main/java/DemoMapper.java",
                "+String sql = \"select * from user where token = 'abc'\";\n+redisTemplate.opsForValue().get(\"k\");"
        );

        assertThat(requestedTypes)
                .containsExactly("SQL_RULE", "SECURITY_RULE", "REDIS_RULE", "JAVA_STYLE", "LOG_EXCEPTION_RULE", "TEST_RULE");
        assertThat(contexts).hasSize(2);
        assertThat(contexts).extracting("type").containsExactly("SQL_RULE", "SECURITY_RULE");
    }

    @Test
    void shouldFallbackToUnfilteredSearchWhenTypedSearchHasNoResult() {
        RagProperties properties = defaultProperties();
        List<String> requestedTypes = new ArrayList<>();
        RuleSearchService ruleSearchService = request -> {
            requestedTypes.add(request.getType());
            if (request.getType() == null) {
                return new RuleSearchResponse(List.of(ruleRecord(9L, "GENERAL_RULE", "General rule", 0.50D)));
            }
            return new RuleSearchResponse(List.of());
        };

        ReviewRagServiceImpl service = new ReviewRagServiceImpl(properties, ruleSearchService);
        var contexts = service.retrieveRelevantRules(
                "src/main/java/DemoMapper.java",
                "+String sql = \"select * from user\";"
        );

        assertThat(requestedTypes).contains("SQL_RULE", "JAVA_STYLE", "LOG_EXCEPTION_RULE", "TEST_RULE", null);
        assertThat(contexts).hasSize(1);
        assertThat(contexts.getFirst().getType()).isEqualTo("GENERAL_RULE");
    }

    @Test
    void shouldReuseCachedRuleContextsForSameQueryAndRuleTypes() {
        RagProperties properties = defaultProperties();
        AtomicInteger searchCount = new AtomicInteger();
        RuleSearchService ruleSearchService = request -> {
            searchCount.incrementAndGet();
            return new RuleSearchResponse(List.of(ruleRecord(1L, request.getType(), "Rule", 0.10D)));
        };
        ReviewRagServiceImpl service = new ReviewRagServiceImpl(properties, ruleSearchService);

        var first = service.retrieveRelevantRules("src/main/java/DemoMapper.java", "+String sql = \"select * from user\";");
        int searchesAfterFirstCall = searchCount.get();
        var second = service.retrieveRelevantRules("src/main/java/DemoMapper.java", "+String sql = \"select * from user\";");
        first.getFirst().setContent("mutated by caller");

        assertThat(searchesAfterFirstCall).isGreaterThan(0);
        assertThat(searchCount.get()).isEqualTo(searchesAfterFirstCall);
        assertThat(second.getFirst().getContent()).isEqualTo("Rule");
    }

    @Test
    void shouldBypassCacheWhenRagCacheIsDisabled() {
        RagProperties properties = defaultProperties();
        properties.setCacheEnabled(false);
        AtomicInteger searchCount = new AtomicInteger();
        RuleSearchService ruleSearchService = request -> {
            searchCount.incrementAndGet();
            return new RuleSearchResponse(List.of(ruleRecord(1L, request.getType(), "Rule", 0.10D)));
        };
        ReviewRagServiceImpl service = new ReviewRagServiceImpl(properties, ruleSearchService);

        service.retrieveRelevantRules("src/main/java/DemoMapper.java", "+String sql = \"select * from user\";");
        int searchesAfterFirstCall = searchCount.get();
        service.retrieveRelevantRules("src/main/java/DemoMapper.java", "+String sql = \"select * from user\";");

        assertThat(searchesAfterFirstCall).isGreaterThan(0);
        assertThat(searchCount.get()).isEqualTo(searchesAfterFirstCall * 2);
    }

    @Test
    void shouldEvictLeastRecentlyUsedCachedRuleContextsWhenCacheExceedsMaxSize() {
        RagProperties properties = defaultProperties();
        properties.setCacheMaxSize(1);
        AtomicInteger searchCount = new AtomicInteger();
        RuleSearchService ruleSearchService = request -> {
            int count = searchCount.incrementAndGet();
            return new RuleSearchResponse(List.of(ruleRecord((long) count, request.getType(), "Rule " + count, 0.10D)));
        };
        ReviewRagServiceImpl service = new ReviewRagServiceImpl(properties, ruleSearchService);

        service.retrieveRelevantRules("README.md", "+select * from user");
        service.retrieveRelevantRules("README.md", "+String token = request.getToken();");
        service.retrieveRelevantRules("README.md", "+select * from user");

        assertThat(searchCount.get()).isEqualTo(3);
    }

    private RuleSearchRecord ruleRecord(Long chunkId, String type, String content, Double distance) {
        RuleSearchRecord record = new RuleSearchRecord();
        record.setChunkId(chunkId);
        record.setDocumentId(chunkId + 100);
        record.setType(type);
        record.setContent(content);
        record.setDistance(distance);
        return record;
    }

    private RagProperties defaultProperties() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.setTopK(3);
        properties.setMaxContextChars(2000);
        properties.setMinContentLength(1);
        properties.setCacheEnabled(true);
        properties.setCacheMaxSize(256);
        properties.setCacheTtlSeconds(300);
        return properties;
    }
}
