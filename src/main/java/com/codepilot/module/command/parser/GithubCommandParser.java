package com.codepilot.module.command.parser;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.dto.GithubCommand;
import com.codepilot.module.command.dto.GithubCommandIntentResult;
import com.codepilot.module.command.dto.GithubCommandType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Slf4j
@Component
public class GithubCommandParser {

    private static final String LEGACY_REVIEW_COMMAND = "/review";

    private static final String OPENAI_COMPATIBLE_PROVIDER = "openai-compatible";

    private final GithubCommandProperties properties;

    private final ObjectProvider<GithubCommandIntentAiAssistant> intentAiAssistantProvider;

    private final GithubCommandIntentResultParser intentResultParser;

    private final LlmProperties llmProperties;

    GithubCommandParser(GithubCommandProperties properties) {
        this(properties, null, null, null);
    }

    @Autowired
    public GithubCommandParser(
            GithubCommandProperties properties,
            ObjectProvider<GithubCommandIntentAiAssistant> intentAiAssistantProvider,
            GithubCommandIntentResultParser intentResultParser,
            LlmProperties llmProperties
    ) {
        this.properties = properties;
        this.intentAiAssistantProvider = intentAiAssistantProvider;
        this.intentResultParser = intentResultParser;
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
        GithubCommand localCommand = classifyLocalCommands(commandText);
        if (localCommand != null) {
            return localCommand;
        }

        if (!isLlmAvailable()) {
            return unavailable(commandText, true);
        }

        return classifyWithAi(trimmed, commandText);
    }

    private GithubCommand classifyWithAi(String body, String commandText) {
        if (intentAiAssistantProvider == null || intentResultParser == null) {
            return unavailable(commandText, true);
        }
        GithubCommandIntentAiAssistant assistant = intentAiAssistantProvider.getIfAvailable();
        if (assistant == null) {
            return unavailable(commandText, true);
        }
        try {
            String response = assistant.classify(body, commandText, String.join(",", safeAliases()));
            GithubCommandIntentResult result = intentResultParser.parse(response);
            if (result == null) {
                return unavailable(commandText, true);
            }
            GithubCommandType type = parseType(result.getType());
            if (type == GithubCommandType.NONE) {
                type = GithubCommandType.UNKNOWN;
            }
            return new GithubCommand(
                    type,
                    commandText,
                    true,
                    Boolean.TRUE.equals(result.getDryRun()) || detectDryRun(commandText)
            );
        } catch (Exception exception) {
            log.warn("GitHub command intent classification failed, return unavailable command, message={}", exception.getMessage());
            return unavailable(commandText, true);
        }
    }

    private GithubCommand classifyLocalCommands(String commandText) {
        String normalized = commandText.toLowerCase(Locale.ROOT);
        boolean dryRun = detectDryRun(normalized);
        if (containsAny(normalized,
                "help",
                "\u5e2e\u52a9",
                "\u600e\u4e48\u7528",
                "\u7528\u6cd5",
                "\u652f\u6301\u4ec0\u4e48")) {
            return new GithubCommand(GithubCommandType.HELP, commandText, true, dryRun);
        }
        return null;
    }

    private GithubCommand unavailable(String commandText, boolean mentionedBot) {
        return new GithubCommand(GithubCommandType.UNAVAILABLE, commandText, mentionedBot, detectDryRun(commandText));
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

    private GithubCommandType parseType(String value) {
        if (!StringUtils.hasText(value)) {
            return GithubCommandType.UNKNOWN;
        }
        try {
            return GithubCommandType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return GithubCommandType.UNKNOWN;
        }
    }

    private boolean detectDryRun(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("dry-run")
                || normalized.contains("dry run")
                || normalized.contains("preview")
                || normalized.contains("\u9884\u89c8");
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

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
