package com.codepilot.module.command.tool;

import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubIssueDetail;
import com.codepilot.module.git.dto.GithubLinkedIssue;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GithubCommandChatToolTest {

    @Test
    void shouldFormatPullRequestDetailForAgentConsumption() {
        GithubClient githubClient = mock(GithubClient.class);
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setTitle("Add webhook support");
        detail.setHtmlUrl("https://github.com/liche719/codeAireview/pull/12");
        detail.setHeadRef("feature/chat");
        detail.setHeadSha("abc123");
        detail.setHeadRepoFullName("liche719/codeAireview");
        detail.setBaseRepoFullName("liche719/codeAireview");
        when(githubClient.getPullRequestDetail("liche719", "codeAireview", 12)).thenReturn(detail);

        GithubCommandChatTool tool = new GithubCommandChatTool(githubClient);
        String result = tool.getPullRequestDetail("liche719", "codeAireview", 12);

        assertThat(result).contains("PR title: Add webhook support");
        assertThat(result).contains("PR url: https://github.com/liche719/codeAireview/pull/12");
        assertThat(result).contains("PR branch: feature/chat");
        assertThat(result).contains("head sha: abc123");
    }

    @Test
    void shouldFormatPullRequestFilesAndTruncatePatch() {
        GithubClient githubClient = mock(GithubClient.class);
        GithubChangedFile file = new GithubChangedFile();
        file.setFilename("src/main/java/com/codepilot/Demo.java");
        file.setStatus("modified");
        file.setAdditions(2);
        file.setDeletions(1);
        file.setChanges(3);
        file.setPatch("""
                @@ -1 +1,2 @@
                -old
                +new
                +more
                """);
        when(githubClient.listPullRequestFiles("liche719", "codeAireview", 12)).thenReturn(List.of(file));

        GithubCommandChatTool tool = new GithubCommandChatTool(githubClient);
        String result = tool.listPullRequestFiles("liche719", "codeAireview", 12);

        assertThat(result).contains("src/main/java/com/codepilot/Demo.java");
        assertThat(result).contains("(modified, +2, -1, changes=3)");
        assertThat(result).contains("patch: @@ -1 +1,2 @@ -old +new +more");
    }

    @Test
    void shouldFormatPullRequestLinkedIssuesWithBodySummary() {
        GithubClient githubClient = mock(GithubClient.class);
        when(githubClient.listPullRequestLinkedIssues("liche719", "codeAireview", 12)).thenReturn(List.of(
                new GithubLinkedIssue(
                        "liche719",
                        "codeAireview",
                        42,
                        "Fix SQL injection",
                        "OPEN",
                        "https://github.com/liche719/codeAireview/issues/42",
                        "Fix unsafe SQL concatenation in user lookup and add validation for query inputs.",
                        "GRAPHQL_CLOSING_ISSUES"
                )
        ));

        GithubCommandChatTool tool = new GithubCommandChatTool(githubClient);
        String result = tool.listPullRequestLinkedIssues("liche719", "codeAireview", 12);

        assertThat(result).contains("#42 Fix SQL injection (OPEN)");
        assertThat(result).contains("summary: Fix unsafe SQL concatenation in user lookup");
        assertThat(result).contains("source: GRAPHQL_CLOSING_ISSUES");
    }

    @Test
    void shouldFormatIssueDetailForAgentConsumption() {
        GithubClient githubClient = mock(GithubClient.class);
        GithubIssueDetail detail = new GithubIssueDetail();
        detail.setTitle("Fix SQL injection");
        detail.setHtmlUrl("https://github.com/liche719/codeAireview/issues/42");
        detail.setState("open");
        detail.setBody("This issue describes unsafe SQL concatenation.");
        when(githubClient.getIssueDetail("liche719", "codeAireview", 42)).thenReturn(detail);

        GithubCommandChatTool tool = new GithubCommandChatTool(githubClient);
        String result = tool.getIssueDetail("liche719", "codeAireview", 42);

        assertThat(result).contains("issue title: Fix SQL injection");
        assertThat(result).contains("issue url: https://github.com/liche719/codeAireview/issues/42");
        assertThat(result).contains("issue state: open");
        assertThat(result).contains("issue summary: This issue describes unsafe SQL concatenation.");
    }
}
