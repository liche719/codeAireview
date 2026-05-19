package com.codepilot.module.command.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GithubCommandHandleResult {

    private Long id;

    private String action;

    private String reason;

    public static GithubCommandHandleResult processed(Long id, String action) {
        return new GithubCommandHandleResult(id, action, null);
    }

    public static GithubCommandHandleResult ignored(String reason, String action) {
        return new GithubCommandHandleResult(null, action, reason);
    }
}
