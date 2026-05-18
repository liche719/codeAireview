package com.codepilot.module.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("review_issue")
public class ReviewIssue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private String filePath;

    private Integer lineNumber;

    private String issueType;

    private String severity;

    private String title;

    private String description;

    private String suggestion;

    private String source;

    private String ruleReference;

    private LocalDateTime createdAt;
}

