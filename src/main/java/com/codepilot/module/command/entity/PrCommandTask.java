package com.codepilot.module.command.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pr_command_task")
public class PrCommandTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String commandType;

    private String status;

    private String repoOwner;

    private String repoName;

    private Integer prNumber;

    private String prUrl;

    private String title;

    private String headSha;

    private Long commentId;

    private String commentBody;

    private String commentUserLogin;

    private Boolean dryRun;

    private String generatedPatch;

    private String commitSha;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
