package com.codepilot.module.command.fix;

import java.util.Set;

public record FixPromptInput(String issuesJson, Set<String> allowedPaths, String limits) {
}
