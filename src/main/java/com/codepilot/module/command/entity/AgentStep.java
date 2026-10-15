package com.codepilot.module.command.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_step")
public class AgentStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentRunId;
    private Integer sequenceNo;
    private String stepType;
    private String name;
    private String status;
    private String inputSummary;
    private String outputSummary;
    private Long durationMillis;
    private LocalDateTime createdAt;
}
