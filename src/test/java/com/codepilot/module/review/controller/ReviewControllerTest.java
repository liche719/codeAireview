package com.codepilot.module.review.controller;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubLinkedIssue;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.service.ReviewFileService;
import com.codepilot.module.review.service.ReviewIssueService;
import com.codepilot.module.review.service.ReviewTaskService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewControllerTest {

    @Test
    void shouldListGithubLinkedIssuesForReviewTask() {
        TestContext context = new TestContext();
        ReviewTask task = reviewTask();
        when(context.reviewTaskService.getById(1L)).thenReturn(task);
        when(context.githubClient.listPullRequestLinkedIssues("liche719", "codeAireview", 123))
                .thenReturn(List.of(new GithubLinkedIssue(
                        "liche719",
                        "codeAireview",
                        42,
                        "Fix SQL risk",
                        "OPEN",
                        "https://github.com/liche719/codeAireview/issues/42",
                        "Issue body summary",
                        "GRAPHQL_CLOSING_ISSUES"
                )));

        var response = context.controller.listLinkedIssues(1L);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData())
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.getNumber()).isEqualTo(42);
                    assertThat(issue.getTitle()).isEqualTo("Fix SQL risk");
                });
    }

    @Test
    void shouldFailWhenReviewTaskDoesNotExistForLinkedIssues() {
        TestContext context = new TestContext();
        when(context.reviewTaskService.getById(1L)).thenReturn(null);

        assertThatThrownBy(() -> context.controller.listLinkedIssues(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("review task not found");
    }

    private ReviewTask reviewTask() {
        ReviewTask task = new ReviewTask();
        task.setId(1L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(123);
        return task;
    }

    private static class TestContext {

        private final ReviewTaskService reviewTaskService = mock(ReviewTaskService.class);

        private final ReviewFileService reviewFileService = mock(ReviewFileService.class);

        private final ReviewIssueService reviewIssueService = mock(ReviewIssueService.class);

        private final GithubClient githubClient = mock(GithubClient.class);

        private final ReviewController controller = new ReviewController(
                reviewTaskService,
                reviewFileService,
                reviewIssueService,
                githubClient
        );
    }
}
