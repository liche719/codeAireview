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

    private boolean fixEnabled = false;

    private int fixMaxFiles = 3;

    private int fixMaxChangedLines = 120;

    private String fixValidationCommand = "mvn -q -DskipTests compile";
}
