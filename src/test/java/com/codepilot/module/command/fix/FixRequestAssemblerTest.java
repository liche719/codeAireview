package com.codepilot.module.command.fix;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.git.GitPatchExecutionRequest;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.entity.ReviewIssue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FixRequestAssemblerTest {

    @Test
    void shouldBuildPromptInputWithIssuesAllowedPathsAndLimits() throws Exception {
        GithubCommandProperties properties = new GithubCommandProperties();
        properties.setFixMaxFiles(2);
        properties.setFixMaxChangedLines(10);
        FixRequestAssembler assembler = new FixRequestAssembler(properties, new ObjectMapper());

        FixPromptInput input = assembler.buildPromptInput(List.of(issue(" src/main/java/Demo.java ")));

        assertThat(input.issuesJson()).contains("\"filePath\":\" src/main/java/Demo.java \"");
        assertThat(input.allowedPaths()).containsExactly("src/main/java/Demo.java");
        assertThat(input.limits()).isEqualTo("maxFiles=2, maxChangedLines=10, output=unified diff only");
    }

    @Test
    void shouldBuildExecutionRequestWithSanitizedCommitMessageAndValidationSettings() {
        GithubCommandProperties properties = new GithubCommandProperties();
        properties.setFixValidationCommand("git diff --check");
        properties.setFixAllowedValidationCommands(List.of("git diff --check"));
        properties.setFixValidationInheritEnvironment(false);
        properties.setFixValidationTimeoutSeconds(12);
        FixRequestAssembler assembler = new FixRequestAssembler(properties, new ObjectMapper());
        ReflectionTestUtils.setField(assembler, "githubToken", "github-token");

        GitPatchExecutionRequest request = assembler.buildExecutionRequest(
                task(),
                prDetail(),
                "patch",
                " fix: demo\n\nwith extra spaces "
        );

        assertThat(request.getCloneUrl()).isEqualTo("https://github.com/liche719/codeAireview.git");
        assertThat(request.getBranch()).isEqualTo("feature/fix");
        assertThat(request.getPatch()).isEqualTo("patch");
        assertThat(request.getToken()).isEqualTo("github-token");
        assertThat(request.getCommitMessage()).isEqualTo("fix: demo with extra spaces");
        assertThat(request.getValidationCommand()).isEqualTo("git diff --check");
        assertThat(request.getAllowedValidationCommands()).containsExactly("git diff --check");
        assertThat(request.isInheritValidationEnvironment()).isFalse();
        assertThat(request.getValidationTimeoutSeconds()).isEqualTo(12);
        assertThat(request.isDryRun()).isTrue();
    }

    @Test
    void shouldFallbackCommitMessageWhenModelMessageIsBlank() {
        FixRequestAssembler assembler = new FixRequestAssembler(new GithubCommandProperties(), new ObjectMapper());

        GitPatchExecutionRequest request = assembler.buildExecutionRequest(task(), prDetail(), "patch", " ");

        assertThat(request.getCommitMessage()).isEqualTo("fix: CodePilot AI 自动修复");
    }

    private static PrCommandTask task() {
        PrCommandTask task = new PrCommandTask();
        task.setDryRun(true);
        return task;
    }

    private static GithubPullRequestDetail prDetail() {
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setHeadRef("feature/fix");
        detail.setHeadRepoCloneUrl("https://github.com/liche719/codeAireview.git");
        return detail;
    }

    private static ReviewIssue issue(String filePath) {
        ReviewIssue issue = new ReviewIssue();
        issue.setFilePath(filePath);
        issue.setLineNumber(2);
        issue.setIssueType("BUG_RISK");
        issue.setIssueTypeZh("Bug 风险");
        issue.setSeverity("HIGH");
        issue.setTitle("Demo bug");
        issue.setDescription("Fix demo bug");
        issue.setSuggestion("Update the line");
        return issue;
    }
}
