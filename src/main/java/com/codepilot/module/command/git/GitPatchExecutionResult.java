package com.codepilot.module.command.git;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitPatchExecutionResult {

    private boolean success;

    private String commitSha;

    private String message;

    private String detail;

    private boolean retryable;

    public static GitPatchExecutionResult success(String commitSha, String message, String detail) {
        return new GitPatchExecutionResult(true, commitSha, message, detail, false);
    }

    public static GitPatchExecutionResult failure(String message, String detail) {
        return new GitPatchExecutionResult(false, null, message, detail, false);
    }

    public static GitPatchExecutionResult retryableFailure(String message, String detail) {
        return new GitPatchExecutionResult(false, null, message, detail, true);
    }
}
