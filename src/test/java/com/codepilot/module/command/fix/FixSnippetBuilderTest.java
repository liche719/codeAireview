package com.codepilot.module.command.fix;

import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.review.entity.ReviewIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixSnippetBuilderTest {

    @Test
    void shouldBuildSnippetAroundIssueLineAndSkipDuplicateIssueLocation() {
        TestContext context = new TestContext();
        when(context.githubClient.getFileContent("liche719", "codeAireview", "src/main/java/Demo.java", "head-sha"))
                .thenReturn("line1\nline2\nline3\nline4");

        String result = context.builder.build(
                task(),
                "head-sha",
                List.of(issue("src/main/java/Demo.java", 2), issue("src/main/java/Demo.java", 2))
        );

        assertThat(result)
                .contains("文件：src/main/java/Demo.java 行 1-4")
                .contains("2: line2");
        verify(context.githubClient).getFileContent("liche719", "codeAireview", "src/main/java/Demo.java", "head-sha");
    }

    @Test
    void shouldSkipInvalidIssueLocations() {
        TestContext context = new TestContext();

        String result = context.builder.build(task(), "head-sha", List.of(issue(null, 2), issue("src/Demo.java", null)));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldRecordSnippetLoadFailureWithRedactedDetail() {
        TestContext context = new TestContext();
        when(context.githubClient.getFileContent("liche719", "codeAireview", "src/main/java/Demo.java", "head-sha"))
                .thenThrow(new IllegalStateException("token=ghp_123456789012345678901234567890123456 denied"));

        String result = context.builder.build(task(), "head-sha", List.of(issue("src/main/java/Demo.java", 2)));

        assertThat(result).isEmpty();
        verify(context.commandTaskLogService).record(eq(1L), eq("SNIPPET"), eq(false),
                eq("Failed to load snippet for src/main/java/Demo.java"),
                eq("token=[REDACTED] denied"));
    }

    private static PrCommandTask task() {
        PrCommandTask task = new PrCommandTask();
        task.setId(1L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        return task;
    }

    private static ReviewIssue issue(String filePath, Integer lineNumber) {
        ReviewIssue issue = new ReviewIssue();
        issue.setFilePath(filePath);
        issue.setLineNumber(lineNumber);
        return issue;
    }

    private static class TestContext {

        private final GithubClient githubClient = mock(GithubClient.class);

        private final PrCommandTaskLogService commandTaskLogService = mock(PrCommandTaskLogService.class);

        private final FixSnippetBuilder builder = new FixSnippetBuilder(githubClient, commandTaskLogService);
    }
}
