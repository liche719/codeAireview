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

    private String commandText;

    private String commentUserLogin;

    private Boolean dryRun;

    private String generatedPatch;

    private String commitSha;

    private String responseBody;

    private Long sourceTaskId;

    private Long approvalCommentId;

    private String approvedBy;

    private String commentAuthorAssociation;

    private String patchPayload;

    private String patchSha256;

    private String allowedPathsJson;

    private String targetBranch;

    private Integer createdPrNumber;

    private String createdPrUrl;

    private String executionStage;

    private String executionToken;

    private LocalDateTime leaseUntil;

    private Integer attemptCount;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
