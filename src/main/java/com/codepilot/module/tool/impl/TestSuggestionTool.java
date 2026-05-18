package com.codepilot.module.tool.impl;

import com.codepilot.module.tool.dto.ToolCheckResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "codepilot.tools", name = {"enabled", "test-suggestion-enabled"}, havingValue = "true", matchIfMissing = true)
public class TestSuggestionTool {

    @Tool("根据本次 PR 变更文件判断是否缺少单元测试或集成测试")
    public List<ToolCheckResult> suggestTests(
            @P("当前审查文件路径") String filePath,
            @P("当前审查文件的 GitHub Pull Request diff patch") String patch,
            @P("本次 PR 的所有变更文件路径，每行一个") String allChangedFilesText
    ) {
        long startTime = System.currentTimeMillis();
        try {
            if (!StringUtils.hasText(filePath) || !StringUtils.hasText(patch)) {
                return List.of();
            }
            if (!isCoreBusinessFile(filePath) || hasTestFileChanged(allChangedFilesText)) {
                log.info("TestSuggestionTool executed, filePath={}, hit=false, issueCount=0, costTimeMs={}",
                        filePath, System.currentTimeMillis() - startTime);
                return List.of();
            }

            List<ToolCheckResult> results = List.of(ToolCheckResult.of(
                    "TEST_MISSING",
                    "LOW",
                    "核心业务代码变更缺少测试",
                    "本次 PR 修改了 controller/service/manager/job 等核心业务代码，但未发现测试目录或测试文件变更。",
                    "建议补充对应单元测试或集成测试，覆盖主要分支、异常路径和边界条件。"
            ));
            log.info("TestSuggestionTool executed, filePath={}, hit=true, issueCount={}, costTimeMs={}",
                    filePath, results.size(), System.currentTimeMillis() - startTime);
            return results;
        } catch (Exception exception) {
            log.warn("TestSuggestionTool failed, filePath={}, message={}", filePath, exception.getMessage());
            return List.of();
        }
    }

    private boolean isCoreBusinessFile(String filePath) {
        String normalized = normalize(filePath);
        return normalized.startsWith("src/main/java/")
                && (normalized.contains("/controller/")
                || normalized.contains("/service/")
                || normalized.contains("/manager/")
                || normalized.contains("/job/")
                || normalized.endsWith("controller.java")
                || normalized.endsWith("service.java")
                || normalized.endsWith("manager.java")
                || normalized.endsWith("job.java"));
    }

    private boolean hasTestFileChanged(String allChangedFilesText) {
        if (!StringUtils.hasText(allChangedFilesText)) {
            return false;
        }
        return allChangedFilesText.lines()
                .map(this::normalize)
                .anyMatch(path -> path.contains("src/test/")
                        || path.contains("/test/")
                        || path.endsWith("test.java")
                        || path.endsWith("tests.java")
                        || path.endsWith("spec.java"));
    }

    private String normalize(String path) {
        return path == null ? "" : path.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
