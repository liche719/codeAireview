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
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "codepilot.tools", name = {"enabled", "sql-risk-enabled"}, havingValue = "true", matchIfMissing = true)
public class SqlRiskTool {

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

            String addedText = DiffToolUtils.addedText(patch);
            String content = StringUtils.hasText(addedText) ? addedText : patch;
            String normalized = content.toLowerCase(Locale.ROOT);
            List<ToolCheckResult> results = new ArrayList<>();

            if (SELECT_ALL.matcher(content).find()) {
                results.add(ToolCheckResult.of(
                        "SQL_RISK",
                        "LOW",
                        "SQL 查询使用 SELECT *",
                        "Diff 中出现 SELECT *，可能导致字段膨胀、索引覆盖失效或接口返回不稳定。",
                        "请明确列出需要查询的字段。"
                ));
            }
            if (hasSqlStringConcatenation(content)) {
                results.add(ToolCheckResult.of(
                        "SQL_RISK",
                        "HIGH",
                        "存在 SQL 字符串拼接风险",
                        "Diff 中疑似通过字符串拼接构造 SQL，用户输入参与拼接时可能导致 SQL 注入。",
                        "请使用 MyBatis 参数绑定、预编译语句或安全的查询构造方式。"
                ));
            }
            if (normalized.contains("${")) {
                results.add(ToolCheckResult.of(
                        "SQL_RISK",
                        "HIGH",
                        "MyBatis ${} 存在直接拼接风险",
                        "MyBatis ${} 会进行字符串替换，若参数来自外部输入可能导致 SQL 注入。",
                        "请改用 #{} 参数绑定；确需动态表名或排序字段时必须做白名单校验。"
                ));
            }
            if (UPDATE_WITHOUT_WHERE.matcher(content).find()) {
                results.add(ToolCheckResult.of(
                        "SQL_RISK",
                        "HIGH",
                        "UPDATE 语句缺少 WHERE 条件",
                        "Diff 中出现疑似无 WHERE 条件的 UPDATE，可能造成批量误更新。",
                        "请为 UPDATE 添加明确 WHERE 条件，并确认批量更新场景有保护措施。"
                ));
            }
            if (DELETE_WITHOUT_WHERE.matcher(content).find()) {
                results.add(ToolCheckResult.of(
                        "SQL_RISK",
                        "HIGH",
                        "DELETE 语句缺少 WHERE 条件",
                        "Diff 中出现疑似无 WHERE 条件的 DELETE，可能造成批量误删除。",
                        "请为 DELETE 添加明确 WHERE 条件，并确认删除范围受控。"
                ));
            }
            if (LIKE_LEFT_WILDCARD.matcher(content).find()) {
                results.add(ToolCheckResult.of(
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
            log.warn("SqlRiskTool failed, filePath={}, message={}", filePath, exception.getMessage());
            return List.of();
        }
    }

    private boolean hasSqlStringConcatenation(String content) {
        for (String line : content.split("\\R")) {
            String normalizedLine = line.toLowerCase(Locale.ROOT);
            if (line.contains("+") && containsAny(normalizedLine, "select", "update", "delete", "insert")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String content, String... keywords) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
