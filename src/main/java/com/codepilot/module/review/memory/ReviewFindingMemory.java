package com.codepilot.module.review.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个重要 finding 在一次 PR 内的生命周期记录。
 *
 * <p>它回答的问题是：<b>这次报出来的问题，是不是上一次审查已经提过的？</b></p>
 *
 * <p>属<b>派生数据</b>：即使整表丢失，也只是退化成"重复评论"，不会丢失任何真相。
 * 这一点是刻意的——它让这张表可以被安全地 drop 以回滚 R6。</p>
 */
@Data
@TableName("review_finding_memory")
public class ReviewFindingMemory {

    public static final String STATE_ACTIVE = "ACTIVE";

    public static final String STATE_RESOLVED = "RESOLVED";

    public static final String STATE_DISMISSED = "DISMISSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String repoOwner;

    private String repoName;

    private Integer prNumber;

    /** 不含行号的 finding 指纹；与 codeFingerprint 构成 OR 匹配。 */
    private String findingFingerprint;

    private String codeFingerprint;

    private String issueType;

    private String filePath;

    private String severity;

    private String title;

    /** ACTIVE / RESOLVED / DISMISSED。 */
    private String state;

    private Integer reopenedCount;

    private Long firstTaskId;

    private Long lastTaskId;

    private String firstSeenHeadSha;

    private String lastSeenHeadSha;

    private String resolvedAtHeadSha;

    private String suppressedReason;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;
}
