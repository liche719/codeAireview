package com.codepilot.module.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("review_task_outbox")
public class ReviewTaskOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private String status;

    private Integer attemptCount;

    private LocalDateTime nextAttemptAt;

    private LocalDateTime lockedAt;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
