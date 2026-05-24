package com.codepilot.module.command.policy;

import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.fix.NonRetryableFixTaskException;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixPullRequestWritePolicyTest {

    private final FixPullRequestWritePolicy policy = new FixPullRequestWritePolicy();

    @Test
    void shouldAllowWritableSameRepositoryBranch() {
        assertThatCode(() -> policy.assertWritableSameRepo(task(), detail(
                "liche719/codeAireview",
                "liche719/codeAireview",
                "feature/fix",
                "https://github.com/liche719/codeAireview.git"
        ))).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectForkHeadRepository() {
        assertThatThrownBy(() -> policy.assertWritableSameRepo(task(), detail(
                "attacker/codeAireview",
                "liche719/codeAireview",
                "feature/fix",
                "https://github.com/attacker/codeAireview.git"
        )))
                .isInstanceOf(NonRetryableFixTaskException.class)
                .hasMessage("仅允许对当前仓库中的分支执行修复。");
    }

    @Test
    void shouldRejectUnexpectedBaseRepository() {
        assertThatThrownBy(() -> policy.assertWritableSameRepo(task(), detail(
                "liche719/codeAireview",
                "other/codeAireview",
                "feature/fix",
                "https://github.com/liche719/codeAireview.git"
        )))
                .isInstanceOf(NonRetryableFixTaskException.class)
                .hasMessage("仅允许对当前仓库中的分支执行修复。");
    }

    @Test
    void shouldRejectMissingHeadRef() {
        assertThatThrownBy(() -> policy.assertWritableSameRepo(task(), detail(
                "liche719/codeAireview",
                "liche719/codeAireview",
                " ",
                "https://github.com/liche719/codeAireview.git"
        )))
                .isInstanceOf(NonRetryableFixTaskException.class)
                .hasMessage("PR head 分支信息不完整。");
    }

    @Test
    void shouldRejectMissingCloneUrl() {
        assertThatThrownBy(() -> policy.assertWritableSameRepo(task(), detail(
                "liche719/codeAireview",
                "liche719/codeAireview",
                "feature/fix",
                null
        )))
                .isInstanceOf(NonRetryableFixTaskException.class)
                .hasMessage("PR head 分支信息不完整。");
    }

    private PrCommandTask task() {
        PrCommandTask task = new PrCommandTask();
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        return task;
    }

    private GithubPullRequestDetail detail(
            String headRepoFullName,
            String baseRepoFullName,
            String headRef,
            String cloneUrl
    ) {
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setHeadRepoFullName(headRepoFullName);
        detail.setBaseRepoFullName(baseRepoFullName);
        detail.setHeadRef(headRef);
        detail.setHeadRepoCloneUrl(cloneUrl);
        return detail;
    }
}
