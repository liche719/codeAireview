package com.codepilot.module.review.report;

import com.codepilot.common.enums.ReviewFileSkipCategory;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.processor.ReviewCommentBudgetAllocator;
import com.codepilot.module.review.processor.ReviewFindingRanker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖度上报的测试。
 *
 * <p>核心约束：<b>只要还有变更文件没被审查，摘要就不能输出"未发现问题"。</b>
 * 修复前，一个 200 文件但只审了 5 个的 PR（甚至一个都没审）会得到一张"体检合格"，
 * 这比漏报更危险——用户会据此认为整个 PR 没问题。</p>
 */
class ReviewReportFormatterCoverageTest {

    private static final String MARKER = "<!-- codepilot-ai-review:liche719/codeAireview -->";

    private ReviewReportFormatter formatter(ReviewProperties properties) {
        return new ReviewReportFormatter(
                MARKER,
                new ReviewCommentBudgetAllocator(properties),
                new ReviewFindingRanker(),
                properties
        );
    }

    /**
     * S2 回归测试：全部文件被跳过时，绝不能输出"未发现问题"。
     */
    @Test
    void shouldNeverReportNoIssuesWhenEveryFileWasSkipped() {
        ReviewReportFormatter formatter = formatter(new ReviewProperties());

        String markdown = formatter.formatMarkdown(null, List.of(), List.of(
                skippedFile("src/A.java", ReviewFileSkipCategory.PATCH_TOO_LARGE),
                skippedFile("src/B.java", ReviewFileSkipCategory.FILE_COUNT_BUDGET)
        ));

        assertThat(markdown).doesNotContain("未发现问题");
        assertThat(markdown).contains("未能审查任何变更文件");
        assertThat(markdown).contains("不代表 PR 没有问题");
    }

    @Test
    void shouldReportNoIssuesOnlyWhenEveryFileWasActuallyReviewed() {
        ReviewReportFormatter formatter = formatter(new ReviewProperties());

        String markdown = formatter.formatMarkdown(null, List.of(), List.of(
                reviewedFile("src/A.java"),
                reviewedFile("src/B.java")
        ));

        assertThat(markdown).contains("未发现问题");
        assertThat(markdown).contains("全部变更文件均已审查");
        assertThat(markdown).doesNotContain("未能审查任何变更文件");
    }

    @Test
    void shouldWarnWhenCoverageFallsBelowConfiguredRatio() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMinCoverageRatio(0.5);
        ReviewReportFormatter formatter = formatter(properties);

        List<ReviewFile> files = new ArrayList<>();
        files.add(reviewedFile("src/A.java"));
        for (int i = 0; i < 9; i++) {
            files.add(skippedFile("src/Skipped" + i + ".java", ReviewFileSkipCategory.FILE_COUNT_BUDGET));
        }

        String markdown = formatter.formatMarkdown(null, List.of(), files);

        assertThat(markdown).contains("审查覆盖不足");
        assertThat(markdown).contains("1 / 10");
        assertThat(markdown).doesNotContain("未发现问题");
    }

    @Test
    void shouldGroupSkippedFilesByReason() {
        ReviewReportFormatter formatter = formatter(new ReviewProperties());

        String markdown = formatter.formatMarkdown(null, List.of(), List.of(
                reviewedFile("src/Reviewed.java"),
                skippedFile("src/Big.java", ReviewFileSkipCategory.PATCH_TOO_LARGE),
                skippedFile("src/Big2.java", ReviewFileSkipCategory.PATCH_TOO_LARGE),
                skippedFile("dist/app.js", ReviewFileSkipCategory.PATH_EXCLUDED)
        ));

        assertThat(markdown).contains("### 审查覆盖");
        assertThat(markdown).contains("1 / 4");
        assertThat(markdown).contains(ReviewFileSkipCategory.PATCH_TOO_LARGE.description());
        assertThat(markdown).contains(ReviewFileSkipCategory.PATH_EXCLUDED.description());
        // 文件多的分组排在前面
        assertThat(markdown.indexOf(ReviewFileSkipCategory.PATCH_TOO_LARGE.description()))
                .isLessThan(markdown.indexOf(ReviewFileSkipCategory.PATH_EXCLUDED.description()));
        assertThat(markdown).contains("`src/Big.java`");
    }

    @Test
    void shouldFoldSkippedFilesBeyondConfiguredListingBudget() {
        ReviewProperties properties = new ReviewProperties();
        properties.setCoverageMaxListedFiles(3);
        ReviewReportFormatter formatter = formatter(properties);

        List<ReviewFile> files = new ArrayList<>();
        files.add(reviewedFile("src/Reviewed.java"));
        for (int i = 0; i < 8; i++) {
            files.add(skippedFile("src/Skipped" + i + ".java", ReviewFileSkipCategory.PATCH_TOO_LARGE));
        }

        String markdown = formatter.formatMarkdown(null, List.of(), files);

        assertThat(markdown).contains("<details><summary>展开 5 个未列出的文件</summary>");
        // 前 3 个逐个列出
        assertThat(markdown).contains("`src/Skipped0.java`");
        assertThat(markdown).contains("`src/Skipped2.java`");
    }

    @Test
    void shouldAllowDisablingCoverageSectionButKeepTheWarning() {
        ReviewProperties properties = new ReviewProperties();
        properties.setCoverageReportEnabled(false);
        ReviewReportFormatter formatter = formatter(properties);

        String markdown = formatter.formatMarkdown(null, List.of(), List.of(
                skippedFile("src/A.java", ReviewFileSkipCategory.PATCH_TOO_LARGE)
        ));

        assertThat(markdown).doesNotContain("### 审查覆盖");
        // 即使关闭了覆盖度区块，"未能审查"的告警与"不得输出未发现问题"的约束依然生效
        assertThat(markdown).contains("未能审查任何变更文件");
        assertThat(markdown).doesNotContain("未发现问题");
    }

    @Test
    void shouldRenderWithoutFileInformationForBackwardCompatibility() {
        ReviewReportFormatter formatter = formatter(new ReviewProperties());

        String markdown = formatter.formatMarkdown(null, List.of());

        assertThat(markdown).contains(MARKER);
        assertThat(markdown).contains("未发现问题");
        assertThat(markdown).doesNotContain("### 审查覆盖");
    }

    private ReviewFile reviewedFile(String path) {
        ReviewFile file = new ReviewFile();
        file.setFilePath(path);
        file.setSkipped(false);
        return file;
    }

    private ReviewFile skippedFile(String path, ReviewFileSkipCategory category) {
        ReviewFile file = new ReviewFile();
        file.setFilePath(path);
        file.setSkipped(true);
        file.setSkipReason(category.description());
        file.setSkipCategory(category.name());
        return file;
    }
}
