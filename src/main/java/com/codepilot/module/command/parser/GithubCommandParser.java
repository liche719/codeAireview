package com.codepilot.module.command.parser;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommand;
import com.codepilot.module.command.dto.GithubCommandType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Component
public class GithubCommandParser {

    private static final String LEGACY_REVIEW_COMMAND = "/review";

    private static final String OPENAI_COMPATIBLE_PROVIDER = "openai-compatible";

    private final GithubCommandProperties properties;

    private final LlmProperties llmProperties;

    public GithubCommandParser(
            GithubCommandProperties properties,
            LlmProperties llmProperties
    ) {
        this.properties = properties;
        this.llmProperties = llmProperties;
    }

    public GithubCommand parse(String body) {
        if (!StringUtils.hasText(body)) {
            return GithubCommand.ignored();
        }

        String trimmed = body.trim();
        if (LEGACY_REVIEW_COMMAND.equals(trimmed)) {
            if (!isLlmAvailable()) {
                return unavailable(trimmed, false);
            }
            return new GithubCommand(GithubCommandType.REVIEW, trimmed, false, false);
        }

        String mention = findMention(trimmed);
        if (!StringUtils.hasText(mention)) {
            return GithubCommand.ignored();
        }

        String commandText = removeMention(trimmed, mention).trim();
        if (!isLlmAvailable()) {
            return unavailable(commandText, true);
        }
        return new GithubCommand(GithubCommandType.CHAT, commandText, true, false);
    }

    private GithubCommand unavailable(String commandText, boolean mentionedBot) {
        return new GithubCommand(GithubCommandType.UNAVAILABLE, commandText, mentionedBot, false);
    }

    private boolean isLlmAvailable() {
        return llmProperties != null
                && llmProperties.isEnabled()
                && StringUtils.hasText(llmProperties.getApiKey())
                && StringUtils.hasText(llmProperties.getBaseUrl())
                && StringUtils.hasText(llmProperties.getModel())
                && OPENAI_COMPATIBLE_PROVIDER.equalsIgnoreCase(StringUtils.trimWhitespace(
                llmProperties.getProvider() == null ? "" : llmProperties.getProvider()));
    }


    private String findMention(String body) {
        List<String> aliases = safeAliases();
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

    private List<String> safeAliases() {
        return properties == null || properties.getBotMentionAliases() == null ? List.of() : properties.getBotMentionAliases();
    }

    private String removeMention(String body, String mention) {
        return body.replaceFirst("(?i)" + java.util.regex.Pattern.quote(mention), "");
    }

}
