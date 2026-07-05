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
                        @@ -20,1 +20,2 @@
                        +String sql = "select * from user where name = '" + name + "'";
                        """
        );

        assertThat(results)
                .anySatisfy(result -> {
                    assertThat(result.getIssueType()).isEqualTo("SQL_RISK");
                    assertThat(result.getSeverity()).isEqualTo("HIGH");
                    assertThat(result.getLineNumber()).isEqualTo(20);
                    assertThat(result.getTitle()).contains("拼接");
                });
    }

    @Test
    void shouldDetectSqlStringConcatenationWithMethodCall() {
        var results = sqlRiskTool.checkSqlRisk(
                "src/main/java/DemoService.java",
                """
                        @@ -42,1 +42,2 @@
                        +String sql = "select * from user where id = " + request.getUserId();
                        """
        );

        assertThat(results)
                .anySatisfy(result -> {
                    assertThat(result.getIssueType()).isEqualTo("SQL_RISK");
                    assertThat(result.getSeverity()).isEqualTo("HIGH");
                    assertThat(result.getLineNumber()).isEqualTo(42);
                    assertThat(result.getTitle()).contains("拼接");
                });
    }

    @Test
    void shouldNotFlagLiteralOnlySqlStringConcatenation() {
        var results = sqlRiskTool.checkSqlRisk(
                "src/main/java/DemoService.java",
                """
                        +String sql = "select id, name " + "from user " + "where status = ?";
                        """
        );

        assertThat(results)
                .noneSatisfy(result -> assertThat(result.getTitle()).contains("拼接"));
    }

    @Test
    void shouldNotFlagNonSqlStringConcatenation() {
        var results = sqlRiskTool.checkSqlRisk(
                "src/main/java/DemoService.java",
                """
                        +String message = "select option " + optionName;
                        """
        );

        assertThat(results).isEmpty();
    }

    @Test
    void shouldDetectMyBatisPlaceholderRisk() {
        var results = sqlRiskTool.checkSqlRisk("src/main/resources/mapper/UserMapper.xml", "+select * from user order by ${sort}");

        assertThat(results)
                .anySatisfy(result -> assertThat(result.getTitle()).contains("MyBatis ${}"));
    }

    @Test
    void shouldDetectMyBatisPlaceholderRiskInInsertStatement() {
        var results = sqlRiskTool.checkSqlRisk(
                "src/main/resources/mapper/UserMapper.xml",
                "+insert into audit_log(message, operator) values (#{message}, ${operator})"
        );

        assertThat(results)
                .anySatisfy(result -> assertThat(result.getTitle()).contains("MyBatis ${}"));
    }

    @Test
    void shouldNotFlagSpringValuePlaceholderAsMyBatisPlaceholderRisk() {
        var results = sqlRiskTool.checkSqlRisk(
                "src/main/java/com/codepilot/module/review/report/ReviewReportFormatter.java",
                """
                        @@ -25,1 +25,2 @@
                        +@Value("${codepilot.github.comment-marker:}") String commentMarker,
                        """
        );

        assertThat(results)
                .noneSatisfy(result -> assertThat(result.getTitle()).contains("MyBatis ${}"));
    }

    @Test
    void shouldNotFlagApplicationYamlPlaceholderAsMyBatisPlaceholderRisk() {
        var results = sqlRiskTool.checkSqlRisk(
                "src/main/resources/application.yml",
                """
                        @@ -98,1 +98,2 @@
                        +  max-summary-findings: ${CODEPILOT_REVIEW_MAX_SUMMARY_FINDINGS:20}
                        """
        );

        assertThat(results)
                .noneSatisfy(result -> assertThat(result.getTitle()).contains("MyBatis ${}"));
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

    @Test
    void shouldNotFlagUpdateWhenAstFindsWhereClause() {
        var results = sqlRiskTool.checkSqlRisk(
                "src/main/resources/mapper/UserMapper.xml",
                "+update user set name = #{name} where id = #{id}"
        );

        assertThat(results)
                .noneSatisfy(result -> assertThat(result.getTitle()).contains("UPDATE"));
    }

    @Test
    void shouldNotFlagDeleteWhenAstFindsWhereClause() {
        var results = sqlRiskTool.checkSqlRisk(
                "src/main/resources/mapper/UserMapper.xml",
                "+delete from user where id = #{id}"
        );

        assertThat(results)
                .noneSatisfy(result -> assertThat(result.getTitle()).contains("DELETE"));
    }

    @Test
    void shouldDetectSelectAllInsideUnionWithAst() {
        var results = sqlRiskTool.checkSqlRisk(
                "src/main/resources/mapper/UserMapper.xml",
                """
                        +select id, name from user where status = #{status}
                        +union all
                        +select * from archived_user where status = #{status}
                        """
        );

        assertThat(results)
                .anySatisfy(result -> assertThat(result.getTitle()).contains("SELECT *"));
    }

    @Test
    void shouldAnalyzeSqlExtractedFromSimpleJavaStringLiteral() {
        var results = sqlRiskTool.checkSqlRisk(
                "src/main/java/DemoRepository.java",
                """
                        +String sql = "delete from user";
                        """
        );

        assertThat(results)
                .anySatisfy(result -> assertThat(result.getTitle()).contains("DELETE"));
    }
}
