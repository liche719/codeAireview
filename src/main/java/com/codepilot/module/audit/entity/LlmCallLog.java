package com.codepilot.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("llm_call_log")
public class LlmCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private String modelName;

    private Long costTimeMs;

    private String requestSummary;

    private String responseSummary;

    private Boolean success;

    private String errorMessage;

    private LocalDateTime createdAt;
}

