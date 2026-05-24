package com.codepilot.module.agent.review.cache;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewLlmCacheTest {

    @Test
    void shouldDeleteExpiredEntriesByConfiguredTtl() {
        LlmProperties properties = new LlmProperties();
        properties.setReviewCacheEnabled(true);
        properties.setReviewCacheTtlDays(7);
        AiReviewCacheMapper mapper = mock(AiReviewCacheMapper.class);
        when(mapper.deleteExpired(org.mockito.Mockito.any())).thenReturn(3);
        ReviewLlmCache cache = new ReviewLlmCache(
                properties,
                mapper,
                new ObjectMapper(),
                mock(ReviewLlmCacheKeyBuilder.class)
        );
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        LocalDateTime before = LocalDateTime.now().minusDays(7).minusSeconds(2);
        LocalDateTime after = LocalDateTime.now().minusDays(7).plusSeconds(2);

        int deletedCount = cache.evictExpired();

        assertThat(deletedCount).isEqualTo(3);
        verify(mapper).deleteExpired(cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isBetween(before, after);
    }

    @Test
    void shouldNotDeleteExpiredEntriesWhenCacheIsDisabled() {
        LlmProperties properties = new LlmProperties();
        properties.setReviewCacheEnabled(false);
        AiReviewCacheMapper mapper = mock(AiReviewCacheMapper.class);
        ReviewLlmCache cache = new ReviewLlmCache(
                properties,
                mapper,
                new ObjectMapper(),
                mock(ReviewLlmCacheKeyBuilder.class)
        );

        int deletedCount = cache.evictExpired();

        assertThat(deletedCount).isZero();
        verify(mapper, never()).deleteExpired(org.mockito.Mockito.any());
    }
}
