package com.codepilot.module.tool.impl;

import com.codepilot.module.tool.dto.ToolCheckRequest;
import com.codepilot.module.tool.dto.ToolCheckResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R13：workflow 安全规则。
 *
 * <p>安全规则的误报代价特别高——一次误报就会让人开始忽略整类提示，
 * 所以下面每个危险模式都配了一个<b>安全反例</b>，而且反例都用真实项目里常见的写法。
 * 尤其是 {@code pull_request_target}：它本身完全合法，
 * 只有同时 checkout 了 PR head 才是注入向量。</p>
 */
class CiWorkflowRiskToolTest {

    private final CiWorkflowRiskTool tool = new CiWorkflowRiskTool();

    @Test
    void shouldOnlySupportWorkflowFiles() {
        assertThat(tool.supports(request(".github/workflows/ci.yml", "+x"))).isTrue();
        assertThat(tool.supports(request(".github/workflows/release.yaml", "+x"))).isTrue();
        assertThat(tool.supports(request("src/main/java/Demo.java", "+x"))).isFalse();
        assertThat(tool.supports(request(".github/dependabot.yml", "+x"))).isFalse();
        assertThat(tool.supports(null)).isFalse();
    }

    // ---------------- pull_request_target + head checkout ----------------

    @Test
    void shouldFlagPullRequestTargetWithHeadCheckout() {
        List<ToolCheckResult> results = tool.check(request(".github/workflows/ci.yml", """
                @@ -0,0 +1,9 @@
                +name: ci
                +on: pull_request_target
                +jobs:
                +  build:
                +    runs-on: ubuntu-latest
                +    steps:
                +      - uses: actions/checkout@v4
                +        with:
                +          ref: ${{ github.event.pull_request.head.sha }}
                """));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getSeverity()).isEqualTo("HIGH");
        assertThat(results.getFirst().getDescription()).contains("pull_request_target");
    }

    /** 反例：光用 pull_request_target 不 checkout head 是合法的，不能报。 */
    @Test
    void shouldNotFlagPullRequestTargetWithoutHeadCheckout() {
        List<ToolCheckResult> results = tool.check(request(".github/workflows/ci.yml", """
                @@ -0,0 +1,6 @@
                +name: labeler
                +on: pull_request_target
                +jobs:
                +  label:
                +    runs-on: ubuntu-latest
                +    steps:
                """));

        assertThat(results).isEmpty();
    }

    // ---------------- run: 里的用户可控输入 ----------------

    @Test
    void shouldFlagUntrustedInterpolationInsideRunBlock() {
        List<ToolCheckResult> results = tool.check(request(".github/workflows/ci.yml", """
                @@ -0,0 +1,8 @@
                +jobs:
                +  greet:
                +    runs-on: ubuntu-latest
                +    steps:
                +      - run: |
                +          echo "PR title: ${{ github.event.pull_request.title }}"
                +        shell: bash
                """));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getTitle()).contains("用户可控输入");
        assertThat(results.getFirst().getSuggestion()).contains("env:");
    }

    @Test
    void shouldFlagInlineRunInterpolation() {
        List<ToolCheckResult> results = tool.check(request(".github/workflows/ci.yml", """
                @@ -0,0 +1,3 @@
                +steps:
                +  - run: echo ${{ github.event.issue.title }}
                """));

        assertThat(results).hasSize(1);
    }

    /** 反例：把输入经 env 传入后，脚本里引用的是变量而不是表达式——安全。 */
    @Test
    void shouldNotFlagInterpolationOutsideRunBlocks() {
        List<ToolCheckResult> results = tool.check(request(".github/workflows/ci.yml", """
                @@ -0,0 +1,7 @@
                +jobs:
                +  build:
                +    if: ${{ github.event.pull_request.draft == false }}
                +    steps:
                +      - env:
                +          TITLE: ${{ github.event.pull_request.title }}
                +        run: echo "$TITLE"
                """));

        assertThat(results).isEmpty();
    }

    // ---------------- 权限 ----------------

    @Test
    void shouldFlagWriteAllPermissions() {
        List<ToolCheckResult> results = tool.check(request(".github/workflows/ci.yml", """
                @@ -0,0 +1,3 @@
                +name: ci
                +permissions: write-all
                +on: push
                """));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getTitle()).contains("write-all");
    }

    /** 反例：显式收敛到只读是推荐写法。 */
    @Test
    void shouldNotFlagScopedPermissions() {
        List<ToolCheckResult> results = tool.check(request(".github/workflows/ci.yml", """
                @@ -0,0 +1,4 @@
                +name: ci
                +permissions:
                +  contents: read
                +on: push
                """));

        assertThat(results).isEmpty();
    }

    // ---------------- secrets ----------------

    /** 反例：pull_request_target 是能拿到 secrets 的，不该按 fork 拿不到来报。 */
    @Test
    void shouldFlagSecretsOnlyOnPlainPullRequest() {
        List<ToolCheckResult> onPullRequest = tool.check(request(".github/workflows/ci.yml", """
                @@ -0,0 +1,6 @@
                +on: pull_request
                +jobs:
                +  deploy:
                +    steps:
                +      - run: ./deploy.sh
                +        env:
                +          TOKEN: ${{ secrets.DEPLOY_TOKEN }}
                """));
        assertThat(onPullRequest).hasSize(1);
        assertThat(onPullRequest.getFirst().getTitle()).contains("secrets");

        List<ToolCheckResult> onPullRequestTarget = tool.check(request(".github/workflows/ci.yml", """
                @@ -0,0 +1,6 @@
                +on: pull_request_target
                +jobs:
                +  deploy:
                +    steps:
                +      - run: ./deploy.sh
                +        env:
                +          TOKEN: ${{ secrets.DEPLOY_TOKEN }}
                """));
        assertThat(onPullRequestTarget).isEmpty();
    }

    // ---------------- 第三方 action ----------------

    @Test
    void shouldFlagThirdPartyActionWithoutPinnedSha() {
        List<ToolCheckResult> results = tool.check(request(".github/workflows/ci.yml", """
                @@ -0,0 +1,3 @@
                +steps:
                +  - uses: some-org/some-action@v1
                +  - run: echo done
                """));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getSeverity()).isEqualTo("LOW");
        assertThat(results.getFirst().getDescription()).contains("some-org/some-action@v1");
    }

    /** 反例：官方 action 用版本标签是社区常态；固定到 SHA 的第三方 action 也不算风险。 */
    @Test
    void shouldNotFlagFirstPartyOrShaPinnedActions() {
        List<ToolCheckResult> results = tool.check(request(".github/workflows/ci.yml", """
                @@ -0,0 +1,3 @@
                +steps:
                +  - uses: actions/checkout@v4
                +  - uses: some-org/some-action@8f4b7c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b
                """));

        assertThat(results).isEmpty();
    }

    /** 一个 workflow 引入多个未固定 action 时只报一条，不刷屏。 */
    @Test
    void shouldReportUnpinnedActionsAtMostOnce() {
        List<ToolCheckResult> results = tool.check(request(".github/workflows/ci.yml", """
                @@ -0,0 +1,4 @@
                +steps:
                +  - uses: a/b@v1
                +  - uses: c/d@main
                +  - uses: e/f@v2
                """));

        assertThat(results).hasSize(1);
    }

    // ---------------- 只判新增行 ----------------

    /**
     * 上下文行里的既有写法不该被报——那不是本 PR 引入的。
     * 让人为历史代码负责会迅速消耗掉对规则的信任。
     */
    @Test
    void shouldIgnorePreexistingPatternsOnContextLines() {
        List<ToolCheckResult> results = tool.check(request(".github/workflows/ci.yml", """
                @@ -1,4 +1,5 @@
                 name: ci
                 permissions: write-all
                 on: push
                +jobs:
                +  build:
                """));

        assertThat(results).isEmpty();
    }

    @Test
    void shouldTolerateEmptyInput() {
        assertThat(tool.check(null)).isEmpty();
        assertThat(tool.check(request(".github/workflows/ci.yml", null))).isEmpty();
        assertThat(tool.check(request(".github/workflows/ci.yml", ""))).isEmpty();
        assertThat(tool.check(request(".github/workflows/ci.yml", "not a patch"))).isEmpty();
    }

    private ToolCheckRequest request(String filePath, String patch) {
        return ToolCheckRequest.of(filePath, patch, List.of(filePath), "context");
    }
}
