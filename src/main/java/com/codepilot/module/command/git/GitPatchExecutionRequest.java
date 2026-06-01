package com.codepilot.module.command.git;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
public class GitPatchExecutionRequest {

    private String cloneUrl;

    private String branch;

    private String patch;

    private Set<String> allowedPaths = new LinkedHashSet<>();

    private String token;

    private String commitMessage;

    private String validationCommand;

    private List<String> allowedValidationCommands = new ArrayList<>();

    private boolean allowBuildValidationCommands;

    private boolean inheritValidationEnvironment;

    private int validationTimeoutSeconds;

    private ValidationExecutionMode validationExecutionMode = ValidationExecutionMode.LOCAL;

    private String validationDockerImage;

    private String validationDockerNetwork = "none";

    private boolean dryRun;
}
