package com.codepilot.module.command.approval;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Optional;

@Component
public class FixApprovalCommandParser {

    public enum Action { PUBLISH, MERGE }

    public record ParsedApproval(Action action, long sourceTaskId) {}

    private static final Pattern PUBLISH_PATTERN = Pattern.compile(
            "^(?:批准发布修复|approve\\s+fix\\s+preview)\\s+#?(\\d+)[。.!！]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern MERGE_PATTERN = Pattern.compile(
            "^(?:批准合并修复|approve\\s+merge\\s+fix)\\s+#?(\\d+)[。.!！]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public boolean isPublishApproval(String commandText, Long previewTaskId) {
        return matches(PUBLISH_PATTERN, commandText, previewTaskId);
    }

    public boolean isMergeApproval(String commandText, Long publishTaskId) {
        return matches(MERGE_PATTERN, commandText, publishTaskId);
    }

    public Optional<ParsedApproval> parse(String commandText) {
        Optional<Long> publish = extract(PUBLISH_PATTERN, commandText);
        if (publish.isPresent()) {
            return Optional.of(new ParsedApproval(Action.PUBLISH, publish.get()));
        }
        return extract(MERGE_PATTERN, commandText)
                .map(taskId -> new ParsedApproval(Action.MERGE, taskId));
    }

    private boolean matches(Pattern pattern, String commandText, Long expectedTaskId) {
        if (!StringUtils.hasText(commandText) || expectedTaskId == null || expectedTaskId <= 0) {
            return false;
        }
        Matcher matcher = pattern.matcher(commandText.trim());
        if (!matcher.matches()) {
            return false;
        }
        try {
            return expectedTaskId.equals(Long.valueOf(matcher.group(1)));
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private Optional<Long> extract(Pattern pattern, String commandText) {
        if (!StringUtils.hasText(commandText)) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(commandText.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            long taskId = Long.parseLong(matcher.group(1));
            return taskId > 0 ? Optional.of(taskId) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
