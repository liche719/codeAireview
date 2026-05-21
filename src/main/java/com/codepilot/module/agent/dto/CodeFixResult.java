package com.codepilot.module.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeFixResult {

    private String summary;

    private String patch;

    private String commitMessage;

    public static CodeFixResult empty() {
        CodeFixResult result = new CodeFixResult();
        result.setSummary("未生成补丁。");
        return result;
    }
}
