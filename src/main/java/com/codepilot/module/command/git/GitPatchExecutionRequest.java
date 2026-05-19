package com.codepilot.module.command.git;

import lombok.Data;

@Data
public class GitPatchExecutionRequest {

    private String cloneUrl;

    private String branch;

    private String patch;

    private String token;

    private String commitMessage;

    private String validationCommand;

    private boolean dryRun;
}
