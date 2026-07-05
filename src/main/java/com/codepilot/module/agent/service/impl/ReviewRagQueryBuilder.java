package com.codepilot.module.agent.service.impl;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ReviewRagQueryBuilder {

    private static final int MAX_QUERY_CHARS = 1500;

    String buildRuleSearchQuery(String filePath, String patch) {
        if (!StringUtils.hasText(patch)) {
            return "";
        }

        StringBuilder query = new StringBuilder();
        if (StringUtils.hasText(filePath)) {
            query.append("filePath: ").append(filePath.trim()).append('\n');
        }

        String addedLines = extractAddedLines(patch);
        query.append(truncate(addedLines, MAX_QUERY_CHARS));

        String normalizedPath = StringUtils.hasText(filePath)
                ? filePath.replace('\\', '/').toLowerCase(Locale.ROOT)
                : "";
        String normalizedPatch = patch.toLowerCase(Locale.ROOT);

        if (normalizedPath.endsWith(".java")) {
            query.append("\nJava Spring Boot 编码规范 异常处理 日志 安全 SQL");
        }
        if (containsAny(normalizedPatch, "select", "update", "delete", "insert", "mybatis", "mapper.xml")) {
            query.append("\nSQL 规范 参数绑定 SQL 注入");
        }
        if (containsAny(normalizedPatch, "redis", "cache")) {
            query.append("\nRedis 缓存规范");
        }
        if (containsAny(normalizedPatch, "password", "token", "secret", "key")) {
            query.append("\n安全规范 敏感信息");
        }

        return truncate(query.toString().trim(), MAX_QUERY_CHARS);
    }

    List<String> inferRuleTypes(String filePath, String patch) {
        String normalizedPath = StringUtils.hasText(filePath)
                ? filePath.replace('\\', '/').toLowerCase(Locale.ROOT)
                : "";
        String normalizedPatch = StringUtils.hasText(patch) ? patch.toLowerCase(Locale.ROOT) : "";

        List<String> types = new ArrayList<>();
        if (containsAny(normalizedPatch, "select", "update", "delete", "insert", "mybatis", "mapper.xml", "${")) {
            types.add("SQL_RULE");
        }
        if (containsAny(normalizedPatch, "password", "passwd", "token", "secret", "accesskey", "apikey", "privatekey")) {
            types.add("SECURITY_RULE");
        }
        if (containsAny(normalizedPatch, "redis", "cache")) {
            types.add("REDIS_RULE");
        }
        if (normalizedPath.endsWith(".java")) {
            types.add("JAVA_STYLE");
            types.add("LOG_EXCEPTION_RULE");
            types.add("TEST_RULE");
        }
        return types.stream().distinct().toList();
    }

    private String extractAddedLines(String patch) {
        StringBuilder addedLines = new StringBuilder();
        for (String line : patch.split("\\R")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                String added = line.substring(1).trim();
                if (StringUtils.hasText(added)) {
                    if (!addedLines.isEmpty()) {
                        addedLines.append('\n');
                    }
                    addedLines.append(added);
                }
            }
        }
        return addedLines.toString();
    }

    private boolean containsAny(String content, String... keywords) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String content, int maxLength) {
        if (content == null || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, Math.max(0, maxLength));
    }
}
