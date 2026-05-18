package com.codepilot.module.rag.service.impl;

import com.codepilot.infrastructure.embedding.EmbeddingService;
import com.codepilot.module.rag.dto.RuleSearchRecord;
import com.codepilot.module.rag.mapper.RuleChunkMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleSearchServiceImplTest {

    @Test
    void shouldEmbedOnceWhenSearchingMultipleTypes() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        RuleChunkMapper ruleChunkMapper = mock(RuleChunkMapper.class);
        when(embeddingService.embed("select user by token")).thenReturn(List.of(0.1F, 0.2F));
        when(ruleChunkMapper.search(anyString(), eq("SQL_RULE"), eq(2)))
                .thenReturn(List.of(ruleRecord(1L, "SQL_RULE", 0.1D)));
        when(ruleChunkMapper.search(anyString(), eq("SECURITY_RULE"), eq(2)))
                .thenReturn(List.of(ruleRecord(2L, "SECURITY_RULE", 0.2D)));

        RuleSearchServiceImpl service = new RuleSearchServiceImpl(embeddingService, ruleChunkMapper);

        var response = service.searchByTypes("select user by token", 2, List.of("SQL_RULE", "SECURITY_RULE"));

        assertThat(response.getRecords()).extracting("type").containsExactly("SQL_RULE", "SECURITY_RULE");
        verify(embeddingService).embed("select user by token");
        verify(ruleChunkMapper).search(anyString(), eq("SQL_RULE"), eq(2));
        verify(ruleChunkMapper).search(anyString(), eq("SECURITY_RULE"), eq(2));
    }

    private RuleSearchRecord ruleRecord(Long chunkId, String type, Double distance) {
        RuleSearchRecord record = new RuleSearchRecord();
        record.setChunkId(chunkId);
        record.setDocumentId(chunkId + 100);
        record.setContent(type + " content");
        record.setType(type);
        record.setDistance(distance);
        return record;
    }
}
