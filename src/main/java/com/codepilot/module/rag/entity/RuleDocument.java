package com.codepilot.module.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rule_document")
public class RuleDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String type;

    private String source;

    private String content;

    private Boolean enabled;

    private LocalDateTime createdAt;
}
