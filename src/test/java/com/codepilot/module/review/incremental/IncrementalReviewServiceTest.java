package com.codepilot.module.review.incremental;

import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewFileMapper;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 增量审查（2026-10-30）。
 *
 * <p>规则很窄：**同一 PR 的上一次成功审查里，patch 完全相同的文件才跳过**。
 * 跳错（比如把改过的文件也跳过）就是漏审——比慢严重得多。
 */
class IncrementalReviewServiceTest {

    private static ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(22L);
        task.setRepoOwner("owner");
        task.setRepoName("repo");
        task.setPrNumber(2);
        task.setHeadSha("new-sha");
        return task;
    }

    private static ReviewFile file(String path, String patch) {
        ReviewFile file = new ReviewFile();
        file.setFilePath(path);
        file.setPatch(patch);
        return file;
    }

    private IncrementalReviewService service(String previousHeadSha) {
        ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
        ReviewFileMapper fileMapper = mock(ReviewFileMapper.class);
        ReviewTask previous = new ReviewTask();
        previous.setId(10L);
        previous.setHeadSha(previousHeadSha);
        when(taskMapper.selectList(any())).thenReturn(List.of(previous));
        when(fileMapper.selectList(any())).thenReturn(List.of(
                file("src/A.java", "patch-a"),
                file("src/B.java", "patch-b-old")));
        return new IncrementalReviewService(taskMapper, fileMapper);
    }

    @Test
    @DisplayName("patch 没变的文件跳过，变了的照常审")
    void skipsOnlyUnchangedFiles() {
        List<ReviewFile> files = List.of(
                file("src/A.java", "patch-a"),
                file("src/B.java", "patch-b-new"));
        int skipped = service("old-sha").markUnchangedAsSkipped(task(), files);

        assertThat(skipped).isEqualTo(1);
        assertThat(files.get(0).getSkipped()).isTrue();
        assertThat(files.get(0).getSkipCategory()).isEqualTo("UNCHANGED");
        assertThat(files.get(1).getSkipped()).isNull();
    }

    @Test
    @DisplayName("没有历史审查时全量审，不跳过任何文件")
    void noHistoryMeansFullReview() {
        IncrementalReviewService service = new IncrementalReviewService(
                mock(ReviewTaskMapper.class), mock(ReviewFileMapper.class));
        List<ReviewFile> files = List.of(file("src/A.java", "patch-a"));

        assertThat(service.markUnchangedAsSkipped(task(), files)).isZero();
        assertThat(files.get(0).getSkipped()).isNull();
    }

    @Test
    @DisplayName("空输入安全")
    void emptyInputIsSafe() {
        assertThat(service("old-sha").markUnchangedAsSkipped(null, List.of())).isZero();
        assertThat(service("old-sha").markUnchangedAsSkipped(task(), null)).isZero();
    }
}
