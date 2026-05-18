package com.codepilot.module.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("review_file")
public class ReviewFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private String filePath;

    private String changeType;

    private String patch;

    private Integer additions;

    private Integer deletions;

    private Boolean skipped;

    private String skipReason;

    private LocalDateTime createdAt;
}
