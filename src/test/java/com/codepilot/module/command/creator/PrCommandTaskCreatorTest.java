package com.codepilot.module.command.creator;

import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrCommandTaskCreatorTest {

    private final PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);

    private final PrCommandTaskCreator creator = new PrCommandTaskCreator(mapper);

    @Test
    void shouldReuseExistingFixTaskForSameCommentId() {
        PrCommandTask existingTask = existingFixTask("PENDING");
        when(mapper.selectList(any())).thenReturn(List.of(existingTask));

        PrCommandTask task = creator.createFixTask(payload());

        assertThat(task).isSameAs(existingTask);
        verify(mapper, never()).insert(any(PrCommandTask.class));
    }

    @Test
    void shouldReuseConcurrentlyCreatedFixTaskWhenUniqueIndexRejectsDuplicate() {
        PrCommandTask existingTask = existingFixTask("PENDING");
        when(mapper.selectList(any())).thenReturn(List.of(), List.of(existingTask));
        when(mapper.insert(any(PrCommandTask.class))).thenThrow(new DuplicateKeyException("duplicate"));

        PrCommandTask task = creator.createFixTask(payload());

        assertThat(task).isSameAs(existingTask);
    }

    @Test
    void shouldInsertNewPendingFixTaskWhenCommentHasNotBeenHandled() {
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.insert(any(PrCommandTask.class))).thenAnswer(invocation -> {
            PrCommandTask task = invocation.getArgument(0);
            task.setId(100L);
            return 1;
        });

        PrCommandTask task = creator.createFixTask(payload());

        assertThat(task.getId()).isEqualTo(100L);
        assertThat(task.getCommandType()).isEqualTo("FIX");
        assertThat(task.getStatus()).isEqualTo("PENDING");
        assertThat(task.getRepoOwner()).isEqualTo("liche719");
        assertThat(task.getRepoName()).isEqualTo("codeAireview");
        assertThat(task.getPrNumber()).isEqualTo(12);
        assertThat(task.getCommentId()).isEqualTo(99L);
        assertThat(task.getDryRun()).isTrue();
        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldPersistOnlyRedactedCommentBodyPreview() {
        when(mapper.selectList(any())).thenReturn(List.of());
        String secret = "ghp_123456789012345678901234567890123456";
        GitHubPullRequestWebhookPayload payload = payload();
        payload.setCommentBody("@x-pilotx fix token=" + secret);
        when(mapper.insert(any(PrCommandTask.class))).thenAnswer(invocation -> {
            PrCommandTask task = invocation.getArgument(0);
            task.setId(100L);
            return 1;
        });

        PrCommandTask task = creator.createFixTask(payload);

        assertThat(task.getCommentBody())
                .contains("[REDACTED]")
                .doesNotContain(secret);
    }

    private PrCommandTask existingFixTask(String status) {
        PrCommandTask task = new PrCommandTask();
        task.setId(1L);
        task.setCommandType("FIX");
        task.setStatus(status);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        task.setCommentId(99L);
        return task;
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
}
