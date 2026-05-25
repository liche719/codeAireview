package com.codepilot.module.tool.impl;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.tool.context.DiffToolUtils;
import com.codepilot.module.tool.dto.ToolCheckRequest;
import com.codepilot.module.tool.dto.ToolCheckResult;
import com.codepilot.module.tool.rule.DeterministicReviewRule;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "codepilot.tools", name = {"enabled", "secret-scan-enabled"}, havingValue = "true", matchIfMissing = true)
public class SecretScanTool implements DeterministicReviewRule {

    private static final List<String> SENSITIVE_KEYWORDS = List.of(
            "password",
            "passwd",
            "secret",
            "token",
            "accesskey",
            "apikey",
            "privatekey",
            "jdbcurl"
    );

    private static final Pattern QUOTED_LITERAL = Pattern.compile("[:=]\\s*([\"'])([^\"']+)\\1");

    private static final Pattern ENVIRONMENT_LOOKUP = Pattern.compile(
            "(?i)\\b(?:System\\.getenv|getenv|process\\.env|env\\(|environment\\.getProperty)\\b"
    );

    private static final Pattern GITHUB_TOKEN = Pattern.compile("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b");

    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b");

    private static final Pattern JWT_TOKEN = Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b");

    private static final Pattern PRIVATE_KEY_HEADER = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");

    @Override
    public String id() {
        return "SECRET_SCAN_RULE";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<ToolCheckResult> check(ToolCheckRequest request) {
        return scanSecrets(
                request == null ? null : request.getFilePath(),
                request == null ? null : request.getPatch()
        );
    }

    @Tool("Detect hardcoded secrets in added diff lines, including tokens, passwords, API keys, private keys, and JDBC URLs.")
    public List<ToolCheckResult> scanSecrets(
            @P("Current review file path") String filePath,
            @P("Current GitHub Pull Request diff patch") String patch
    ) {
        long startTime = System.currentTimeMillis();
        try {
            if (!StringUtils.hasText(patch)) {
                return List.of();
            }

            List<ToolCheckResult> results = new ArrayList<>();
            Set<String> reportedLines = new LinkedHashSet<>();
            for (DiffToolUtils.AddedLine addedLine : DiffToolUtils.addedLineEntries(patch)) {
                String line = addedLine.text();
                Optional<SecretFinding> finding = secretFinding(line);
                String fingerprint = normalizeKeywordText(line).trim();
                if (finding.isPresent() && reportedLines.add(fingerprint)) {
                    results.add(ToolCheckResult.atLine(
                            addedLine.newLineNumber(),
                            "SECURITY",
                            finding.get().severity(),
                            "Added code appears to contain a hardcoded secret",
                            "The added line matches a high-confidence secret pattern (" + finding.get().reason()
                                    + "), which can leak credentials or hardcode sensitive access.",
                            "Move the value to environment variables, a secret manager, or a configuration service, and rotate any credential that was committed."
                    ));
                }
            }

            log.info("SecretScanTool executed, filePath={}, hit={}, issueCount={}, costTimeMs={}",
                    filePath, !results.isEmpty(), results.size(), System.currentTimeMillis() - startTime);
            return results;
        } catch (Exception exception) {
            log.warn("SecretScanTool failed, filePath={}, message={}",
                    filePath, SensitiveDataSanitizer.redact(exception.getMessage()));
            return List.of();
        }
    }

    private Optional<SecretFinding> secretFinding(String line) {
        if (!StringUtils.hasText(line)) {
            return Optional.empty();
        }
        if (PRIVATE_KEY_HEADER.matcher(line).find()) {
            return Optional.of(new SecretFinding("HIGH", "private key header"));
        }
        if (GITHUB_TOKEN.matcher(line).find()) {
            return Optional.of(new SecretFinding("HIGH", "GitHub token"));
        }
        if (AWS_ACCESS_KEY.matcher(line).find()) {
            return Optional.of(new SecretFinding("HIGH", "AWS access key"));
        }
        if (JWT_TOKEN.matcher(line).find()) {
            return Optional.of(new SecretFinding("HIGH", "JWT token"));
        }
        if (hasSensitiveLiteralAssignment(line)) {
            return Optional.of(new SecretFinding("HIGH", "literal secret assignment"));
        }
        if (containsSensitiveKeyword(normalizeKeywordText(line)) && likelyHardcodedValue(line)) {
            return Optional.of(new SecretFinding("MEDIUM", "sensitive keyword with literal value"));
        }
        return Optional.empty();
    }

    private boolean likelyHardcodedValue(String line) {
        return line.contains("=")
                && (line.contains("\"") || line.contains("'"))
                && !ENVIRONMENT_LOOKUP.matcher(line).find();
    }

    private boolean hasSensitiveLiteralAssignment(String line) {
        if (!StringUtils.hasText(line) || ENVIRONMENT_LOOKUP.matcher(line).find()) {
            return false;
        }
        int assignmentIndex = assignmentIndex(line);
        if (assignmentIndex < 0) {
            return false;
        }
        String leftSide = normalizeKeywordText(line.substring(0, assignmentIndex));
        if (!containsSensitiveKeyword(leftSide)) {
            return false;
        }
        java.util.regex.Matcher matcher = QUOTED_LITERAL.matcher(line.substring(assignmentIndex));
        if (!matcher.find()) {
            return false;
        }
        String value = matcher.group(2).trim();
        return StringUtils.hasText(value)
                && !value.startsWith("${")
                && !value.startsWith("%{")
                && !value.startsWith("{{")
                && !value.startsWith("<");
    }

    private int assignmentIndex(String line) {
        int equalsIndex = line.indexOf('=');
        int colonIndex = line.indexOf(':');
        if (equalsIndex < 0) {
            return colonIndex;
        }
        if (colonIndex < 0) {
            return equalsIndex;
        }
        return Math.min(equalsIndex, colonIndex);
    }

    private String normalizeKeywordText(String line) {
        return line == null ? "" : line.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private boolean containsSensitiveKeyword(String line) {
        return SENSITIVE_KEYWORDS.stream().anyMatch(line::contains);
    }

    private record SecretFinding(String severity, String reason) {
    }
}
