package com.codepilot.module.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiReviewIssue {

    private String filePath;

    private Integer lineNumber;

    /**
     * 这个问题跨越的起始行；单行问题为 null。
     *
     * <p>由模型给出，但**不会直接用于发布**——落点必须经 {@code DiffLineMapper.mapRange}
     * 在本地校验（与 lineNumber 同侧、且都在 diff 内、且连续），校验不过就降级为单行评论。</p>
     */
    private Integer startLine;

    /** 跨越的结束行，与 lineNumber 一致；单行问题为 null。 */
    private Integer endLine;

    private String issueType;

    private String issueTypeZh;

    private String severity;

    private String title;

    private String description;

    private String suggestion;

    private String source;

    private String ruleReference;
}
