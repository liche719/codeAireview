package com.codepilot.module.review.cost;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次真实模型调用的用量（2026-10-29）：用于按天/按模型统计花费。 */
@Data
@TableName("llm_usage")
public class LlmUsage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的审查任务；为 null 表示非审查场景（例如 agent 对话） */
    private Long taskId;

    private String modelName;

    private Long promptTokens;

    private Long completionTokens;

    /** 没有配置单价时为 null —— 表示"不知道花了多少"，不是 0 */
    private Double estimatedCost;

    private LocalDateTime createdAt;
}
