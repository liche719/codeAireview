package com.codepilot.module.command.git;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class GitPatchExecutionRequest {

    private String cloneUrl;

    private String branch;

    private String patch;

    private String token;

    private String commitMessage;

    private String validationCommand;

    private List<String> allowedValidationCommands = new ArrayList<>();

    private boolean inheritValidationEnvironment;

    private int validationTimeoutSeconds;

    private boolean dryRun;
}
