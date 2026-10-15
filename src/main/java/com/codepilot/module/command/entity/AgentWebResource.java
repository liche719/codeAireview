package com.codepilot.module.command.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_web_resource")
public class AgentWebResource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentRunId;
    private String urlHash;
    private String url;
    private LocalDateTime createdAt;
}
