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
        List<Float> embedding = embeddingService.embed(request.getQuery().trim());
        String vectorString = PgVectorUtils.toVectorString(embedding);
        String type = StringUtils.hasText(request.getType()) ? request.getType().trim() : null;

        List<RuleSearchRecord> records = ruleChunkMapper.search(vectorString, type, topK);
        log.info("Rule search completed, queryLength={}, topK={}, type={}, resultCount={}",
                request.getQuery().length(), topK, type, records.size());
        return new RuleSearchResponse(records);
    }

    @Override
    public RuleSearchResponse searchByTypes(String query, Integer topK, List<String> types) {
        if (!StringUtils.hasText(query)) {
            throw new BusinessException("rule search query must not be blank");
        }

        int normalizedTopK = normalizeTopK(topK);
        List<Float> embedding = embeddingService.embed(query.trim());
        String vectorString = PgVectorUtils.toVectorString(embedding);
        List<String> normalizedTypes = normalizeTypes(types);

        if (normalizedTypes.isEmpty()) {
            List<RuleSearchRecord> records = ruleChunkMapper.search(vectorString, null, normalizedTopK);
            log.info("Rule search completed, queryLength={}, topK={}, types=[], resultCount={}",
                    query.length(), normalizedTopK, records.size());
            return new RuleSearchResponse(records);
        }

        List<RuleSearchRecord> records = new ArrayList<>();
        for (String type : normalizedTypes) {
            records.addAll(ruleChunkMapper.search(vectorString, type, normalizedTopK));
        }

        List<RuleSearchRecord> mergedRecords = mergeTopK(records, normalizedTopK);
        log.info("Rule typed search completed, queryLength={}, topK={}, types={}, resultCount={}",
                query.length(), normalizedTopK, normalizedTypes, mergedRecords.size());
        return new RuleSearchResponse(mergedRecords);
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
