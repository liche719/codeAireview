package com.codepilot.module.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeFixResult {

    private String summary;

    private String patch;

    public static CodeFixResult empty() {
        CodeFixResult result = new CodeFixResult();
        result.setSummary("No patch generated.");
        return result;
    }
}
