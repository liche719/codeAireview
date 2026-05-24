package com.codepilot.module.tool.impl;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.tool.context.DiffToolUtils;
import com.codepilot.module.tool.dto.ToolCheckResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.update.Update;
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
public class SqlRiskTool {

    private static final Pattern SQL_KEYWORD = Pattern.compile("(?is)\\b(select|update|delete|insert)\\b");

    private static final Pattern SELECT_STATEMENT = Pattern.compile("(?is)\\bselect\\b.+\\bfrom\\b");

    private static final Pattern MUTATION_STATEMENT = Pattern.compile(
            "(?is)\\b(?:update\\s+[\\w.`\"]+\\s+set|delete\\s+from\\s+[\\w.`\"]+|insert\\s+into\\s+[\\w.`\"]+)\\b"
    );

    private static final Pattern SELECT_ALL = Pattern.compile("(?is)\\bselect\\s+\\*");

    private static final Pattern LIKE_LEFT_WILDCARD = Pattern.compile("(?is)\\blike\\s+['\"]%");

    private static final Pattern UPDATE_WITHOUT_WHERE = Pattern.compile("(?is)\\bupdate\\b(?![^;\\n]*\\bwhere\\b)[^;\\n]*");

    private static final Pattern DELETE_WITHOUT_WHERE = Pattern.compile("(?is)\\bdelete\\s+from\\b(?![^;\\n]*\\bwhere\\b)[^;\\n]*");

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
            String addedText = DiffToolUtils.addedText(patch);
            String content = StringUtils.hasText(addedText) ? addedText : patch;
            String normalized = content.toLowerCase(Locale.ROOT);
            List<ToolCheckResult> results = new ArrayList<>();
            SqlAstFindings astFindings = analyzeSqlAst(content);

            if (astFindings.selectAll() || (!astFindings.parsed() && SELECT_ALL.matcher(content).find())) {
                results.add(ToolCheckResult.atLine(
                        lineNumber(addedLines, line -> SELECT_ALL.matcher(line).find()),
                        "SQL_RISK",
                        "LOW",
                        "SQL 查询使用 SELECT *",
                        "Diff 中出现 SELECT *，可能导致字段膨胀、索引覆盖失效或接口返回不稳定。",
                        "请明确列出需要查询的字段。"
                ));
            }
            if (hasSqlStringConcatenation(content)) {
                results.add(ToolCheckResult.atLine(
                        lineNumber(addedLines, this::hasSqlStringConcatenation),
                        "SQL_RISK",
                        "HIGH",
                        "存在 SQL 字符串拼接风险",
                        "Diff 中疑似通过字符串拼接构造 SQL，用户输入参与拼接时可能导致 SQL 注入。",
                        "请使用 MyBatis 参数绑定、预编译语句或安全的查询构造方式。"
                ));
            }
            if (normalized.contains("${")) {
                results.add(ToolCheckResult.atLine(
                        lineNumber(addedLines, line -> line.contains("${")),
                        "SQL_RISK",
                        "HIGH",
                        "MyBatis ${} 存在直接拼接风险",
                        "MyBatis ${} 会进行字符串替换，若参数来自外部输入可能导致 SQL 注入。",
                        "请改用 #{} 参数绑定；确需动态表名或排序字段时必须做白名单校验。"
                ));
            }
            if (astFindings.updateWithoutWhere() || (!astFindings.parsed() && UPDATE_WITHOUT_WHERE.matcher(content).find())) {
                results.add(ToolCheckResult.atLine(
                        lineNumber(addedLines, line -> UPDATE_WITHOUT_WHERE.matcher(line).find()),
                        "SQL_RISK",
                        "HIGH",
                        "UPDATE 语句缺少 WHERE 条件",
                        "Diff 中出现疑似无 WHERE 条件的 UPDATE，可能造成批量误更新。",
                        "请为 UPDATE 添加明确 WHERE 条件，并确认批量更新场景有保护措施。"
                ));
            }
            if (astFindings.deleteWithoutWhere() || (!astFindings.parsed() && DELETE_WITHOUT_WHERE.matcher(content).find())) {
                results.add(ToolCheckResult.atLine(
                        lineNumber(addedLines, line -> DELETE_WITHOUT_WHERE.matcher(line).find()),
                        "SQL_RISK",
                        "HIGH",
                        "DELETE 语句缺少 WHERE 条件",
                        "Diff 中出现疑似无 WHERE 条件的 DELETE，可能造成批量误删除。",
                        "请为 DELETE 添加明确 WHERE 条件，并确认删除范围受控。"
                ));
            }
            if (LIKE_LEFT_WILDCARD.matcher(content).find()) {
                results.add(ToolCheckResult.atLine(
                        lineNumber(addedLines, line -> LIKE_LEFT_WILDCARD.matcher(line).find()),
                        "SQL_RISK",
                        "MEDIUM",
                        "LIKE 左模糊查询可能影响索引",
                        "Diff 中出现 LIKE '%...' 形式，通常无法有效使用普通 B-Tree 索引。",
                        "请评估数据量和索引策略，必要时使用全文索引、倒排索引或更合适的查询方式。"
                ));
            }

            log.info("SqlRiskTool executed, filePath={}, hit={}, issueCount={}, costTimeMs={}",
                    filePath, !results.isEmpty(), results.size(), System.currentTimeMillis() - startTime);
            return results;
        } catch (Exception exception) {
            log.warn("SqlRiskTool failed, filePath={}, message={}",
                    filePath, SensitiveDataSanitizer.redact(exception.getMessage()));
            return List.of();
        }
    }

    private boolean hasSqlStringConcatenation(String content) {
        for (String statement : statements(content)) {
            if (hasSqlStringConcatenationExpression(statement)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSqlStringConcatenationExpression(String statement) {
        if (!StringUtils.hasText(statement) || !statement.contains("+") || !SQL_KEYWORD.matcher(statement).find()) {
            return false;
        }
        List<String> segments = splitByConcatenationOperator(statement);
        if (segments.size() < 2) {
            return false;
        }
        StringBuilder literalText = new StringBuilder();
        boolean hasDynamicSegment = false;
        for (String segment : segments) {
            String segmentLiteralText = stringLiteralText(segment);
            if (StringUtils.hasText(segmentLiteralText)) {
                literalText.append(' ').append(segmentLiteralText);
            }
            hasDynamicSegment = hasDynamicSegment || isDynamicConcatenationSegment(segment);
        }
        return likelySqlText(literalText.toString()) && hasDynamicSegment;
    }

    private List<String> statements(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;
        for (char currentChar : content.toCharArray()) {
            current.append(currentChar);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (currentChar == '\\') {
                escaped = true;
                continue;
            }
            if (currentChar == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (currentChar == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (currentChar == ';' && !inSingleQuote && !inDoubleQuote) {
                addStatement(statements, current);
            }
        }
        addStatement(statements, current);
        return statements;
    }

    private void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (StringUtils.hasText(statement)) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    private List<String> splitByConcatenationOperator(String statement) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;
        for (char currentChar : statement.toCharArray()) {
            if (escaped) {
                current.append(currentChar);
                escaped = false;
                continue;
            }
            if (currentChar == '\\') {
                current.append(currentChar);
                escaped = true;
                continue;
            }
            if (currentChar == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                current.append(currentChar);
                continue;
            }
            if (currentChar == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(currentChar);
                continue;
            }
            if (currentChar == '+' && !inSingleQuote && !inDoubleQuote) {
                segments.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(currentChar);
        }
        segments.add(current.toString());
        return segments;
    }

    private String stringLiteralText(String segment) {
        if (!StringUtils.hasText(segment)) {
            return "";
        }
        StringBuilder literals = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;
        for (char currentChar : segment.toCharArray()) {
            if (escaped) {
                if (inSingleQuote || inDoubleQuote) {
                    literal.append(currentChar);
                }
                escaped = false;
                continue;
            }
            if (currentChar == '\\') {
                escaped = true;
                continue;
            }
            if (currentChar == '\'' && !inDoubleQuote) {
                if (inSingleQuote && !literal.isEmpty()) {
                    literals.append(' ').append(literal);
                }
                inSingleQuote = !inSingleQuote;
                if (inSingleQuote) {
                    literal.setLength(0);
                }
                continue;
            }
            if (currentChar == '"' && !inSingleQuote) {
                if (inDoubleQuote && !literal.isEmpty()) {
                    literals.append(' ').append(literal);
                }
                inDoubleQuote = !inDoubleQuote;
                if (inDoubleQuote) {
                    literal.setLength(0);
                }
                continue;
            }
            if (inSingleQuote || inDoubleQuote) {
                literal.append(currentChar);
            }
        }
        return literals.toString();
    }

    private boolean likelySqlText(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String compact = content.replaceAll("\\s+", " ").trim();
        return SELECT_STATEMENT.matcher(compact).find() || MUTATION_STATEMENT.matcher(compact).find();
    }

    private boolean isDynamicConcatenationSegment(String segment) {
        String normalized = stripOuterParentheses(stripTrailingSyntax(segment));
        if (!StringUtils.hasText(normalized) || isQuotedLiteralOnly(normalized) || isSafeConstant(normalized)) {
            return false;
        }
        return normalized.matches(".*[A-Za-z_$][\\w$]*(?:\\s*\\(|\\.|\\[|$).*");
    }

    private String stripTrailingSyntax(String segment) {
        String normalized = segment == null ? "" : segment.trim();
        while (normalized.endsWith(";") || normalized.endsWith(",")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private String stripOuterParentheses(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.startsWith("(") && normalized.endsWith(")") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private boolean isQuotedLiteralOnly(String value) {
        String normalized = value == null ? "" : value.trim();
        return (normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'"));
    }

    private boolean isSafeConstant(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized)) {
            return true;
        }
        if (normalized.matches("[-+]?\\d+(?:\\.\\d+)?[lLdDfF]?")
                || normalized.equals("true")
                || normalized.equals("false")
                || normalized.equals("null")) {
            return true;
        }
        return normalized.matches("[A-Z0-9_$.]+");
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

    private boolean containsAny(String content, String... keywords) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private SqlAstFindings analyzeSqlAst(String content) {
        List<String> candidates = sqlCandidates(content);
        if (candidates.isEmpty()) {
            return SqlAstFindings.notParsed();
        }
        SqlAstFindings findings = new SqlAstFindings(false, false, false, false);
        for (String candidate : candidates) {
            try {
                Statement statement = CCJSqlParserUtil.parse(normalizeMyBatisPlaceholders(candidate));
                findings = findings.merge(analyzeStatement(statement));
            } catch (Exception ignored) {
                // Incomplete Java/MyBatis fragments still fall back to regex heuristics.
            }
        }
        return findings;
    }

    private String normalizeMyBatisPlaceholders(String candidate) {
        return candidate
                .replaceAll("#\\{[^}]+}", "?")
                .replaceAll("\\$\\{[^}]+}", "?");
    }

    private List<String> sqlCandidates(String content) {
        List<String> candidates = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : content.split("\\R")) {
            String line = stripJavaStringNoise(rawLine).trim();
            if (!StringUtils.hasText(line)) {
                continue;
            }
            if (startsWithSqlKeyword(line) || !current.isEmpty()) {
                if (!current.isEmpty()) {
                    current.append(' ');
                }
                current.append(line);
                if (line.endsWith(";")) {
                    addCandidate(candidates, current.toString());
                    current.setLength(0);
                }
            }
        }
        if (!current.isEmpty()) {
            addCandidate(candidates, current.toString());
        }
        return candidates;
    }

    private String stripJavaStringNoise(String line) {
        String trimmed = line == null ? "" : line.trim();
        int assignmentIndex = trimmed.indexOf('=');
        if (assignmentIndex >= 0 && !startsWithSqlKeyword(trimmed)) {
            trimmed = trimmed.substring(assignmentIndex + 1).trim();
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\";"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            trimmed = trimmed.substring(1, trimmed.endsWith("\";") ? trimmed.length() - 2 : trimmed.length() - 1);
        }
        return trimmed;
    }

    private void addCandidate(List<String> candidates, String candidate) {
        String normalized = candidate.trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (StringUtils.hasText(normalized) && startsWithSqlKeyword(normalized)) {
            candidates.add(normalized);
        }
    }

    private boolean startsWithSqlKeyword(String content) {
        String normalized = content == null ? "" : content.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("select ")
                || normalized.startsWith("update ")
                || normalized.startsWith("delete ");
    }

    private SqlAstFindings analyzeStatement(Statement statement) {
        if (statement instanceof Select select) {
            return SqlAstFindings.parsed(hasSelectAll(select), false, false);
        }
        if (statement instanceof Update update) {
            return SqlAstFindings.parsed(false, update.getWhere() == null, false);
        }
        if (statement instanceof Delete delete) {
            return SqlAstFindings.parsed(false, false, delete.getWhere() == null);
        }
        return SqlAstFindings.parsed(false, false, false);
    }

    private boolean hasSelectAll(Select select) {
        if (select instanceof PlainSelect plainSelect) {
            return plainSelectHasSelectAll(plainSelect);
        }
        if (select instanceof SetOperationList setOperationList) {
            return setOperationList.getSelects() != null
                    && setOperationList.getSelects().stream().anyMatch(this::hasSelectAll);
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            return parenthesedSelect.getSelect() != null && hasSelectAll(parenthesedSelect.getSelect());
        }
        return false;
    }

    private boolean plainSelectHasSelectAll(PlainSelect plainSelect) {
        if (plainSelect.getSelectItems() != null) {
            for (SelectItem<?> item : plainSelect.getSelectItems()) {
                if (item != null && item.getExpression() instanceof AllColumns) {
                    return true;
                }
            }
        }
        if (plainSelect.getFromItem() instanceof ParenthesedSelect parenthesedSelect
                && parenthesedSelect.getSelect() != null
                && hasSelectAll(parenthesedSelect.getSelect())) {
            return true;
        }
        return plainSelect.getJoins() != null
                && plainSelect.getJoins().stream()
                .anyMatch(join -> join.getRightItem() instanceof ParenthesedSelect parenthesedSelect
                        && parenthesedSelect.getSelect() != null
                        && hasSelectAll(parenthesedSelect.getSelect()));
    }

    private record SqlAstFindings(boolean parsed, boolean selectAll, boolean updateWithoutWhere, boolean deleteWithoutWhere) {

        private static SqlAstFindings parsed(boolean selectAll, boolean updateWithoutWhere, boolean deleteWithoutWhere) {
            return new SqlAstFindings(true, selectAll, updateWithoutWhere, deleteWithoutWhere);
        }

        private static SqlAstFindings notParsed() {
            return new SqlAstFindings(false, false, false, false);
        }

        private SqlAstFindings merge(SqlAstFindings other) {
            return new SqlAstFindings(
                    parsed || other.parsed,
                    selectAll || other.selectAll,
                    updateWithoutWhere || other.updateWithoutWhere,
                    deleteWithoutWhere || other.deleteWithoutWhere
            );
        }
    }
}
