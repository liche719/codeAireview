package com.codepilot.module.command.fix;

import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.git.client.GithubClient;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FixResultCommenterTest {

    private final GithubClient githubClient = mock(GithubClient.class);

    private final FixResultCommenter commenter = new FixResultCommenter(githubClient);

    @Test
    void shouldSanitizeDryRunComment() {
        String secret = "ghp_123456789012345678901234567890123456";
        String patch = """
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                +String token = "%s";
                +String fence = "```";
                """.formatted(secret);

        commenter.dryRunCompleted(task(), new FixPatchScopeValidationResult(1, 2, Set.of("src/main/java/Demo.java")),
                "Fix *demo* <script>", patch);

        verify(githubClient).createPullRequestComment(
                eq("liche719"),
                eq("codeAireview"),
                eq(12),
                org.mockito.ArgumentMatchers.argThat(body ->
                        body.contains("预演完成")
                                && body.contains("\\*demo\\*")
                                && body.contains("&lt;script&gt;")
                                && body.contains("[REDACTED]")
                                && !body.contains(secret)
                                && body.contains("`\u200b``")
                )
        );
    }

    @Test
    void shouldRedactFailureMessageBeforeCommenting() {
        String secret = "ghp_123456789012345678901234567890123456";

        commenter.fixFailed(task(), "validation failed token=" + secret);

        verify(githubClient).createPullRequestComment(
                eq("liche719"),
                eq("codeAireview"),
                eq(12),
                org.mockito.ArgumentMatchers.argThat(body ->
                        body.contains("[REDACTED]") && !body.contains(secret)
                )
        );
    }

    @Test
    void shouldNotFailTaskWhenGithubCommentFails() {
        doThrow(new IllegalStateException("github token=ghp_123456789012345678901234567890123456"))
                .when(githubClient)
                .createPullRequestComment(eq("liche719"), eq("codeAireview"), eq(12), org.mockito.ArgumentMatchers.anyString());

        assertThatCode(() -> commenter.patchNotGenerated(task()))
                .doesNotThrowAnyException();
    }

    private PrCommandTask task() {
        PrCommandTask task = new PrCommandTask();
        task.setId(1L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        task.setCommitSha("abc123");
        return task;
    }
}
