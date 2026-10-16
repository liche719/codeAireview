package com.codepilot.module.review.instruction;

import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R12：路径级指令的读取。
 *
 * <p>整个类围绕一条安全性质：<b>规则必须从 base 分支读，绝不从 head 读</b>。
 * 否则一个 PR 只要顺手改掉 {@code .codepilot/rules.md} 就能放宽对自己的审查——
 * 不需要绕过任何权限，提交一个文件就够了。</p>
 */
class PathInstructionProviderTest {

    private GithubClient githubClient;

    private ReviewProperties properties;

    private PathInstructionProvider provider;

    @BeforeEach
    void setUp() {
        githubClient = mock(GithubClient.class);
        properties = new ReviewProperties();
        properties.setPathInstructionsEnabled(true);
        provider = new PathInstructionProvider(githubClient, properties);
    }

    @Test
    void shouldNotCallGithubWhenDisabled() {
        properties.setPathInstructionsEnabled(false);

        assertThat(provider.load(task())).isEmpty();
        verifyNoInteractions(githubClient);
    }

    @Test
    void shouldLoadAndParseInstructionsFromTheBaseBranch() {
        givenBaseRef("main");
        when(githubClient.getFileContent("liche719", "codeAireview", ".codepilot/rules.md", "main"))
                .thenReturn("## **/*.sql\n迁移必须可回滚。\n");

        List<PathInstruction> instructions = provider.load(task());

        assertThat(instructions).hasSize(1);
        assertThat(instructions.getFirst().glob()).isEqualTo("**/*.sql");
        assertThat(instructions.getFirst().instruction()).isEqualTo("迁移必须可回滚。");
    }

    /**
     * 核心安全断言：传给内容接口的 ref 必须是 <b>base</b>，不能是 head。
     *
     * <p>这里把 base 与 head 设成不同的值，然后捕获实际使用的 ref——
     * 如果实现哪天改成读 head（例如为了"让本 PR 的规则改动立即生效"），这条断言会失败。</p>
     */
    @Test
    void mustReadFromTheBaseRefNeverFromTheHead() {
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setBaseRef("base-branch");
        detail.setHeadRef("feature-branch");
        detail.setHeadSha("head-sha-1");
        when(githubClient.getPullRequestDetail(anyString(), anyString(), anyInt())).thenReturn(detail);
        when(githubClient.getFileContent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("## *.java\n规则。\n");

        provider.load(task());

        ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
        verify(githubClient).getFileContent(anyString(), anyString(), anyString(), refCaptor.capture());
        assertThat(refCaptor.getValue()).isEqualTo("base-branch");
        assertThat(refCaptor.getValue())
                .isNotEqualTo("feature-branch")
                .isNotEqualTo("head-sha-1");
    }

    /** 规则文件路径可配，但默认必须是仓库内的约定位置。 */
    @Test
    void shouldUseTheConfiguredRuleFilePath() {
        assertThat(properties.getPathInstructionsFile()).isEqualTo(".codepilot/rules.md");
        givenBaseRef("main");
        properties.setPathInstructionsFile("docs/review-rules.md");
        when(githubClient.getFileContent(anyString(), anyString(), eq("docs/review-rules.md"), anyString()))
                .thenReturn("## *.java\n规则。\n");

        assertThat(provider.load(task())).hasSize(1);
        verify(githubClient).getFileContent("liche719", "codeAireview", "docs/review-rules.md", "main");
    }

    /** 旁路能力：读不到就当没有规则，绝不让审查失败。 */
    @Test
    void shouldDegradeToEmptyWhenTheRuleFileCannotBeRead() {
        givenBaseRef("main");
        when(githubClient.getFileContent(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("404 Not Found"));

        assertThatCode(() -> assertThat(provider.load(task())).isEmpty()).doesNotThrowAnyException();
    }

    @Test
    void shouldDegradeToEmptyWhenThePullRequestDetailFails() {
        when(githubClient.getPullRequestDetail(anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("rate limited"));

        assertThat(provider.load(task())).isEmpty();
        verify(githubClient, never()).getFileContent(anyString(), anyString(), anyString(), anyString());
    }

    /** 拿不到 base ref 时<b>不能退化成读 head</b>，只能放弃注入。 */
    @Test
    void shouldGiveUpWhenTheBaseRefIsMissing() {
        givenBaseRef("   ");

        assertThat(provider.load(task())).isEmpty();
        verify(githubClient, never()).getFileContent(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldReturnEmptyForEmptyRuleFile() {
        givenBaseRef("main");
        when(githubClient.getFileContent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("   ");

        assertThat(provider.load(task())).isEmpty();
    }

    @Test
    void shouldNotCallGithubWithoutTaskIdentity() {
        assertThat(provider.load(null)).isEmpty();

        ReviewTask withoutPr = task();
        withoutPr.setPrNumber(null);
        assertThat(provider.load(withoutPr)).isEmpty();
        verifyNoInteractions(githubClient);
    }

    private void givenBaseRef(String baseRef) {
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setBaseRef(baseRef);
        when(githubClient.getPullRequestDetail("liche719", "codeAireview", 12)).thenReturn(detail);
    }

    private static ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(7L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        task.setHeadSha("head-sha-1");
        return task;
    }
}
