package com.codepilot.module.tool.impl;

import com.codepilot.module.tool.dto.ToolCheckResult;

class SqlRiskIssueFactory {

    private static final String ISSUE_TYPE = "SQL_RISK";

    ToolCheckResult selectAll(Integer lineNumber) {
        return ToolCheckResult.atLine(
                lineNumber,
                ISSUE_TYPE,
                "LOW",
                "SQL 查询使用 SELECT *",
                "Diff 中出现 SELECT *，可能导致字段膨胀、索引覆盖失效或接口返回不稳定。",
                "请明确列出需要查询的字段。"
        );
    }

    ToolCheckResult stringConcatenation(Integer lineNumber) {
        return ToolCheckResult.atLine(
                lineNumber,
                ISSUE_TYPE,
                "HIGH",
                "存在 SQL 字符串拼接风险",
                "Diff 中疑似通过字符串拼接构造 SQL，用户输入参与拼接时可能导致 SQL 注入。",
                "请使用 MyBatis 参数绑定、预编译语句或安全的查询构造方式。"
        );
    }

    ToolCheckResult myBatisPlaceholder(Integer lineNumber) {
        return ToolCheckResult.atLine(
                lineNumber,
                ISSUE_TYPE,
                "HIGH",
                "MyBatis ${} 存在直接拼接风险",
                "MyBatis ${} 会进行字符串替换，若参数来自外部输入可能导致 SQL 注入。",
                "请改用 #{} 参数绑定；确需动态表名或排序字段时必须做白名单校验。"
        );
    }

    ToolCheckResult updateWithoutWhere(Integer lineNumber) {
        return ToolCheckResult.atLine(
                lineNumber,
                ISSUE_TYPE,
                "HIGH",
                "UPDATE 语句缺少 WHERE 条件",
                "Diff 中出现疑似无 WHERE 条件的 UPDATE，可能造成批量误更新。",
                "请为 UPDATE 添加明确 WHERE 条件，并确认批量更新场景有保护措施。"
        );
    }

    ToolCheckResult deleteWithoutWhere(Integer lineNumber) {
        return ToolCheckResult.atLine(
                lineNumber,
                ISSUE_TYPE,
                "HIGH",
                "DELETE 语句缺少 WHERE 条件",
                "Diff 中出现疑似无 WHERE 条件的 DELETE，可能造成批量误删除。",
                "请为 DELETE 添加明确 WHERE 条件，并确认删除范围受控。"
        );
    }

    ToolCheckResult likeLeftWildcard(Integer lineNumber) {
        return ToolCheckResult.atLine(
                lineNumber,
                ISSUE_TYPE,
                "MEDIUM",
                "LIKE 左模糊查询可能影响索引",
                "Diff 中出现 LIKE '%...' 形式，通常无法有效使用普通 B-Tree 索引。",
                "请评估数据量和索引策略，必要时使用全文索引、倒排索引或更合适的查询方式。"
        );
    }
}
