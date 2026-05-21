package com.codepilot.module.tool.impl;

import com.codepilot.module.tool.context.DiffToolUtils;
import com.codepilot.module.tool.dto.ToolCheckResult;
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
import java.util.Set;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "codepilot.tools", name = {"enabled", "secret-scan-enabled"}, havingValue = "true", matchIfMissing = true)
public class SecretScanTool {

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

    @Tool("检测代码 Diff 中是否包含硬编码敏感信息，例如 password、token、secret、accessKey、apiKey、privateKey")
    public List<ToolCheckResult> scanSecrets(
            @P("当前审查文件路径") String filePath,
            @P("当前审查文件的 GitHub Pull Request diff patch") String patch
    ) {
        long startTime = System.currentTimeMillis();
        try {
            if (!StringUtils.hasText(patch)) {
                return List.of();
            }

            List<ToolCheckResult> results = new ArrayList<>();
            Set<String> reportedLines = new LinkedHashSet<>();
            for (String line : DiffToolUtils.addedLines(patch)) {
                String normalized = line.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                if (containsSensitiveKeyword(normalized) && reportedLines.add(normalized.trim())) {
                    results.add(ToolCheckResult.of(
                            "SECURITY",
                            likelyHardcodedValue(line) ? "HIGH" : "MEDIUM",
                            "新增代码疑似包含敏感信息",
                            "新增行中出现 password、token、secret、apiKey 等敏感字段，存在硬编码或泄露风险。",
                            "请使用环境变量、配置中心、密钥管理服务，并避免在日志或代码中明文保存敏感信息。"
                    ));
                }
            }

            log.info("SecretScanTool executed, filePath={}, hit={}, issueCount={}, costTimeMs={}",
                    filePath, !results.isEmpty(), results.size(), System.currentTimeMillis() - startTime);
            return results;
        } catch (Exception exception) {
            log.warn("SecretScanTool failed, filePath={}, message={}", filePath, exception.getMessage());
            return List.of();
        }
    }

    private boolean containsSensitiveKeyword(String line) {
        return SENSITIVE_KEYWORDS.stream().anyMatch(line::contains);
    }

    private boolean likelyHardcodedValue(String line) {
        return line.contains("=") && (line.contains("\"") || line.contains("'"));
    }
}
