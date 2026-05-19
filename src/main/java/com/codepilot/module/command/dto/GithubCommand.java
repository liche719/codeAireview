package com.codepilot.module.command.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GithubCommand {

    private GithubCommandType type;

    private String text;

    private boolean mentionedBot;

    private boolean dryRun;

    public static GithubCommand ignored() {
        return new GithubCommand(GithubCommandType.NONE, null, false, false);
    }

    public boolean shouldIgnore() {
        return type == null || type == GithubCommandType.NONE;
    }
}
