package com.codepilot.module.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("review_task")
public class ReviewTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String repoOwner;

    private String repoName;

    private Integer prNumber;

    private String prUrl;

    private String title;

    private String headSha;

    private String reviewCommentMode;

    private String status;

    private Integer totalFiles;

    private Integer totalIssues;

    private String riskLevel;

    private String errorMessage;

    private String executionToken;

    private LocalDateTime leaseUntil;

    private Integer attemptCount;

    private String publishStatus;

    private Integer publishAttemptCount;

    private String publishError;

    private LocalDateTime publishNextAttemptAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 门禁判定：SUCCESS / NEUTRAL / FAILURE（R17）；为空表示门禁未启用。 */
    private String gateStatus;

    /** 门禁判定依据，用于审计"为什么这次判 failure"。 */
    private String gateReason;
}
