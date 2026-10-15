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

    /**
     * {@link com.codepilot.common.enums.ReviewFileSkipCategory} 的名字。
     * skipReason 给人读，本字段用于按原因分组统计（摘要的审查覆盖区块）。
     */
    private String skipCategory;

    private LocalDateTime createdAt;
}
