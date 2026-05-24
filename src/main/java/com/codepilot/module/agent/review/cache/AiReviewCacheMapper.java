package com.codepilot.module.agent.review.cache;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AiReviewCacheMapper extends BaseMapper<AiReviewCacheEntry> {

    @Select("""
            SELECT id, cache_key, provider, model_name, prompt_signature, file_path, result_json,
                   created_at, updated_at, last_used_at
            FROM ai_review_cache
            WHERE cache_key = #{cacheKey}
              AND updated_at >= #{updatedAfter}
            LIMIT 1
            """)
    AiReviewCacheEntry findFreshByCacheKey(
            @Param("cacheKey") String cacheKey,
            @Param("updatedAfter") LocalDateTime updatedAfter
    );

    @Update("""
            UPDATE ai_review_cache
            SET last_used_at = #{lastUsedAt}
            WHERE id = #{id}
            """)
    void updateLastUsedAt(
            @Param("id") Long id,
            @Param("lastUsedAt") LocalDateTime lastUsedAt
    );

    @Insert("""
            INSERT INTO ai_review_cache (
                cache_key, provider, model_name, prompt_signature, file_path, result_json,
                created_at, updated_at, last_used_at
            )
            VALUES (
                #{cacheKey}, #{provider}, #{modelName}, #{promptSignature}, #{filePath}, #{resultJson},
                #{createdAt}, #{updatedAt}, #{lastUsedAt}
            )
            ON CONFLICT (cache_key) DO UPDATE SET
                provider = EXCLUDED.provider,
                model_name = EXCLUDED.model_name,
                prompt_signature = EXCLUDED.prompt_signature,
                file_path = EXCLUDED.file_path,
                result_json = EXCLUDED.result_json,
                updated_at = EXCLUDED.updated_at,
                last_used_at = EXCLUDED.last_used_at
            """)
    void upsert(AiReviewCacheEntry entry);
}
