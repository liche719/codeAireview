package com.codepilot.module.command.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pr_command_task_outbox")
public class PrCommandTaskOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long commandTaskId;

    private String status;

    private Integer attemptCount;

    private LocalDateTime nextAttemptAt;

    private LocalDateTime lockedAt;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
