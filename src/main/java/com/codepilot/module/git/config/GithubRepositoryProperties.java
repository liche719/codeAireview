package com.codepilot.module.git.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.github")
public class GithubRepositoryProperties {

    private List<String> allowedRepositories = new ArrayList<>();
}
