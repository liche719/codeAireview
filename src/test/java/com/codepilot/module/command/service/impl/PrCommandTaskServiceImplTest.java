package com.codepilot.module.command.service.impl;

import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.entity.ReviewTask;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrCommandTaskServiceImplTest {

    @Test
    void shouldRequireSameHeadShaBeforeReusingReviewTaskForFix() {
        PrCommandTaskServiceImpl service = new PrCommandTaskServiceImpl(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        PrCommandTask commandTask = new PrCommandTask();
        commandTask.setHeadSha("abc123");
        ReviewTask reviewTask = new ReviewTask();
        reviewTask.setHeadSha("ABC123");

        assertThat(service.hasSameHeadSha(commandTask, reviewTask)).isTrue();

        reviewTask.setHeadSha("def456");
        assertThat(service.hasSameHeadSha(commandTask, reviewTask)).isFalse();

        reviewTask.setHeadSha(null);
        assertThat(service.hasSameHeadSha(commandTask, reviewTask)).isFalse();
    }

    @Test
    void shouldAllowPatchOnlyForSelectedIssueFile() {
        PrCommandTaskServiceImpl service = serviceWithDefaultProperties();
        String patch = """
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                --- a/src/main/java/Demo.java
                +++ b/src/main/java/Demo.java
                @@ -1 +1 @@
                -old
                +new
                """;

        var stats = service.validatePatchScope(patch, Set.of("src/main/java/Demo.java"));

        assertThat(stats.filesChanged()).isEqualTo(1);
        assertThat(stats.changedLines()).isEqualTo(2);
        assertThat(stats.paths()).containsExactly("src/main/java/Demo.java");
    }

    @Test
    void shouldRejectPatchForUnselectedFile() {
        PrCommandTaskServiceImpl service = serviceWithDefaultProperties();
        String patch = """
                diff --git a/src/main/java/Other.java b/src/main/java/Other.java
                --- a/src/main/java/Other.java
                +++ b/src/main/java/Other.java
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> service.validatePatchScope(patch, Set.of("src/main/java/Demo.java")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自动修复只能修改被选中问题所在文件");
    }

    @Test
    void shouldRejectPatchForSensitivePathEvenWhenSelected() {
        PrCommandTaskServiceImpl service = serviceWithDefaultProperties();
        String patch = """
                diff --git a/.github/workflows/deploy.yml b/.github/workflows/deploy.yml
                --- a/.github/workflows/deploy.yml
                +++ b/.github/workflows/deploy.yml
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> service.validatePatchScope(patch, Set.of(".github/workflows/deploy.yml")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自动修复不允许修改敏感路径");
    }

    @Test
    void shouldRejectPatchWithoutFilePathHeaders() {
        PrCommandTaskServiceImpl service = serviceWithDefaultProperties();
        String patch = """
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> service.validatePatchScope(patch, Set.of("src/main/java/Demo.java")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("补丁没有声明被修改的文件路径");
    }

    @Test
    void shouldRejectPatchThatExceedsChangedLineLimit() {
        GithubCommandProperties properties = new GithubCommandProperties();
        properties.setFixMaxChangedLines(1);
        PrCommandTaskServiceImpl service = serviceWithProperties(properties);
        String patch = """
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                --- a/src/main/java/Demo.java
                +++ b/src/main/java/Demo.java
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> service.validatePatchScope(patch, Set.of("src/main/java/Demo.java")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("补丁修改的行数过多");
    }

    @Test
    void shouldReuseExistingFixTaskForSameCommentId() {
        TestContext context = new TestContext();
        PrCommandTask existingTask = existingFixTask(99L, "PENDING");
        when(context.mapper.selectList(any())).thenReturn(List.of(existingTask));

        PrCommandTask task = context.service.createFixTask(payload());

        assertThat(task).isSameAs(existingTask);
        verify(context.mapper, never()).insert(any(PrCommandTask.class));
    }

    @Test
    void shouldReuseConcurrentlyCreatedFixTaskWhenUniqueIndexRejectsDuplicate() {
        TestContext context = new TestContext();
        PrCommandTask existingTask = existingFixTask(99L, "PENDING");
        when(context.mapper.selectList(any())).thenReturn(List.of(), List.of(existingTask));
        when(context.mapper.insert(any(PrCommandTask.class))).thenThrow(new DuplicateKeyException("duplicate"));

        PrCommandTask task = context.service.createFixTask(payload());

        assertThat(task).isSameAs(existingTask);
    }

    @Test
    void shouldInsertNewFixTaskWhenCommentHasNotBeenHandled() {
        TestContext context = new TestContext();
        when(context.mapper.selectList(any())).thenReturn(List.of());
        when(context.mapper.insert(any(PrCommandTask.class))).thenAnswer(invocation -> {
            PrCommandTask task = invocation.getArgument(0);
            task.setId(100L);
            return 1;
        });

        PrCommandTask task = context.service.createFixTask(payload());

        assertThat(task.getId()).isEqualTo(100L);
        assertThat(task.getCommandType()).isEqualTo("FIX");
        assertThat(task.getStatus()).isEqualTo("PENDING");
        assertThat(task.getCommentId()).isEqualTo(99L);
    }

    @Test
    void shouldSkipTerminalFixTaskMessage() {
        TestContext context = new TestContext();
        when(context.mapper.selectById(1L)).thenReturn(existingFixTask(99L, "SUCCESS"));

        context.service.processFixTask(1L);

        verify(context.mapper, never()).updateById(any(PrCommandTask.class));
        verify(context.githubClient, never()).getPullRequestDetail(any(), any(), any());
    }

    private PrCommandTaskServiceImpl serviceWithDefaultProperties() {
        return serviceWithProperties(new GithubCommandProperties());
    }

    private PrCommandTaskServiceImpl serviceWithProperties(GithubCommandProperties properties) {
        return new PrCommandTaskServiceImpl(
                properties,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private GitHubPullRequestWebhookPayload payload() {
        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setOwner("liche719");
        payload.setRepo("codeAireview");
        payload.setPullNumber(12);
        payload.setPrUrl("https://github.com/liche719/codeAireview/pull/12");
        payload.setTitle("Fix SQL risk");
        payload.setHeadSha("abc123");
        payload.setCommentId(99L);
        payload.setCommentBody("@x-pilotx fix");
        payload.setCommentUserLogin("reviewer");
        payload.setDryRun(true);
        return payload;
    }

    private PrCommandTask existingFixTask(Long commentId, String status) {
        PrCommandTask task = new PrCommandTask();
        task.setId(1L);
        task.setCommandType("FIX");
        task.setStatus(status);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        task.setCommentId(commentId);
        return task;
    }

    private static class TestContext {

        private final PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);

        private final GithubClient githubClient = mock(GithubClient.class);

        private final PrCommandTaskServiceImpl service;

        private TestContext() {
            service = new PrCommandTaskServiceImpl(
                    new GithubCommandProperties(),
                    githubClient,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            ReflectionTestUtils.setField(service, "baseMapper", mapper);
        }
    }
}
