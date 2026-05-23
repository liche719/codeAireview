package com.codepilot.module.agent.review;

import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.tool.dto.ToolCheckResult;
import com.codepilot.module.tool.impl.SecretScanTool;
import com.codepilot.module.tool.impl.SqlRiskTool;
import com.codepilot.module.tool.impl.TestSuggestionTool;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DeterministicReviewToolRunner {

    private final ObjectProvider<SqlRiskTool> sqlRiskToolProvider;

    private final ObjectProvider<SecretScanTool> secretScanToolProvider;

    private final ObjectProvider<TestSuggestionTool> testSuggestionToolProvider;

    private final ReviewIssueDeduplicator reviewIssueDeduplicator;

    public AiReviewResult run(String filePath, String patch, String allChangedFilesText) {
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
        result.setIssues(reviewIssueDeduplicator.dedupe(issues));
        if (!result.getIssues().isEmpty()) {
            result.setSummary("\u786e\u5b9a\u6027\u5de5\u5177\u53d1\u73b0 "
                    + result.getIssues().size()
                    + " \u4e2a\u6f5c\u5728\u95ee\u9898\u3002");
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
            issue.setLineNumber(toolResult.getLineNumber());
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

    private String displayIssueType(String issueType) {
        String normalized = StringUtils.hasText(issueType) ? issueType.trim().toUpperCase() : "";
        return switch (normalized) {
            case "SQL_RISK" -> "SQL \u98ce\u9669";
            case "SECURITY" -> "\u5b89\u5168\u98ce\u9669";
            case "TEST_MISSING" -> "\u6d4b\u8bd5\u7f3a\u5931";
            case "PERFORMANCE" -> "\u6027\u80fd\u98ce\u9669";
            case "STYLE" -> "\u4ee3\u7801\u98ce\u683c";
            case "EXCEPTION_HANDLING" -> "\u5f02\u5e38\u5904\u7406";
            case "LOGGING" -> "\u65e5\u5fd7\u98ce\u9669";
            case "BUG_RISK" -> "Bug \u98ce\u9669";
            default -> "\u95ee\u9898";
        };
    }
}
