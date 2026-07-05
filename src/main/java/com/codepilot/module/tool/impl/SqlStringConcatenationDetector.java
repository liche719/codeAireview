package com.codepilot.module.tool.impl;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

class SqlStringConcatenationDetector {

    private static final Pattern SQL_KEYWORD = Pattern.compile("(?is)\\b(select|update|delete|insert)\\b");

    private static final Pattern SELECT_STATEMENT = Pattern.compile("(?is)\\bselect\\b.+\\bfrom\\b");

    private static final Pattern MUTATION_STATEMENT = Pattern.compile(
            "(?is)\\b(?:update\\s+[\\w.`\"]+\\s+set|delete\\s+from\\s+[\\w.`\"]+|insert\\s+into\\s+[\\w.`\"]+)\\b"
    );

    boolean hasRisk(String content) {
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
}
