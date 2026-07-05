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
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "codepilot.tools", name = {"enabled", "sql-risk-enabled"}, havingValue = "true", matchIfMissing = true)
public class SqlRiskTool implements DeterministicReviewRule {

    private static final Pattern SELECT_ALL = Pattern.compile("(?is)\\bselect\\s+\\*");

    private static final Pattern LIKE_LEFT_WILDCARD = Pattern.compile("(?is)\\blike\\s+['\"]%");

    private static final Pattern UPDATE_WITHOUT_WHERE = Pattern.compile("(?is)\\bupdate\\b(?![^;\\n]*\\bwhere\\b)[^;\\n]*");

    private static final Pattern DELETE_WITHOUT_WHERE = Pattern.compile("(?is)\\bdelete\\s+from\\b(?![^;\\n]*\\bwhere\\b)[^;\\n]*");

    private final SqlAstRiskAnalyzer astRiskAnalyzer = new SqlAstRiskAnalyzer();

    private final SqlStringConcatenationDetector stringConcatenationDetector = new SqlStringConcatenationDetector();

    private final SqlRiskIssueFactory issueFactory = new SqlRiskIssueFactory();

    @Override
    public String id() {
        return "SQL_RISK_RULE";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public List<ToolCheckResult> check(ToolCheckRequest request) {
        return checkSqlRisk(
                request == null ? null : request.getFilePath(),
                request == null ? null : request.getPatch()
        );
    }

    @Tool("检测代码 Diff 中的 SQL 风险，包括 SELECT *、字符串拼接 SQL、MyBatis ${}、UPDATE/DELETE 无 WHERE 等")
    public List<ToolCheckResult> checkSqlRisk(
            @P("当前审查文件路径") String filePath,
            @P("当前审查文件的 GitHub Pull Request diff patch") String patch
    ) {
        long startTime = System.currentTimeMillis();
        try {
            if (!StringUtils.hasText(patch)) {
                return List.of();
            }

            List<DiffToolUtils.AddedLine> addedLines = DiffToolUtils.addedLineEntries(patch);
            String content = reviewContent(patch);
            SqlAstFindings astFindings = astRiskAnalyzer.analyze(content);
            List<ToolCheckResult> results = collectSqlRiskIssues(addedLines, content, astFindings);

            log.info("SqlRiskTool executed, filePath={}, hit={}, issueCount={}, costTimeMs={}",
                    filePath, !results.isEmpty(), results.size(), System.currentTimeMillis() - startTime);
            return results;
        } catch (Exception exception) {
            log.warn("SqlRiskTool failed, filePath={}, message={}",
                    filePath, SensitiveDataSanitizer.redact(exception.getMessage()));
            return List.of();
        }
    }

    private String reviewContent(String patch) {
        String addedText = DiffToolUtils.addedText(patch);
        return StringUtils.hasText(addedText) ? addedText : patch;
    }

    private List<ToolCheckResult> collectSqlRiskIssues(
            List<DiffToolUtils.AddedLine> addedLines,
            String content,
            SqlAstFindings astFindings
    ) {
        List<ToolCheckResult> results = new ArrayList<>();
        addSelectAllIssue(results, addedLines, content, astFindings);
        addStringConcatenationIssue(results, addedLines, content);
        addMyBatisPlaceholderIssue(results, addedLines, content);
        addUpdateWithoutWhereIssue(results, addedLines, content, astFindings);
        addDeleteWithoutWhereIssue(results, addedLines, content, astFindings);
        addLikeLeftWildcardIssue(results, addedLines, content);
        return results;
    }

    private void addSelectAllIssue(
            List<ToolCheckResult> results,
            List<DiffToolUtils.AddedLine> addedLines,
            String content,
            SqlAstFindings astFindings
    ) {
        if (astFindings.selectAll() || (!astFindings.parsed() && SELECT_ALL.matcher(content).find())) {
            results.add(issueFactory.selectAll(lineNumber(addedLines, line -> SELECT_ALL.matcher(line).find())));
        }
    }

    private void addStringConcatenationIssue(
            List<ToolCheckResult> results,
            List<DiffToolUtils.AddedLine> addedLines,
            String content
    ) {
        if (stringConcatenationDetector.hasRisk(content)) {
            results.add(issueFactory.stringConcatenation(
                    lineNumber(addedLines, stringConcatenationDetector::hasRisk)
            ));
        }
    }

    private void addMyBatisPlaceholderIssue(
            List<ToolCheckResult> results,
            List<DiffToolUtils.AddedLine> addedLines,
            String content
    ) {
        if (astRiskAnalyzer.hasMyBatisPlaceholderRisk(content)) {
            results.add(issueFactory.myBatisPlaceholder(
                    lineNumber(addedLines, astRiskAnalyzer::hasMyBatisPlaceholderRisk)
            ));
        }
    }

    private void addUpdateWithoutWhereIssue(
            List<ToolCheckResult> results,
            List<DiffToolUtils.AddedLine> addedLines,
            String content,
            SqlAstFindings astFindings
    ) {
        if (astFindings.updateWithoutWhere() || (!astFindings.parsed() && UPDATE_WITHOUT_WHERE.matcher(content).find())) {
            results.add(issueFactory.updateWithoutWhere(
                    lineNumber(addedLines, line -> UPDATE_WITHOUT_WHERE.matcher(line).find())
            ));
        }
    }

    private void addDeleteWithoutWhereIssue(
            List<ToolCheckResult> results,
            List<DiffToolUtils.AddedLine> addedLines,
            String content,
            SqlAstFindings astFindings
    ) {
        if (astFindings.deleteWithoutWhere() || (!astFindings.parsed() && DELETE_WITHOUT_WHERE.matcher(content).find())) {
            results.add(issueFactory.deleteWithoutWhere(
                    lineNumber(addedLines, line -> DELETE_WITHOUT_WHERE.matcher(line).find())
            ));
        }
    }

    private void addLikeLeftWildcardIssue(
            List<ToolCheckResult> results,
            List<DiffToolUtils.AddedLine> addedLines,
            String content
    ) {
        if (LIKE_LEFT_WILDCARD.matcher(content).find()) {
            results.add(issueFactory.likeLeftWildcard(
                    lineNumber(addedLines, line -> LIKE_LEFT_WILDCARD.matcher(line).find())
            ));
        }
    }

    private Integer lineNumber(List<DiffToolUtils.AddedLine> addedLines, Predicate<String> matcher) {
        if (addedLines == null || addedLines.isEmpty() || matcher == null) {
            return null;
        }
        return addedLines.stream()
                .filter(addedLine -> matcher.test(addedLine.text()))
                .map(DiffToolUtils.AddedLine::newLineNumber)
                .findFirst()
                .orElse(addedLines.getFirst().newLineNumber());
    }
}
