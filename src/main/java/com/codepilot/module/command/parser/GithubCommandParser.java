package com.codepilot.module.command.parser;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.common.util.PromptInputSanitizer;
import com.codepilot.common.util.SensitiveDataSanitizer;
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
        GithubCommand highConfidenceCommand = parseHighConfidenceMentionCommand(commandText);
        if (highConfidenceCommand != null) {
            if (!isLlmAvailable()) {
                return unavailable(commandText, true);
            }
            return highConfidenceCommand;
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
            String response = assistant.classify(
                    promptSafe(body),
                    promptSafe(commandText),
                    String.join(",", safeAliases())
            );
            GithubCommandIntentResult result = intentResultParser.parse(response);
            if (result == null) {
                return unavailable(commandText, true);
            }
            GithubCommandType type = parseType(result.getType());
            return new GithubCommand(
                    type,
                    commandText,
                    true,
                    Boolean.TRUE.equals(result.getDryRun())
            );
        } catch (Exception exception) {
            log.warn("GitHub command intent classification failed, return unavailable command, message={}",
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            return unavailable(commandText, true);
        }
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

    private GithubCommandType parseType(String value) {
        if (!StringUtils.hasText(value)) {
            return GithubCommandType.UNKNOWN;
        }
        if ("HELP".equalsIgnoreCase(value.trim())) {
            return GithubCommandType.CHAT;
        }
        try {
            GithubCommandType type = GithubCommandType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            return type;
        } catch (IllegalArgumentException exception) {
            return GithubCommandType.UNKNOWN;
        }
    }

    private GithubCommand parseHighConfidenceMentionCommand(String commandText) {
        String normalized = normalizeCommandText(commandText);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (isReviewSummaryChat(normalized)) {
            return new GithubCommand(GithubCommandType.CHAT, commandText, true, false);
        }
        if (isExplicitReviewCommand(normalized)) {
            return new GithubCommand(GithubCommandType.REVIEW, commandText, true, false);
        }
        return null;
    }

    private boolean isReviewSummaryChat(String normalizedCommandText) {
        return containsAny(normalizedCommandText,
                "summarize",
                "summary",
                "explain",
                "list",
                "tell me",
                "confirm",
                "\u603b\u7ed3",
                "\u89e3\u91ca",
                "\u8bf4\u660e",
                "\u5217\u51fa",
                "\u8bc1\u636e")
                && containsAny(normalizedCommandText,
                "review",
                "finding",
                "findings",
                "result",
                "evidence",
                "comment",
                "issue",
                "pr",
                "pull request",
                "\u95ee\u9898",
                "\u5ba1\u67e5",
                "\u8bc4\u8bba",
                "\u8bc1\u636e");
    }

    private boolean isExplicitReviewCommand(String normalizedCommandText) {
        return normalizedCommandText.equals("review")
                || normalizedCommandText.equals("please review")
                || normalizedCommandText.equals("review this pr")
                || normalizedCommandText.equals("review this pull request")
                || normalizedCommandText.equals("please review this pr")
                || normalizedCommandText.equals("please review this pull request")
                || normalizedCommandText.equals("run review")
                || normalizedCommandText.equals("run a review")
                || normalizedCommandText.equals("rerun review")
                || normalizedCommandText.equals("re-run review");
    }

    private boolean containsAny(String text, String... needles) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String needle : needles) {
            if (StringUtils.hasText(needle) && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeCommandText(String commandText) {
        if (!StringUtils.hasText(commandText)) {
            return "";
        }
        return commandText.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[`*_]", " ")
                .replaceAll("\\s+", " ");
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

    private String promptSafe(String content) {
        return PromptInputSanitizer.escapeUntrustedBlockDelimiters(content);
    }
}
