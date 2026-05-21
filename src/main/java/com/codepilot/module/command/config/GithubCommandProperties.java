package com.codepilot.module.command.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.github")
public class GithubCommandProperties {

    private List<String> botMentionAliases = new ArrayList<>(List.of("@x-pilotx", "@X-PilotX"));

    private List<String> allowedCommentAuthorAssociations = new ArrayList<>(List.of(
            "OWNER",
            "MEMBER",
            "COLLABORATOR"
    ));

    private boolean fixEnabled = false;

    private int fixMaxFiles = 3;

    private int fixMaxChangedLines = 120;

    private String fixValidationCommand = "git diff --check";

    private List<String> fixAllowedValidationCommands = new ArrayList<>(List.of("git diff --check"));

    private boolean fixValidationInheritEnvironment = false;

    private int fixValidationTimeoutSeconds = 300;
}
