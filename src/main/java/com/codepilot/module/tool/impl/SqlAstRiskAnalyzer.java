package com.codepilot.module.tool.impl;

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
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class SqlAstRiskAnalyzer {

    SqlAstFindings analyze(String content) {
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

    boolean hasMyBatisPlaceholderRisk(String content) {
        if (!StringUtils.hasText(content) || !content.contains("${")) {
            return false;
        }
        return sqlCandidates(content).stream()
                .anyMatch(candidate -> candidate.contains("${"));
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
                || normalized.startsWith("delete ")
                || normalized.startsWith("insert ");
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
}
