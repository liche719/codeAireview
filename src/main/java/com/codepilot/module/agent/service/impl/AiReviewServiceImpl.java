package com.codepilot.module.agent.service.impl;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.agent.dto.ReviewRuleContext;
import com.codepilot.module.agent.parser.AiReviewResultParser;
import com.codepilot.module.agent.prompt.ReviewPromptBuilder;
import com.codepilot.module.agent.service.AiReviewService;
import com.codepilot.module.agent.service.CodeReviewAiAssistant;
import com.codepilot.module.agent.service.ReviewRagService;
import com.codepilot.module.audit.entity.LlmCallLog;
import com.codepilot.module.audit.service.LlmCallLogService;
import com.codepilot.module.tool.dto.ToolCheckResult;
import com.codepilot.module.tool.impl.SecretScanTool;
import com.codepilot.module.tool.impl.SqlRiskTool;
import com.codepilot.module.tool.impl.TestSuggestionTool;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReviewServiceImpl implements AiReviewService {

    private static final int RESPONSE_SUMMARY_LIMIT = 1000;

    private final LlmProperties llmProperties;

    private final ObjectProvider<CodeReviewAiAssistant> codeReviewAiAssistantProvider;

    private final AiReviewResultParser aiReviewResultParser;

    private final ReviewRagService reviewRagService;

    private final ReviewPromptBuilder reviewPromptBuilder;

    private final LlmCallLogService llmCallLogService;

    private final ObjectProvider<SqlRiskTool> sqlRiskToolProvider;

    private final ObjectProvider<SecretScanTool> secretScanToolProvider;

    private final ObjectProvider<TestSuggestionTool> testSuggestionToolProvider;

    @Override
    public AiReviewResult reviewFile(Long taskId, String filePath, String patch, List<String> allChangedFiles) {
        if (!StringUtils.hasText(patch)) {
            log.info("Skip ai review because patch is empty, filePath={}", filePath);
            return AiReviewResult.empty();
        }

        String allChangedFilesText = buildAllChangedFilesText(allChangedFiles);
        AiReviewResult deterministicResult = runDeterministicTools(filePath, patch, allChangedFilesText);
        if (!llmProperties.isEnabled()) {
            log.info("Skip llm review because llm is disabled, filePath={}, deterministicIssueCount={}",
                    filePath, issueCount(deterministicResult));
            return deterministicResult;
        }
        if (!StringUtils.hasText(llmProperties.getApiKey())) {
            log.info("Skip llm review because llm api key is missing, filePath={}, deterministicIssueCount={}",
                    filePath, issueCount(deterministicResult));
            return deterministicResult;
        }

        CodeReviewAiAssistant codeReviewAiAssistant = codeReviewAiAssistantProvider.getIfAvailable();
        if (codeReviewAiAssistant == null) {
            log.warn("Skip llm review because CodeReviewAiAssistant bean is unavailable, filePath={}, deterministicIssueCount={}",
                    filePath, issueCount(deterministicResult));
            return deterministicResult;
        }

        List<ReviewRuleContext> rules = reviewRagService.retrieveRelevantRules(filePath, patch);
        String rulesContext = reviewPromptBuilder.buildRulesContext(rules);
        log.info("AI review RAG context prepared, filePath={}, ruleCount={}, contextChars={}",
                filePath, rules.size(), rulesContext == null ? 0 : rulesContext.length());

        String responseText = null;
        String errorMessage = null;
        boolean success = false;
        long startTime = System.currentTimeMillis();

        try {
            Result<String> result = codeReviewAiAssistant.review(filePath, patch, rulesContext, allChangedFilesText);
            responseText = result == null ? null : result.content();
            if (!StringUtils.hasText(responseText)) {
                errorMessage = "empty model response";
                log.warn("LangChain4j review returned empty content, filePath={}", filePath);
                throw new IllegalStateException(errorMessage);
            }

            AiReviewResult parsedResult = aiReviewResultParser.parse(responseText);
            success = true;
            return mergeResults(parsedResult, deterministicResult);
        } catch (Exception exception) {
            errorMessage = SensitiveDataSanitizer.redact(exception.getMessage());
            log.warn("LangChain4j ai review failed, filePath={}, errorType={}, message={}",
                    filePath, exception.getClass().getSimpleName(), errorMessage);
            throw exception;
        } finally {
            long costTimeMs = System.currentTimeMillis() - startTime;
            saveCallLog(taskId, filePath, patch, rules.size(), costTimeMs, success, errorMessage, responseText);
        }
    }

    private void saveCallLog(
            Long taskId,
            String filePath,
            String patch,
            int ruleCount,
            long costTimeMs,
            boolean success,
            String errorMessage,
            String responseText
    ) {
        try {
            LlmCallLog logRecord = new LlmCallLog();
            logRecord.setTaskId(taskId);
            logRecord.setModelName(llmProperties.getModel());
            logRecord.setCostTimeMs(costTimeMs);
            logRecord.setRequestSummary(buildRequestSummary(filePath, patch, ruleCount));
            logRecord.setResponseSummary(SensitiveDataSanitizer.redactAndTruncate(responseText, RESPONSE_SUMMARY_LIMIT));
            logRecord.setSuccess(success);
            logRecord.setErrorMessage(SensitiveDataSanitizer.redact(errorMessage));
            logRecord.setCreatedAt(LocalDateTime.now());
            llmCallLogService.save(logRecord);
        } catch (Exception exception) {
            log.warn("Failed to save llm call log, taskId={}, filePath={}, errorType={}, message={}",
                    taskId, filePath, exception.getClass().getSimpleName(), SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }

    private String buildRequestSummary(String filePath, String patch, int ruleCount) {
        int patchLength = patch == null ? 0 : patch.length();
        return "filePath=" + filePath + ", patchLength=" + patchLength + ", ragRuleCount=" + ruleCount;
    }

    private String buildAllChangedFilesText(List<String> allChangedFiles) {
        if (allChangedFiles == null || allChangedFiles.isEmpty()) {
            return "No changed file list was provided.";
        }
        return String.join("\n", allChangedFiles);
    }

    private AiReviewResult runDeterministicTools(String filePath, String patch, String allChangedFilesText) {
        List<AiReviewIssue> issues = new ArrayList<>();
        SqlRiskTool sqlRiskTool = sqlRiskToolProvider == null ? null : sqlRiskToolProvider.getIfAvailable();
        if (sqlRiskTool != null) {
            issues.addAll(mapToolResults(filePath, sqlRiskTool.checkSqlRisk(filePath, patch)));
        }

        SecretScanTool secretScanTool = secretScanToolProvider == null ? null : secretScanToolProvider.getIfAvailable();
        if (secretScanTool != null) {
            issues.addAll(mapToolResults(filePath, secretScanTool.scanSecrets(filePath, patch)));
        }

        TestSuggestionTool testSuggestionTool = testSuggestionToolProvider == null ? null : testSuggestionToolProvider.getIfAvailable();
        if (testSuggestionTool != null) {
            issues.addAll(mapToolResults(filePath, testSuggestionTool.suggestTests(filePath, patch, allChangedFilesText)));
        }

        AiReviewResult result = new AiReviewResult();
        result.setIssues(dedupeIssues(issues));
        if (!result.getIssues().isEmpty()) {
            result.setSummary("确定性工具发现 " + result.getIssues().size() + " 个潜在问题。");
        }
        return result;
    }

    private List<AiReviewIssue> mapToolResults(String filePath, List<ToolCheckResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return List.of();
        }
        List<AiReviewIssue> issues = new ArrayList<>();
        for (ToolCheckResult toolResult : toolResults) {
            if (toolResult == null) {
                continue;
            }
            AiReviewIssue issue = new AiReviewIssue();
            issue.setFilePath(filePath);
            issue.setIssueType(toolResult.getIssueType());
            issue.setIssueTypeZh(displayIssueType(toolResult.getIssueType()));
            issue.setSeverity(toolResult.getSeverity());
            issue.setTitle(toolResult.getTitle());
            issue.setDescription(toolResult.getDescription());
            issue.setSuggestion(toolResult.getSuggestion());
            issue.setSource("TOOL");
            issue.setRuleReference(null);
            issues.add(issue);
        }
        return issues;
    }

    private AiReviewResult mergeResults(AiReviewResult llmResult, AiReviewResult deterministicResult) {
        List<AiReviewIssue> mergedIssues = new ArrayList<>();
        if (llmResult != null && llmResult.getIssues() != null) {
            mergedIssues.addAll(llmResult.getIssues());
        }
        if (deterministicResult != null && deterministicResult.getIssues() != null) {
            mergedIssues.addAll(deterministicResult.getIssues());
        }
        AiReviewResult mergedResult = llmResult == null ? AiReviewResult.empty() : llmResult;
        mergedResult.setIssues(dedupeIssues(mergedIssues));
        if (!StringUtils.hasText(mergedResult.getSummary())
                && deterministicResult != null
                && StringUtils.hasText(deterministicResult.getSummary())) {
            mergedResult.setSummary(deterministicResult.getSummary());
        }
        return mergedResult;
    }

    private List<AiReviewIssue> dedupeIssues(List<AiReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        List<AiReviewIssue> deduped = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AiReviewIssue issue : issues) {
            if (issue == null) {
                continue;
            }
            String key = nullToDash(issue.getFilePath())
                    + ":"
                    + nullToDash(issue.getIssueType())
                    + ":"
                    + nullToDash(issue.getSeverity())
                    + ":"
                    + nullToDash(issue.getTitle())
                    + ":"
                    + nullToDash(issue.getDescription());
            if (seen.add(key)) {
                deduped.add(issue);
            }
        }
        return deduped;
    }

    private int issueCount(AiReviewResult result) {
        return result == null || result.getIssues() == null ? 0 : result.getIssues().size();
    }

    private String displayIssueType(String issueType) {
        String normalized = StringUtils.hasText(issueType) ? issueType.trim().toUpperCase() : "";
        return switch (normalized) {
            case "SQL_RISK" -> "SQL 风险";
            case "SECURITY" -> "安全风险";
            case "TEST_MISSING" -> "测试缺失";
            case "PERFORMANCE" -> "性能风险";
            case "STYLE" -> "代码风格";
            case "EXCEPTION_HANDLING" -> "异常处理";
            case "LOGGING" -> "日志风险";
            case "BUG_RISK" -> "Bug 风险";
            default -> "问题";
        };
    }

    private String nullToDash(String content) {
        return StringUtils.hasText(content) ? content : "N/A";
    }

    private String truncate(String content, int maxLength) {
        if (!StringUtils.hasText(content) || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength);
    }
}
