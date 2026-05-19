package com.codepilot.module.command.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pr_command_task_log")
public class PrCommandTaskLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long commandTaskId;

    private String step;

    private Boolean success;

    private String message;

    private String detail;

    private LocalDateTime createdAt;
}
