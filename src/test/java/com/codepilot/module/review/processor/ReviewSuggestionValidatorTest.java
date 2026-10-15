package com.codepilot.module.review.processor;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.diff.DiffLineMapping;
import com.codepilot.module.review.entity.ReviewIssue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSuggestionValidatorTest {

    @Test
    void shouldRejectWhenSuggestionFeatureIsDisabled() {
        // 出厂默认就是关闭——任何情况下都不该给出一键应用按钮。
        ReviewSuggestionValidator validator = new ReviewSuggestionValidator(new ReviewProperties());

        assertThat(validator.isApplicable(issue("new code"), mapping(true, 10, "RIGHT"))).isFalse();
    }

    @Test
    void shouldAcceptRightSideCommentableWithinLineLimit() {
        assertThat(newValidator(true, 20).isApplicable(issue("new code"), mapping(true, 10, "RIGHT"))).isTrue();
    }

    @Test
    void shouldRejectBlankOrMissingSuggestedCode() {
        ReviewSuggestionValidator validator = newValidator(true, 20);

        assertThat(validator.isApplicable(issue(null), mapping(true, 10, "RIGHT"))).isFalse();
        assertThat(validator.isApplicable(issue("   \n  "), mapping(true, 10, "RIGHT"))).isFalse();
    }

    @Test
    void shouldRejectNonCommentableMapping() {
        assertThat(newValidator(true, 20).isApplicable(issue("new code"), mapping(false, null, null))).isFalse();
    }

    /**
     * 删除侧的内容在建议应用后就不存在了：对它给"一键应用"只会产生错内容。
     */
    @Test
    void shouldRejectLeftSideMapping() {
        assertThat(newValidator(true, 20).isApplicable(issue("new code"), mapping(true, 11, "LEFT"))).isFalse();
    }

    @Test
    void shouldAcceptLowercaseSideName() {
        assertThat(newValidator(true, 20).isApplicable(issue("new code"), mapping(true, 10, "right"))).isTrue();
    }

    @Test
    void shouldRejectWhenExceedingMaxLines() {
        ReviewSuggestionValidator validator = newValidator(true, 2);

        assertThat(validator.isApplicable(issue("a\nb"), mapping(true, 10, "RIGHT"))).isTrue();
        assertThat(validator.isApplicable(issue("a\nb\nc"), mapping(true, 10, "RIGHT"))).isFalse();
    }

    /** 结尾多一个换行不该被算成多一行，否则"刚好卡在上限"的建议会被无谓地降级。 */
    @Test
    void shouldIgnoreTrailingNewlineWhenCountingLines() {
        assertThat(ReviewSuggestionValidator.countLines("a\nb\n")).isEqualTo(2);
        assertThat(ReviewSuggestionValidator.countLines("a\nb")).isEqualTo(2);
        assertThat(ReviewSuggestionValidator.countLines("a\r\nb\r\n")).isEqualTo(2);
        assertThat(ReviewSuggestionValidator.countLines("")).isZero();
        assertThat(ReviewSuggestionValidator.countLines(null)).isZero();
        // 只有一个换行的内容在去掉结尾换行后为空，应算 0 行而不是 1 行。
        assertThat(ReviewSuggestionValidator.countLines("\n")).isZero();
    }

    @Test
    void shouldTreatNonPositiveMaxLinesAsOneLine() {
        ReviewSuggestionValidator validator = newValidator(true, 0);

        assertThat(validator.isApplicable(issue("single"), mapping(true, 10, "RIGHT"))).isTrue();
        assertThat(validator.isApplicable(issue("a\nb"), mapping(true, 10, "RIGHT"))).isFalse();
    }

    @Test
    void shouldRejectNullIssueOrMapping() {
        ReviewSuggestionValidator validator = newValidator(true, 20);

        assertThat(validator.isApplicable(null, mapping(true, 10, "RIGHT"))).isFalse();
        assertThat(validator.isApplicable(issue("new code"), null)).isFalse();
    }

    private static ReviewSuggestionValidator newValidator(boolean enabled, int maxLines) {
        ReviewProperties properties = new ReviewProperties();
        properties.setSuggestionEnabled(enabled);
        properties.setSuggestionMaxLines(maxLines);
        return new ReviewSuggestionValidator(properties);
    }

    private static ReviewIssue issue(String suggestedCode) {
        ReviewIssue issue = new ReviewIssue();
        issue.setSuggestedCode(suggestedCode);
        return issue;
    }

    private static DiffLineMapping mapping(boolean commentable, Integer line, String side) {
        return new DiffLineMapping(commentable, line, side, null);
    }
}
