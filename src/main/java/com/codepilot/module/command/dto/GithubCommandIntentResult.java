package com.codepilot.module.command.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GithubCommandIntentResult {

    private String type;

    private Boolean dryRun;

    private String reason;
}
