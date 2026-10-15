package com.codepilot.module.git.dto;

import lombok.Data;

@Data
public class GithubMergeResult {

    private Boolean merged;

    private String sha;

    private String message;
}
