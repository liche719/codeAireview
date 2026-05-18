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

import java.util.List;

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

    private int normalizeTopK(Integer topK) {
        if (topK == null) {
            return DEFAULT_TOP_K;
        }
        if (topK < 1) {
            throw new BusinessException("topK must be greater than 0");
        }
        return Math.min(topK, MAX_TOP_K);
    }
}
