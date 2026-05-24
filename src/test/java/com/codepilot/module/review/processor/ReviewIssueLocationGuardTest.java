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
    void shouldClearLineNumberWhenIssueTargetsContextOrDeletedLine() {
        ReviewIssue contextLineIssue = issue("src/Demo.java", 10);
        ReviewIssue deletedLineIssue = issue("src/Demo.java", 12);

        guard.keepOnlyCommentableChangedLines("src/Demo.java", patch(), List.of(contextLineIssue, deletedLineIssue));

        assertThat(contextLineIssue.getLineNumber()).isNull();
        assertThat(deletedLineIssue.getLineNumber()).isNull();
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
