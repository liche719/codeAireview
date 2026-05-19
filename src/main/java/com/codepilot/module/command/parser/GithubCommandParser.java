package com.codepilot.module.command.parser;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommand;
import com.codepilot.module.command.dto.GithubCommandType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class GithubCommandParser {

    private static final String LEGACY_REVIEW_COMMAND = "/review";

    private final GithubCommandProperties properties;

    public GithubCommand parse(String body) {
        if (!StringUtils.hasText(body)) {
            return GithubCommand.ignored();
        }

        String trimmed = body.trim();
        if (LEGACY_REVIEW_COMMAND.equals(trimmed)) {
            return new GithubCommand(GithubCommandType.REVIEW, trimmed, false, false);
        }

        String mention = findMention(trimmed);
        if (!StringUtils.hasText(mention)) {
            return GithubCommand.ignored();
        }

        String commandText = removeMention(trimmed, mention).trim();
        String normalized = commandText.toLowerCase(Locale.ROOT);
        boolean dryRun = normalized.contains("dry-run")
                || normalized.contains("dry run")
                || normalized.contains("preview")
                || normalized.contains("预览");

        if (containsAny(normalized, "help", "帮助", "怎么用")) {
            return new GithubCommand(GithubCommandType.HELP, commandText, true, dryRun);
        }
        if (containsAny(normalized, "fix", "解决", "修复", "改一下", "处理一下")) {
            return new GithubCommand(GithubCommandType.FIX, commandText, true, dryRun);
        }
        if (containsAny(normalized, "review", "审查", "检查", "看一下")) {
            return new GithubCommand(GithubCommandType.REVIEW, commandText, true, dryRun);
        }

        return new GithubCommand(GithubCommandType.UNKNOWN, commandText, true, dryRun);
    }

    private String findMention(String body) {
        List<String> aliases = properties.getBotMentionAliases();
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        String normalizedBody = body.toLowerCase(Locale.ROOT);
        return aliases.stream()
                .filter(StringUtils::hasText)
                .filter(alias -> normalizedBody.contains(alias.trim().toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
    }

    private String removeMention(String body, String mention) {
        return body.replaceFirst("(?i)" + java.util.regex.Pattern.quote(mention), "");
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
