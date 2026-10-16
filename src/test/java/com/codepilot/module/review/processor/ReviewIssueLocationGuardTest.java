package com.codepilot.module.review.processor;

import com.codepilot.module.review.diff.DiffLineMapper;
import com.codepilot.module.review.entity.ReviewIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewIssueLocationGuardTest {

    private final ReviewIssueLocationGuard guard = new ReviewIssueLocationGuard(new DiffLineMapper());

    @Test
    void shouldKeepLineNumberWhenIssueTargetsAddedLine() {
        ReviewIssue issue = issue("src/Demo.java", 11);

        guard.keepOnlyCommentableChangedLines("src/Demo.java", patch(), List.of(issue));

        assertThat(issue.getLineNumber()).isEqualTo(11);
    }

    @Test
    void shouldKeepLineNumberWhenIssueTargetsContextOrDeletedLine() {
        ReviewIssue contextLineIssue = issue("src/Demo.java", 10);
        ReviewIssue deletedLineIssue = issue("src/Demo.java", 12);

        guard.keepOnlyCommentableChangedLines("src/Demo.java", patch(), List.of(contextLineIssue, deletedLineIssue));

        assertThat(contextLineIssue.getLineNumber()).isEqualTo(10);
        assertThat(deletedLineIssue.getLineNumber()).isEqualTo(12);
    }

    @Test
    void shouldClearLineNumberWhenPatchOrFileContextIsNotTrustworthy() {
        ReviewIssue missingPatchIssue = issue("src/Demo.java", 11);
        ReviewIssue wrongFileIssue = issue("src/Other.java", 11);

        guard.keepOnlyCommentableChangedLines("src/Demo.java", null, List.of(missingPatchIssue));
        guard.keepOnlyCommentableChangedLines("src/Demo.java", patch(), List.of(wrongFileIssue));

        assertThat(missingPatchIssue.getLineNumber()).isNull();
        assertThat(wrongFileIssue.getLineNumber()).isNull();
    }

    @Test
    void shouldKeepFileLevelIssueWithoutLineNumber() {
        ReviewIssue issue = issue("src/Demo.java", null);

        guard.keepOnlyCommentableChangedLines("src/Demo.java", patch(), List.of(issue));

        assertThat(issue.getLineNumber()).isNull();
    }

    /**
     * R16：把"删除行与上下文行也会被保留"钉成显式断言。
     *
     * <p>这不是重复上一条测试——它针对的是<b>文案</b>曾经说错的那件事：
     * 旧 reason 写的是 {@code "line is not a commentable added diff line"}，
     * 只提"新增行"，读起来像"只有新增行可评论"。这条断言把"删除侧 LEFT 行不会被清空"
     * 单独固化下来，于是那句文案不可能再被改回去。</p>
     */
    @Test
    void shouldKeepLineNumberOnTheDeletedSideBecauseBothSidesAreCommentable() {
        // patch 的 @@ -10,4 +10,5 @@ 里，第 12 行是被删除的那一行。
        ReviewIssue deletedLineIssue = issue("src/Demo.java", 12);

        guard.keepOnlyCommentableChangedLines("src/Demo.java", patch(), List.of(deletedLineIssue));

        assertThat(deletedLineIssue.getLineNumber())
                .as("deleted-side lines are commentable too, so they must not be cleared")
                .isEqualTo(12);
        // 反向：真正不在 diff 里的行仍然会被清空。
        ReviewIssue outsideDiffIssue = issue("src/Demo.java", 999);
        guard.keepOnlyCommentableChangedLines("src/Demo.java", patch(), List.of(outsideDiffIssue));
        assertThat(outsideDiffIssue.getLineNumber()).isNull();
    }

    private ReviewIssue issue(String filePath, Integer lineNumber) {
        ReviewIssue issue = new ReviewIssue();
        issue.setFilePath(filePath);
        issue.setLineNumber(lineNumber);
        issue.setIssueType("BUG_RISK");
        return issue;
    }

    private String patch() {
        return """
                @@ -10,4 +10,5 @@
                 public void run() {
                +    String sql = "select * from user where name = '" + name + "'";
                -    String oldSql = sql;
                     System.out.println(sql);
                 }
                """;
    }
}
