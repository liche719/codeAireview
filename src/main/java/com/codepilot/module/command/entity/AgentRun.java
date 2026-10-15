package com.codepilot.module.command.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_run")
public class AgentRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long commandTaskId;
    private String status;
    private Integer modelTurnCount;
    private Integer toolCallCount;
    private Integer continuationCount;
    private String waitingWorkflowType;
    private Long waitingWorkflowTaskId;
    private String continuationContext;
    private LocalDateTime deadlineAt;
    private String lastResponse;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
