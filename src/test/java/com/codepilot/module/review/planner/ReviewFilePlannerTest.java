package com.codepilot.module.review.planner;

import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewFilePlannerTest {

    @Test
    void shouldMapReviewableChangedFile() {
        ReviewFilePlanner planner = new ReviewFilePlanner(new ReviewProperties());

        List<ReviewFile> reviewFiles = planner.plan(7L, List.of(changedFile("src/main/java/Demo.java", "modified", "+line")));

        assertThat(reviewFiles).hasSize(1);
        ReviewFile reviewFile = reviewFiles.getFirst();
        assertThat(reviewFile.getTaskId()).isEqualTo(7L);
        assertThat(reviewFile.getFilePath()).isEqualTo("src/main/java/Demo.java");
        assertThat(reviewFile.getChangeType()).isEqualTo("modified");
        assertThat(reviewFile.getSkipped()).isFalse();
        assertThat(reviewFile.getSkipReason()).isNull();
        assertThat(reviewFile.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldSkipEmptyPatchAndGeneratedPaths() {
        ReviewFilePlanner planner = new ReviewFilePlanner(new ReviewProperties());

        List<ReviewFile> reviewFiles = planner.plan(1L, List.of(
                changedFile("src/main/java/Binary.class", "modified", null),
                changedFile("dist/app.js", "modified", "+bundle"),
                changedFile("frontend/package-lock.json", "modified", "+lock")
        ));

        assertThat(reviewFiles).extracting(ReviewFile::getSkipped).containsExactly(true, true, true);
        assertThat(reviewFiles).extracting(ReviewFile::getSkipReason).containsExactly(
                "patch is empty or file is binary/too large",
                "file type or generated path skipped",
                "file type or generated path skipped"
        );
    }

    @Test
    void shouldApplyFileAndPatchLimitsInOrder() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMaxFilesPerTask(1);
        properties.setMaxPatchCharsPerFile(5);
        properties.setMaxTotalPatchChars(10);
        ReviewFilePlanner planner = new ReviewFilePlanner(properties);

        List<ReviewFile> reviewFiles = planner.plan(1L, List.of(
                changedFile("src/A.java", "modified", "12345"),
                changedFile("src/B.java", "modified", "123456"),
                changedFile("src/C.java", "modified", "1234")
        ));

        assertThat(reviewFiles).extracting(ReviewFile::getSkipped).containsExactly(false, true, true);
        assertThat(reviewFiles).extracting(ReviewFile::getSkipReason).containsExactly(
                null,
                "patch exceeds per-file review limit",
                "review file count limit exceeded"
        );
    }

    @Test
    void shouldApplyTotalPatchLimit() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMaxFilesPerTask(10);
        properties.setMaxPatchCharsPerFile(10);
        properties.setMaxTotalPatchChars(6);
        ReviewFilePlanner planner = new ReviewFilePlanner(properties);

        List<ReviewFile> reviewFiles = planner.plan(1L, List.of(
                changedFile("src/A.java", "modified", "1234"),
                changedFile("src/B.java", "modified", "1234")
        ));

        assertThat(reviewFiles).extracting(ReviewFile::getSkipped).containsExactly(false, true);
        assertThat(reviewFiles.get(1).getSkipReason()).isEqualTo("review total patch length limit exceeded");
    }

    private GithubChangedFile changedFile(String filename, String status, String patch) {
        GithubChangedFile changedFile = new GithubChangedFile();
        changedFile.setFilename(filename);
        changedFile.setStatus(status);
        changedFile.setPatch(patch);
        changedFile.setAdditions(1);
        changedFile.setDeletions(0);
        return changedFile;
    }
}
