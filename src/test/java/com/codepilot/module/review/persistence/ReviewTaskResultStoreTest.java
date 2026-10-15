package com.codepilot.module.review.persistence;

import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.processor.ReviewTaskProcessingResult;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.ReviewIssueService;
import com.codepilot.module.review.state.ReviewTaskStateManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReviewTaskResultStoreTest {

    @Test
    void shouldReplaceResultsAndCompleteTaskInOneBoundary() {
        ReviewFileService fileService = mock(ReviewFileService.class);
        ReviewIssueService issueService = mock(ReviewIssueService.class);
        ReviewTaskStateManager stateManager = mock(ReviewTaskStateManager.class);
        ReviewTaskResultStore store = new ReviewTaskResultStore(
                fileService, issueService, stateManager,
                mock(com.codepilot.module.review.memory.ReviewFindingMemoryService.class));
        ReviewTask task = new ReviewTask();
        task.setId(1L);
        ReviewFile file = new ReviewFile();
        ReviewIssue issue = new ReviewIssue();

        store.replaceAndComplete(task, new ReviewTaskProcessingResult(
                1, 1, "HIGH", List.of(file), List.of(issue)
        ));

        verify(fileService).remove(any());
        verify(issueService).remove(any());
        verify(fileService).saveBatch(List.of(file));
        verify(issueService).saveBatch(List.of(issue));
        verify(stateManager).markSuccess(task, 1, 1, "HIGH");
    }
}
