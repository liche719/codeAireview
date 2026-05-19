package com.codepilot.module.command.git;

public interface GitPatchExecutor {

    GitPatchExecutionResult execute(GitPatchExecutionRequest request);
}
