package com.codepilot.module.agent.service.impl;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.agent.config.RagProperties;
import com.codepilot.module.agent.dto.ReviewRuleContext;
import com.codepilot.module.agent.service.ReviewRagService;
import com.codepilot.module.rag.dto.RuleSearchRecord;
import com.codepilot.module.rag.dto.RuleSearchResponse;
import com.codepilot.module.rag.service.RuleSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewRagServiceImpl implements ReviewRagService {

    private static final int MAX_QUERY_CHARS = 1500;

    private static final String CACHE_VERSION = "review-rag-v1";

    private final RagProperties ragProperties;

    private final RuleSearchService ruleSearchService;

    private final Map<String, CachedRuleContext> cache = new ConcurrentHashMap<>();

    @Override
    public List<ReviewRuleContext> retrieveRelevantRules(String filePath, String patch) {
        if (!ragProperties.isEnabled()) {
            log.info("RAG skipped because codepilot.rag.enabled=false, filePath={}", filePath);
            return List.of();
        }
        if (!StringUtils.hasText(patch)) {
            return List.of();
        }
        if (patch.trim().length() < Math.max(0, ragProperties.getMinContentLength())) {
            log.info("RAG skipped because patch is too short, filePath={}, patchLength={}", filePath, patch.length());
            return List.of();
        }

        try {
            String query = buildRuleSearchQuery(filePath, patch);
            if (!StringUtils.hasText(query)) {
                return List.of();
            }

            int topK = Math.max(1, ragProperties.getTopK());
            List<String> ruleTypes = inferRuleTypes(filePath, patch);
            String cacheKey = buildCacheKey(query, topK, ruleTypes);
            List<ReviewRuleContext> cachedContexts = getCached(cacheKey);
            if (cachedContexts != null) {
                log.info("RAG cache hit, filePath={}, ruleTypes={}, ruleCount={}, contextChars={}",
                        filePath, ruleTypes, cachedContexts.size(), totalContentLength(cachedContexts));
                return cachedContexts;
            }

            List<RuleSearchRecord> records = searchByRuleTypes(query, topK, ruleTypes);
            List<ReviewRuleContext> contexts = limitContext(records);
            putCached(cacheKey, contexts);
            log.info("RAG retrieved rules, filePath={}, ruleTypes={}, ruleCount={}, contextChars={}",
                    filePath, ruleTypes, contexts.size(), totalContentLength(contexts));
            return contexts;
        } catch (Exception exception) {
            log.warn("RAG retrieval failed, fallback to plain ai review, filePath={}, message={}",
                    filePath, SensitiveDataSanitizer.redact(exception.getMessage()));
            return List.of();
        }
    }

    private List<ReviewRuleContext> getCached(String cacheKey) {
        if (!isCacheEnabled() || !StringUtils.hasText(cacheKey)) {
            return null;
        }
        CachedRuleContext cached = cache.get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (cached.isExpired(cacheTtl())) {
            cache.remove(cacheKey, cached);
            return null;
        }
        cached.markAccessed();
        return copyContexts(cached.contexts());
    }

    private void putCached(String cacheKey, List<ReviewRuleContext> contexts) {
        if (!isCacheEnabled() || !StringUtils.hasText(cacheKey)) {
            return;
        }
        cache.put(cacheKey, new CachedRuleContext(copyContexts(contexts)));
        evictIfNeeded();
    }

    private void evictIfNeeded() {
        int maxSize = Math.max(1, ragProperties.getCacheMaxSize());
        if (cache.size() <= maxSize) {
            return;
        }
        cache.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().lastAccessedAt()))
                .limit(Math.max(1, cache.size() - maxSize))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(cache::remove);
    }

    private boolean isCacheEnabled() {
        return ragProperties.isCacheEnabled()
                && ragProperties.getCacheMaxSize() > 0
                && ragProperties.getCacheTtlSeconds() > 0;
    }

    private Duration cacheTtl() {
        return Duration.ofSeconds(Math.max(1, ragProperties.getCacheTtlSeconds()));
    }

    private String buildCacheKey(String query, int topK, List<String> ruleTypes) {
        MessageDigest digest = sha256();
        appendDigest(digest, CACHE_VERSION);
        appendDigest(digest, query);
        appendDigest(digest, Integer.toString(topK));
        appendDigest(digest, Integer.toString(Math.max(0, ragProperties.getMaxContextChars())));
        appendDigest(digest, String.join(",", ruleTypes == null ? List.of() : ruleTypes));
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    private void appendDigest(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private List<ReviewRuleContext> copyContexts(List<ReviewRuleContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        return contexts.stream()
                .filter(Objects::nonNull)
                .map(this::copyContext)
                .toList();
    }

    private ReviewRuleContext copyContext(ReviewRuleContext source) {
        ReviewRuleContext copy = new ReviewRuleContext();
        copy.setChunkId(source.getChunkId());
        copy.setDocumentId(source.getDocumentId());
        copy.setType(source.getType());
        copy.setContent(source.getContent());
        copy.setDistance(source.getDistance());
        return copy;
    }

    public String buildRuleSearchQuery(String filePath, String patch) {
        if (!StringUtils.hasText(patch)) {
            return "";
        }

        StringBuilder query = new StringBuilder();
        if (StringUtils.hasText(filePath)) {
            query.append("filePath: ").append(filePath.trim()).append('\n');
        }

        String addedLines = extractAddedLines(patch);
        query.append(truncate(addedLines, MAX_QUERY_CHARS));

        String normalizedPath = StringUtils.hasText(filePath)
                ? filePath.replace('\\', '/').toLowerCase(Locale.ROOT)
                : "";
        String normalizedPatch = patch.toLowerCase(Locale.ROOT);

        if (normalizedPath.endsWith(".java")) {
            query.append("\nJava Spring Boot 编码规范 异常处理 日志 安全 SQL");
        }
        if (containsAny(normalizedPatch, "select", "update", "delete", "insert", "mybatis", "mapper.xml")) {
            query.append("\nSQL 规范 参数绑定 SQL 注入");
        }
        if (containsAny(normalizedPatch, "redis", "cache")) {
            query.append("\nRedis 缓存规范");
        }
        if (containsAny(normalizedPatch, "password", "token", "secret", "key")) {
            query.append("\n安全规范 敏感信息");
        }

        return truncate(query.toString().trim(), MAX_QUERY_CHARS);
    }

    public List<String> inferRuleTypes(String filePath, String patch) {
        String normalizedPath = StringUtils.hasText(filePath)
                ? filePath.replace('\\', '/').toLowerCase(Locale.ROOT)
                : "";
        String normalizedPatch = StringUtils.hasText(patch) ? patch.toLowerCase(Locale.ROOT) : "";

        List<String> types = new ArrayList<>();
        if (containsAny(normalizedPatch, "select", "update", "delete", "insert", "mybatis", "mapper.xml", "${")) {
            types.add("SQL_RULE");
        }
        if (containsAny(normalizedPatch, "password", "passwd", "token", "secret", "accesskey", "apikey", "privatekey")) {
            types.add("SECURITY_RULE");
        }
        if (containsAny(normalizedPatch, "redis", "cache")) {
            types.add("REDIS_RULE");
        }
        if (normalizedPath.endsWith(".java")) {
            types.add("JAVA_STYLE");
            types.add("LOG_EXCEPTION_RULE");
            types.add("TEST_RULE");
        }
        return types.stream().distinct().toList();
    }

    private List<RuleSearchRecord> searchByRuleTypes(String query, int topK, List<String> ruleTypes) {
        RuleSearchResponse response = ruleSearchService.searchByTypes(query, topK, ruleTypes);
        List<RuleSearchRecord> mergedRecords = mergeTopK(response == null ? null : response.getRecords(), topK);
        if (!mergedRecords.isEmpty() || ruleTypes == null || ruleTypes.isEmpty()) {
            return mergedRecords;
        }

        log.info("RAG typed search returned no rule, fallback to unfiltered search, ruleTypes={}", ruleTypes);
        RuleSearchResponse fallbackResponse = ruleSearchService.searchByTypes(query, topK, List.of());
        return mergeTopK(fallbackResponse == null ? null : fallbackResponse.getRecords(), topK);
    }

    private List<RuleSearchRecord> mergeTopK(List<RuleSearchRecord> records, int topK) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        Map<String, RuleSearchRecord> deduplicatedRecords = new LinkedHashMap<>();
        for (RuleSearchRecord record : records) {
            if (record == null || !StringUtils.hasText(record.getContent())) {
                continue;
            }
            String key = dedupeKey(record);
            deduplicatedRecords.merge(key, record, this::nearestRecord);
        }

        return deduplicatedRecords.values().stream()
                .sorted(Comparator.comparing(
                        RuleSearchRecord::getDistance,
                        Comparator.nullsLast(Double::compareTo)
                ))
                .limit(topK)
                .toList();
    }

    private String dedupeKey(RuleSearchRecord record) {
        if (record.getChunkId() != null) {
            return "chunk:" + record.getChunkId();
        }
        return "content:" + record.getDocumentId() + ":" + record.getContent();
    }

    private RuleSearchRecord nearestRecord(RuleSearchRecord left, RuleSearchRecord right) {
        Double leftDistance = left.getDistance();
        Double rightDistance = right.getDistance();
        if (leftDistance == null) {
            return right;
        }
        if (rightDistance == null) {
            return left;
        }
        return leftDistance <= rightDistance ? left : right;
    }

    private String extractAddedLines(String patch) {
        StringBuilder addedLines = new StringBuilder();
        for (String line : patch.split("\\R")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                String added = line.substring(1).trim();
                if (StringUtils.hasText(added)) {
                    if (!addedLines.isEmpty()) {
                        addedLines.append('\n');
                    }
                    addedLines.append(added);
                }
            }
        }
        return addedLines.toString();
    }

    private List<ReviewRuleContext> limitContext(List<RuleSearchRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        int maxContextChars = Math.max(0, ragProperties.getMaxContextChars());
        int usedChars = 0;
        List<ReviewRuleContext> contexts = new ArrayList<>();
        for (RuleSearchRecord record : records) {
            if (record == null || !StringUtils.hasText(record.getContent())) {
                continue;
            }
            int remaining = maxContextChars - usedChars;
            if (remaining <= 0) {
                break;
            }

            ReviewRuleContext context = new ReviewRuleContext();
            context.setChunkId(record.getChunkId());
            context.setDocumentId(record.getDocumentId());
            context.setType(record.getType());
            context.setDistance(record.getDistance());
            context.setContent(truncate(record.getContent().trim(), remaining));
            usedChars += context.getContent().length();
            contexts.add(context);
        }
        return contexts;
    }

    private int totalContentLength(List<ReviewRuleContext> contexts) {
        return contexts.stream()
                .map(ReviewRuleContext::getContent)
                .filter(StringUtils::hasText)
                .mapToInt(String::length)
                .sum();
    }

    private boolean containsAny(String content, String... keywords) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String content, int maxLength) {
        if (content == null || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, Math.max(0, maxLength));
    }

    private static class CachedRuleContext {

        private final List<ReviewRuleContext> contexts;

        private final Instant createdAt;

        private volatile Instant lastAccessedAt;

        private CachedRuleContext(List<ReviewRuleContext> contexts) {
            Instant now = Instant.now();
            this.contexts = contexts == null ? List.of() : contexts;
            this.createdAt = now;
            this.lastAccessedAt = now;
        }

        private boolean isExpired(Duration ttl) {
            return createdAt.plus(ttl).isBefore(Instant.now());
        }

        private void markAccessed() {
            lastAccessedAt = Instant.now();
        }

        private List<ReviewRuleContext> contexts() {
            return contexts;
        }

        private Instant lastAccessedAt() {
            return lastAccessedAt;
        }
    }
}
