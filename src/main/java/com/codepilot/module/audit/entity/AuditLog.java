package com.codepilot.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 审计记录（2026-10-29）：谁、在什么来源、对什么、做了什么、结果如何。 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** TRIGGER_REVIEW / APPROVE_FIX / UPDATE_CONFIG / ... */
    private String action;

    /** 操作者（API Key 指纹 / 评论作者 / system） */
    private String actor;

    private String sourceIp;

    /** 作用对象，例如 owner/repo#123 */
    private String target;

    /** SUCCESS / REJECTED / FAILED */
    private String result;

    private String detail;

    private LocalDateTime createdAt;
}
