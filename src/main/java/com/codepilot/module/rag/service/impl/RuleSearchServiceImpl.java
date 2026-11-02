package com.codepilot.module.rag.service.impl;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.infrastructure.embedding.EmbeddingService;
import com.codepilot.module.rag.dto.RuleSearchRecord;
import com.codepilot.module.rag.dto.RuleSearchRequest;
import com.codepilot.module.rag.dto.RuleSearchResponse;
import com.codepilot.module.rag.mapper.RuleChunkMapper;
import com.codepilot.module.rag.service.RuleSearchService;
import com.codepilot.module.rag.util.PgVectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleSearchServiceImpl implements RuleSearchService {

    private static final int DEFAULT_TOP_K = 3;

    private static final int MAX_TOP_K = 10;

    private final EmbeddingService embeddingService;

    private final RuleChunkMapper ruleChunkMapper;

    @Override
    public RuleSearchResponse search(RuleSearchRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuery())) {
            throw new BusinessException("rule search query must not be blank");
        }

        int topK = normalizeTopK(request.getTopK());
        String type = StringUtils.hasText(request.getType()) ? request.getType().trim() : null;

        String query = request.getQuery().trim();
        // 2026-11-02：两路并发召回 + RRF 融合。
        // 向量擅长"语义差不多"，精确词擅长"就是这一条"（@Transactional / ERR_502 / 类名）。
        List<RuleSearchRecord> vectorHits = List.of();
        try {
            List<Float> embedding = embeddingService.embed(query);
            vectorHits = ruleChunkMapper.search(PgVectorUtils.toVectorString(embedding), type, topK * 3);
        } catch (RuntimeException exception) {
            // embedding 未配置/不可用时降级为纯关键词检索，而不是让"查团队规范"直接失败
            log.warn("语义检索不可用，降级为关键词检索, queryLength={}, reason={}",
                    query.length(), exception.getMessage());
        }
        List<RuleSearchRecord> lexicalHits = lexicalSearch(query, type, topK * 3);
        List<RuleSearchRecord> records = fuseByRrf(vectorHits, lexicalHits, topK);
        log.info("Rule search completed, queryLength={}, topK={}, type={}, vector={}, lexical={}, resultCount={}",
                query.length(), topK, type, vectorHits.size(), lexicalHits.size(), records.size());
        return new RuleSearchResponse(records);
    }

    @Override
    public RuleSearchResponse searchByTypes(String query, Integer topK, List<String> types) {
        if (!StringUtils.hasText(query)) {
            throw new BusinessException("rule search query must not be blank");
        }

        int normalizedTopK = normalizeTopK(topK);
        String trimmedQuery = query.trim();
        List<String> normalizedTypes = normalizeTypes(types);

        // 2026-11-02：这里原来只有向量一条路，embedding 不可用时**整条能力不可用**——
        // 而 K 类工具（searchTeamRules）恰好走这条路。现在同样做成向量 + 精确词融合。
        String vectorString = null;
        try {
            vectorString = PgVectorUtils.toVectorString(embeddingService.embed(trimmedQuery));
        } catch (RuntimeException exception) {
            log.warn("语义检索不可用，仅用关键词检索, queryLength={}, reason={}",
                    trimmedQuery.length(), exception.getMessage());
        }

        if (normalizedTypes.isEmpty()) {
            List<RuleSearchRecord> vectorHits = vectorString == null
                    ? List.of() : ruleChunkMapper.search(vectorString, null, normalizedTopK * 3);
            List<RuleSearchRecord> lexicalHits = lexicalSearch(trimmedQuery, null, normalizedTopK * 3);
            List<RuleSearchRecord> records = fuseByRrf(vectorHits, lexicalHits, normalizedTopK);
            log.info("Rule search completed, queryLength={}, topK={}, types=[], vector={}, lexical={}, resultCount={}",
                    trimmedQuery.length(), normalizedTopK, vectorHits.size(), lexicalHits.size(), records.size());
            return new RuleSearchResponse(records);
        }

        List<RuleSearchRecord> records = new ArrayList<>();
        for (String type : normalizedTypes) {
            List<RuleSearchRecord> vectorHits = vectorString == null
                    ? List.of() : ruleChunkMapper.search(vectorString, type, normalizedTopK * 3);
            List<RuleSearchRecord> lexicalHits = lexicalSearch(trimmedQuery, type, normalizedTopK * 3);
            records.addAll(fuseByRrf(vectorHits, lexicalHits, normalizedTopK));
        }

        List<RuleSearchRecord> mergedRecords = mergeTopK(records, normalizedTopK);
        log.info("Rule typed search completed, queryLength={}, topK={}, types={}, resultCount={}",
                query.length(), normalizedTopK, normalizedTypes, mergedRecords.size());
        return new RuleSearchResponse(mergedRecords);
    }

    /**
     * RRF（倒数排名融合，2026-11-02）。
     *
     * <p>为什么不直接把分数相加：向量给的是 0~1 的余弦距离，关键词那一路的"分"是 0
     * （见 SQL 里 {@code CAST(0 AS double precision)}）——两者量纲不可比，加权重调参又不可解释。
     * RRF 只看名次：{@code 1/(60+rank)}，天然抹平尺度差异，且两路都靠前的条目会被顶上来。
     *
     * <p>常数 60 是业界常用值：足够大，避免某一路的第一名直接霸榜。
     */
    private List<RuleSearchRecord> fuseByRrf(List<RuleSearchRecord> vectorHits,
                                             List<RuleSearchRecord> lexicalHits, int topK) {
        java.util.Map<String, Double> scores = new java.util.LinkedHashMap<>();
        java.util.Map<String, RuleSearchRecord> byKey = new java.util.LinkedHashMap<>();
        java.util.function.BiConsumer<List<RuleSearchRecord>, String> accumulate = (hits, tag) -> {
            if (hits == null) {
                return;
            }
            int rank = 0;
            for (RuleSearchRecord record : hits) {
                if (record == null || !StringUtils.hasText(record.getContent())) {
                    continue;
                }
                String key = dedupeKey(record);
                rank++;
                scores.merge(key, 1.0 / (60 + rank), Double::sum);
                byKey.putIfAbsent(key, record);
            }
        };
        accumulate.accept(vectorHits, "vector");
        accumulate.accept(lexicalHits, "lexical");
        if (scores.isEmpty()) {
            return List.of();
        }
        return scores.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> expandNeighbors(byKey.get(entry.getKey())))
                .toList();
    }

    /**
     * 精确词检索：把查询拆成词，OR 召回后**按命中词数重排**（2026-11-02）。
     *
     * <p>拆词规则刻意很简单：空白 + 常见标点切分，丢掉长度 < 2 的碎片。
     * 中文分词不上分词器——规范里的关键 token 多是标识符、错误码、类名，
     * 按空白切就够；复杂中文语义交给向量那一路。
     */
    private List<RuleSearchRecord> lexicalSearch(String query, String type, int limit) {
        List<String> terms = new java.util.ArrayList<>();
        for (String piece : query.split("[\\s,，、;；:：/|()（）\\[\\]{}<>\"']+")) {
            String term = piece.trim();
            if (term.length() >= 2 && !terms.contains(term)) {
                terms.add(term);
            }
        }
        if (terms.isEmpty()) {
            return List.of();
        }
        List<RuleSearchRecord> candidates = ruleChunkMapper.searchByTerms(terms, query, type, Math.max(limit * 3, 20));
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .map(record -> java.util.Map.entry(record, matchedTerms(record.getContent(), terms)))
                .sorted(java.util.Comparator
                        .comparingInt((java.util.Map.Entry<RuleSearchRecord, Integer> entry) -> entry.getValue())
                        .reversed())
                .filter(entry -> entry.getValue() > 0)
                .limit(limit)
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    /** 这条规范命中了几个查询词（大小写不敏感） */
    /**
     * 命中块的**邻块扩展**（2026-11-02）。
     *
     * <p>没有 Rerank 模型时的取舍：用小片去检索（精准、噪音小），命中后再把前后相邻块拼回来
     * （上下文完整）。上限 1200 字，避免把整篇规范塞进上下文。
     *
     * <p>这不是标准的父子文档（那需要入库时维护父子关系），而是**低成本的等价做法**：
     * 只多一次按 (document_id, chunk_index) 的查询，不改表结构、不改切块。
     */
    private RuleSearchRecord expandNeighbors(RuleSearchRecord record) {
        if (record == null || record.getDocumentId() == null || !StringUtils.hasText(record.getContent())) {
            return record;
        }
        try {
            int index = record.getChunkIndex() == null ? 0 : record.getChunkIndex();
            // 只扩前后各 1 块；并且**命中的那块放最前面**——否则模型先读到的可能是邻块，
            // 会把"相关规范"误当成"命中规范"（实测把 SQL 规范排在了幂等规范前面）。
            List<String> neighbors = ruleChunkMapper.selectNeighborContents(
                    record.getDocumentId(), Math.max(0, index - 1), index + 1);
            if (neighbors == null || neighbors.isEmpty()) {
                return record;
            }
            StringBuilder merged = new StringBuilder();
            merged.append(record.getContent().trim());
            for (String piece : neighbors) {
                if (!StringUtils.hasText(piece) || piece.trim().equals(record.getContent().trim())) {
                    continue;
                }
                if (merged.length() + piece.length() > 1200) {
                    break;
                }
                merged.append('\n').append(piece.trim());
            }
            String expanded = merged.toString();
            record.setContent(expanded);
            return record;
        } catch (RuntimeException exception) {
            log.debug("邻块扩展失败（用原块返回）：{}", exception.getMessage());
            return record;
        }
    }

    private int matchedTerms(String content, List<String> terms) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        String lower = content.toLowerCase();
        int hits = 0;
        for (String term : terms) {
            if (lower.contains(term.toLowerCase())) {
                hits++;
            }
        }
        return hits;
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null) {
            return DEFAULT_TOP_K;
        }
        if (topK < 1) {
            throw new BusinessException("topK must be greater than 0");
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private List<String> normalizeTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        return types.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
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
            deduplicatedRecords.merge(dedupeKey(record), record, this::nearestRecord);
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
}
