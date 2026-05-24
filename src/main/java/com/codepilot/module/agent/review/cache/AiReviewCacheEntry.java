package com.codepilot.module.agent.review.cache;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_review_cache")
public class AiReviewCacheEntry {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cacheKey;

    private String provider;

    private String modelName;

    private String promptSignature;

    private String filePath;

    private String resultJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime lastUsedAt;
}
