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

    /** 区间评论的起始行；单行评论为 null。与 endLine 同时存在。 */
    private Integer startLine;

    /** 区间评论的结束行（即锚定行）；单行评论为 null。 */
    private Integer endLine;

    /** 落点所在侧 RIGHT/LEFT，由 DiffLineMapper 判定后持久化。 */
    private String lineSide;

    private String issueType;

    private String issueTypeZh;

    private String severity;

    private String title;

    private String description;

    private String suggestion;

    private String source;

    private String ruleReference;

    private Integer finalScore;

    private String publishDecision;

    private String suppressionReason;

    private String commentChannel;

    private LocalDateTime createdAt;
}
