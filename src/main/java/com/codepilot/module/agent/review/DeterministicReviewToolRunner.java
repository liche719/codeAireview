package com.codepilot.module.agent.review;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.agent.dto.AiReviewIssue;
import com.codepilot.module.agent.dto.AiReviewResult;
import com.codepilot.module.tool.dto.ToolCheckRequest;
import com.codepilot.module.tool.dto.ToolCheckResult;
import com.codepilot.module.tool.rule.DeterministicReviewRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeterministicReviewToolRunner {

    private final List<DeterministicReviewRule> reviewRules;

    private final ReviewIssueDeduplicator reviewIssueDeduplicator;

    public AiReviewResult run(
            String filePath,
            String patch,
            List<String> allChangedFiles,
            String reviewContextText
    ) {
        ToolCheckRequest request = ToolCheckRequest.of(filePath, patch, allChangedFiles, reviewContextText);
        List<AiReviewIssue> issues = new ArrayList<>();
        for (DeterministicReviewRule rule : orderedRules()) {
            if (rule == null || !rule.supports(request)) {
                continue;
            }
            issues.addAll(mapToolResults(filePath, rule, runRule(rule, request)));
        }

        AiReviewResult result = new AiReviewResult();
        result.setIssues(reviewIssueDeduplicator.dedupe(issues));
        if (!result.getIssues().isEmpty()) {
            result.setSummary("\u786e\u5b9a\u6027\u89c4\u5219\u53d1\u73b0 "
                    + result.getIssues().size()
                    + " \u4e2a\u6f5c\u5728\u95ee\u9898\u3002");
        }
        return result;
    }

    public AiReviewResult run(String filePath, String patch, String allChangedFilesText) {
        return run(filePath, patch, splitLines(allChangedFilesText), allChangedFilesText);
    }

    private List<DeterministicReviewRule> orderedRules() {
        if (reviewRules == null || reviewRules.isEmpty()) {
            return List.of();
        }
        return reviewRules.stream()
                .filter(rule -> rule != null && StringUtils.hasText(rule.id()))
                .sorted(Comparator.comparingInt(DeterministicReviewRule::order)
                        .thenComparing(DeterministicReviewRule::id))
                .toList();
    }

    private List<ToolCheckResult> runRule(DeterministicReviewRule rule, ToolCheckRequest request) {
        try {
            List<ToolCheckResult> results = rule.check(request);
            log.info("Deterministic review rule executed, ruleId={}, filePath={}, issueCount={}",
                    rule.id(), request.getFilePath(), results == null ? 0 : results.size());
            return results == null ? List.of() : results;
        } catch (Exception exception) {
            log.warn("Deterministic review rule failed, ruleId={}, filePath={}, message={}",
                    rule.id(), request.getFilePath(), SensitiveDataSanitizer.redact(exception.getMessage()));
            return List.of();
        }
    }

    private List<AiReviewIssue> mapToolResults(
            String filePath,
            DeterministicReviewRule rule,
            List<ToolCheckResult> toolResults
    ) {
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
            issue.setRuleReference(ruleReference(rule, toolResult));
            issues.add(issue);
        }
        return issues;
    }

    private String ruleReference(DeterministicReviewRule rule, ToolCheckResult toolResult) {
        if (toolResult != null && StringUtils.hasText(toolResult.getRuleId())) {
            return toolResult.getRuleId().trim();
        }
        return rule == null ? null : rule.id();
    }

    private List<String> splitLines(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        return text.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
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
