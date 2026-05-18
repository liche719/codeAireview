package com.codepilot.module.tool.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlRiskToolTest {

    private final SqlRiskTool sqlRiskTool = new SqlRiskTool();

    @Test
    void shouldDetectSelectAll() {
        var results = sqlRiskTool.checkSqlRisk("src/main/java/DemoMapper.java", "+select * from user where id = #{id}");

        assertThat(results)
                .anySatisfy(result -> assertThat(result.getTitle()).contains("SELECT *"));
    }

    @Test
    void shouldDetectSqlStringConcatenation() {
        var results = sqlRiskTool.checkSqlRisk(
                "src/main/java/DemoService.java",
                """
                        +String sql = "select * from user where name = '" + name + "'";
                        """
        );

        assertThat(results)
                .anySatisfy(result -> {
                    assertThat(result.getIssueType()).isEqualTo("SQL_RISK");
                    assertThat(result.getSeverity()).isEqualTo("HIGH");
                    assertThat(result.getTitle()).contains("拼接");
                });
    }

    @Test
    void shouldDetectMyBatisPlaceholderRisk() {
        var results = sqlRiskTool.checkSqlRisk("src/main/resources/mapper/UserMapper.xml", "+select * from user order by ${sort}");

        assertThat(results)
                .anySatisfy(result -> assertThat(result.getTitle()).contains("MyBatis ${}"));
    }

    @Test
    void shouldDetectDeleteWithoutWhere() {
        var results = sqlRiskTool.checkSqlRisk("src/main/java/DemoMapper.java", "+delete from user");

        assertThat(results)
                .anySatisfy(result -> {
                    assertThat(result.getIssueType()).isEqualTo("SQL_RISK");
                    assertThat(result.getTitle()).contains("DELETE");
                });
    }
}
