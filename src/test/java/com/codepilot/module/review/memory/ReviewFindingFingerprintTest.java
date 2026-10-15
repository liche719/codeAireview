package com.codepilot.module.review.memory;

import com.codepilot.module.review.entity.ReviewIssue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指纹的两条安全属性：
 * <ol>
 *   <li><b>抗行号漂移</b>：同一处问题在 rebase 或前面插入几行后行号会变，指纹必须不变，
 *       否则去重立刻失效。</li>
 *   <li><b>不过度匹配</b>：两个不同问题算出同一指纹，会让第二个被误判为"已提过"而不再评论——
 *       这是漏报，比重复评论严重得多。</li>
 * </ol>
 */
class ReviewFindingFingerprintTest {

    @Test
    void shouldIgnoreLineNumberSoTheFingerprintSurvivesRebase() {
        ReviewIssue before = issue("SQL_RISK", "src/UserService.java", 42, "字符串拼接 SQL", "使用参数绑定");
        ReviewIssue after = issue("SQL_RISK", "src/UserService.java", 187, "字符串拼接 SQL", "使用参数绑定");

        assertThat(ReviewFindingFingerprint.findingFingerprint(before))
                .as("行号变化不得改变指纹")
                .isEqualTo(ReviewFindingFingerprint.findingFingerprint(after));
        assertThat(ReviewFindingFingerprint.codeFingerprint(before))
                .isEqualTo(ReviewFindingFingerprint.codeFingerprint(after));
    }

    @Test
    void shouldNotCollideForDifferentFindingsInTheSameFile() {
        ReviewIssue sqlRisk = issue("SQL_RISK", "src/UserService.java", 10, "字符串拼接 SQL", "使用参数绑定");
        ReviewIssue secret = issue("SECURITY", "src/UserService.java", 10, "硬编码密钥", "改用环境变量");

        assertThat(ReviewFindingFingerprint.findingFingerprint(sqlRisk))
                .isNotEqualTo(ReviewFindingFingerprint.findingFingerprint(secret));
    }

    @Test
    void shouldNotCollideForTheSameFindingTextInDifferentFiles() {
        ReviewIssue first = issue("SQL_RISK", "src/A.java", 10, "字符串拼接 SQL", "使用参数绑定");
        ReviewIssue second = issue("SQL_RISK", "src/B.java", 10, "字符串拼接 SQL", "使用参数绑定");

        assertThat(ReviewFindingFingerprint.findingFingerprint(first))
                .isNotEqualTo(ReviewFindingFingerprint.findingFingerprint(second));
    }

    @Test
    void shouldBeStableAcrossCaseAndWhitespaceDifferences() {
        ReviewIssue first = issue("SQL_RISK", "src/UserService.java", 10, "Concatenated   SQL String", "Fix it");
        ReviewIssue second = issue("sql_risk", "src/UserService.java", 10, "concatenated sql string", "fix it");

        assertThat(ReviewFindingFingerprint.findingFingerprint(first))
                .isEqualTo(ReviewFindingFingerprint.findingFingerprint(second));
    }

    /**
     * 识别信息不足时必须返回 null，调用方据此按"全新问题"处理。
     * 拿一个粗糙指纹去撞，就会把不相干的问题误判成重复。
     */
    @Test
    void shouldReturnNullWhenThereIsNotEnoughIdentityInformation() {
        ReviewIssue blankTitleAndDescription = issue("UNKNOWN", "src/A.java", 1, "", "");
        assertThat(ReviewFindingFingerprint.findingFingerprint(blankTitleAndDescription)).isNull();

        ReviewIssue missingPath = issue("SQL_RISK", null, 1, "字符串拼接 SQL", "使用参数绑定");
        assertThat(ReviewFindingFingerprint.findingFingerprint(missingPath)).isNull();

        ReviewIssue missingType = issue(null, "src/A.java", 1, "字符串拼接 SQL", "使用参数绑定");
        assertThat(ReviewFindingFingerprint.findingFingerprint(missingType)).isNull();
    }

    @Test
    void shouldFallBackToDescriptionWhenTitleIsTooShort() {
        ReviewIssue shortTitle = issue("SQL_RISK", "src/A.java", 1, "sql", "这是一段足够长的描述用于识别问题");
        ReviewIssue noTitle = issue("SQL_RISK", "src/A.java", 1, "", "这是一段足够长的描述用于识别问题");

        assertThat(ReviewFindingFingerprint.findingFingerprint(shortTitle))
                .as("标题过短时退回描述，两者应得到同一指纹")
                .isEqualTo(ReviewFindingFingerprint.findingFingerprint(noTitle));
    }

    @Test
    void shouldReturnNullCodeFingerprintWhenThereIsNoSuggestion() {
        ReviewIssue noSuggestion = issue("SQL_RISK", "src/A.java", 1, "字符串拼接 SQL", null);

        assertThat(ReviewFindingFingerprint.findingFingerprint(noSuggestion)).isNotNull();
        assertThat(ReviewFindingFingerprint.codeFingerprint(noSuggestion)).isNull();
    }

    @Test
    void shouldKeepCjkCharactersSoChineseFindingsStayDistinct() {
        ReviewIssue first = issue("SQL_RISK", "src/A.java", 1, "缺少 WHERE 条件", "补充条件");
        ReviewIssue second = issue("SQL_RISK", "src/A.java", 1, "缺少事务边界", "补充事务");

        assertThat(ReviewFindingFingerprint.findingFingerprint(first))
                .isNotEqualTo(ReviewFindingFingerprint.findingFingerprint(second));
    }

    @Test
    void shouldProduceSixteenHexCharacters() {
        String fingerprint = ReviewFindingFingerprint.findingFingerprint(
                issue("SQL_RISK", "src/A.java", 1, "字符串拼接 SQL", "使用参数绑定"));

        assertThat(fingerprint).hasSize(16).matches("[0-9a-f]{16}");
    }

    private ReviewIssue issue(String issueType, String filePath, Integer line, String title, String suggestion) {
        ReviewIssue issue = new ReviewIssue();
        issue.setIssueType(issueType);
        issue.setFilePath(filePath);
        issue.setLineNumber(line);
        issue.setTitle(title);
        issue.setDescription(title);
        issue.setSuggestion(suggestion);
        issue.setSeverity("HIGH");
        return issue;
    }
}
