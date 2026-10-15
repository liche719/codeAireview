# 四套成熟「确定性」代码审查系统与 GitHub PR 的集成方式调研

> 目的：为「Java Spring Boot + 确定性规则先行、LLM 后置」的 AI PR Review Bot 提供设计依据。
>
> 调研对象：**reviewdog**、**Danger / Danger JS**、**SonarQube / SonarCloud**、**Semgrep**。
>
> 本报告只写能给出处的内容。凡未能证实的一律标注 `无法证实`，不做推测性补全。

## 0. 调研方法与可信度约定

本环境的网络限制与被突破的路径（影响可信度判读，故先说明）：

| 目标 | 状态 | 本报告采用的替代路径 |
| --- | --- | --- |
| `github.com` / `raw.githubusercontent.com` | **被封锁**（解析到非公网 IP，fetch 失败） | 未使用 |
| `docs.github.com` | 只能取到导航壳 | 未使用；GitHub 行为一律以被调研项目自己的描述为准 |
| `deepwiki.com` | HTTP 429（Vercel 人机校验） | 未使用 |
| reviewdog 官方 README / 源码 | — | 项目自建的 GitLab 镜像 `gitlab.com/reviewdog/reviewdog/-/raw/master/...`（内容与 GitHub 仓库同源），以及 `pkg.go.dev` 渲染的 README |
| Danger JS 源码 | — | npm 发布产物 `unpkg.com/danger@12.3.3/distribution/...` |
| `semgrep.dev/docs` | 301 到 `docs.semgrep.dev` | 直接访问 `docs.semgrep.dev`，**在 URL 后加 `.md` 可得干净 Markdown** |
| `docs.sonarsource.com` | 可达 | 同样是 GitBook，**URL 后加 `.md` 可得纯 Markdown**；并使用了其文档内公开的 `?ask=&goal=` 问答端点 |

**标签含义**

- `官方文档`：项目自己的文档站/README 正文。
- `官方源码`：项目自己发布或镜像的源代码/构建产物（比文档更能反映真实行为）。
- `搜索引擎摘要`：仅来自搜索片段，未取得正文。
- `无法证实`：未能取得可信出处。

---

## 1. reviewdog

**总体可信度：`官方文档` + `官方源码`（高）**

- 官方 README（GitLab 镜像）：<https://gitlab.com/reviewdog/reviewdog/-/raw/master/README.md>
- 官方 README（pkg.go.dev 渲染同一份）：<https://pkg.go.dev/github.com/reviewdog/reviewdog>
- 官方源码：<https://gitlab.com/reviewdog/reviewdog/-/raw/master/service/github/github.go>

### 1.1 评论模式（Reporter）——本报告重点

reviewdog 的定位是一句话：把**任意 linter 的 stdout** 按 diff 过滤后投递到代码托管平台。

> “reviewdog provides a way to post review comments to code hosting services, such as GitHub, automatically by integrating with any linter tools with ease. It uses an output of lint tools and posts them as a comment **if findings are in the diff of patches to review**.”
> —— README

**它如何在「check/注解」与「真正的 PR 评论」之间选择？答案是：由用户用 `-reporter` 显式指定，没有自动切换**（除了一处降级，见 1.6）。所有官方 reporter：

| `-reporter` | 投递形态 | 关键事实（均出自 README） |
| --- | --- | --- |
| `local` | 只打到 stdout（**默认值**） | “reviewdog can find newly introduced findings by filtering linter results using diff.” 用 `-diff="git diff FETCH_HEAD"` 喂 diff |
| `github-pr-check` | **GitHub Checks**（check run + 行内 annotation，带 commit status） | 走 Checks API；因 Check API 只对 GitHub App / GitHub Actions 开放，故有两条路：① Actions 里用 `secrets.GITHUB_TOKEN`；② 装 reviewdog GitHub App 并设 `REVIEWDOG_TOKEN`，CLI 只发请求，**由 reviewdog 的服务器代为创建 check** |
| `github-check` | 同 `github-pr-check`，但**不限 PR** | “It's basically the same as `-reporter=github-pr-check` except it works not only for Pull Request but also for commit.” 只有它能跑 on: push，因此 README 用它做 badge |
| `github-pr-review` | **真正的 PR review comment**（Discussion 线程） | 用个人 API Token（`REVIEWDOG_GITHUB_API_TOKEN`，`repo` 或 `public_repo` scope）；支持 GitHub Enterprise（`GITHUB_API`）；**代码建议（code suggestion）只有它支持** |
| `github-annotations` | 不调 API 发评论，而是把 **GitHub Actions 注解格式打到 stdout** | 形如 `::error line=11,col=41,file=app/index.md::[vale] reported by reviewdog 🐶...`；“This reporter requires a valid GitHub API token to generate a diff, but will not use the token to report errors.” |
| `github-pr-annotations` | 同上，但**仅对 PR 生效** | “Same as `github-annotations` but only works for Pull Requests.” |
| `gitlab-mr-discussion` / `gitlab-mr-commit` / `gerrit-change-review` / `bitbucket-code-report` / `gitea-pr-review` | 其它平台 | 非本报告重点 |

**`github-pr-check` 的取舍（官方明确承认的运维风险）**

> “As described above, github-pr-check reporter with Option 2 depends on reviewdog GitHub App server. The server is running with haya14busa's pocket money for now and I may break things, so I cannot ensure that the server is running 24h and 365 days.”
> “You can use github-pr-review reporter or use run reviewdog under GitHub Actions if you don't want to depend on reviewdog server.”

即：**要 check run 又不想要第三方服务器 → 必须在 GitHub Actions 内跑**。这是 `github-pr-check`(Option 2) 与 `github-pr-review` 的核心权衡。

**`level` → GitHub 状态 的显式映射**（`-level` 或 `.reviewdog.yml` 的 `level`，默认 `error`）：

| Level | GitHub Status |
| --- | --- |
| `info` | neutral |
| `warning` | neutral |
| `error` | failure |

**能力差异表（官方给出，说明 reporter 之间不是等价可选）**

代码建议支持（README “Code Suggestions Support Table”）：`github-pr-review` = OK；`gitlab-mr-discussion` = OK；`local`/`github-check`/`github-pr-check`/`github-annotations`/`github-pr-annotations`/`gitlab-mr-commit`/`gerrit-change-review`/`bitbucket-code-report`/`gitea-pr-review` = **NO**。

### 1.2 Diff 范围与「只报新增」

由 `-filter-mode` 控制（README “Filter mode”）：

| `-filter-mode` | 语义（官方原文） |
| --- | --- |
| `added`（**默认**） | “Filter results by added/modified lines.” |
| `diff_context` | “Filter results by diff context. i.e. changed lines +-N lines (N=3 for example).” |
| `file` | “Filter results by added/modified file. i.e. reviewdog will report results as long as they are in added/modified file even if the results are not in actual diff.” |
| `nofilter` | “Do not filter any results. Useful for posting results as comments as much as possible and check other results in console at the same time.” |

**存量问题怎么办**：默认 `added` 下，不在本 PR 新增/修改行上的结果**根本不会被投递**（不是「标记为旧」，而是直接不报）。若要连存量一起看，用 `nofilter`，此时官方建议配合 `-fail-on-error`：README 明写 “`-fail-on-error` also works with any filter-mode and can catch all results from any linters with `nofilter` mode.”

**不是所有 reporter 都能实现所有 filter mode**（README “Filter Mode Support Table”），且官方给了降级规则，其中两条对本项目最有用：

> “`github-pr-review` reporter uses [GitHub Review API] but this API don't support posting comments outside diff context, so reviewdog will use [Check annotation] as fallback to post those comments.”
> 脚注 [1]：“Report results that are outside the diff file with Check annotation as fallback if it's running in GitHub actions instead of Review API (comments). **All results will be reported to console as well.**”

`bitbucket-code-report` 全表 `NO [4]`：“Not implemented at the moment”，仅支持 `no-filter`。

### 1.3 抑制模型：reviewdog 自己没有 ignore 语法

reviewdog **不提供** 类似 `// nolint` 的抑制注解，也不提供 baseline 文件。它做的是「**diff 过滤即抑制**」：被抑制 = 不在 diff 内。真正的行内抑制由**上游 linter** 负责（例如 ESLint 的 `eslint-disable`），reviewdog 只透传。

状态的存放位置见 1.4——**全部存在 GitHub 的评论正文里，reviewdog 自身无状态**。

### 1.4 重复评论避免（源码级证据，这是最有价值的部分）

`service/github/github.go` 揭示了一套干净的无状态去重方案：

1. 每轮先 `setPostedComment()` —— 分页拉取该 PR 的**全部** review comment：

```go
func (g *PullRequest) setPostedComment(ctx context.Context) error {
	g.postedcs = make(commentutil.PostedComments)
	g.outdatedComments = make(map[string]*github.PullRequestComment)
	...
	for _, c := range cs {
		if id := c.GetInReplyTo(); id != 0 { g.prCommentWithReply[id] = true }
		if meta := serviceutil.ExtractMetaComment(c.GetBody()); meta != nil {
			g.postedcs.AddPostedComment(c.GetPath(), c.GetLine(), meta.GetFingerprint())
			if meta.SourceName == g.toolName { g.outdatedComments[meta.GetFingerprint()] = c }
		}
	}
}
```

2. **指纹写在评论正文里**。每条评论的 body 末尾由 `serviceutil.BuildMetaComment(fprint, toolName)` 追加一段元数据注释；下一轮通过 `ExtractMetaComment` 读回。指纹由 `serviceutil.Fingerprint(c.Result.Diagnostic)` 从诊断本身算出。

3. 投递时按 `(path, line, fingerprint)` 去重：

```go
if g.postedcs.IsPosted(c, githubCommentLine(c), fprint) {
	// it's already posted. Mark the comment as non-outdated and skip it.
	delete(g.outdatedComments, fprint)
	continue
}
```

4. **过期评论会被删除**（这是 reviewdog 少有人提的行为）：

```go
for _, c := range g.outdatedComments {
	if ok := g.prCommentWithReply[c.GetID()]; ok { continue } // 有人回复则保留
	if _, err := g.cli.PullRequests.DeleteComment(ctx, g.owner, g.repo, c.GetID()); err != nil { ... }
}
```

`outdatedComments` 的构造条件是 `meta.SourceName == g.toolName`——**只删自己这个 tool 的评论**，多工具共存互不干扰。

**结论**：去重状态**存放在 GitHub 服务器上（评论正文内嵌元数据）**，服务端无需数据库，重启不丢。

### 1.5 更新 vs 新建

**每次运行都新建一个 GitHub Review，但会跳过已存在的评论**：

```go
review := &github.PullRequestReviewRequest{ CommitID: &g.sha, Event: new("COMMENT"), Comments: reviewComments, Body: new(g.remainingCommentsSummary(...)) }
_, _, err := g.cli.PullRequests.CreateReview(ctx, g.owner, g.repo, g.pr, review)
```

识别「自己上一条评论」靠的是**正文里的 meta 指纹（fingerprint + toolName）**，而不是评论 ID、也不是 commit 绑定。`CommitID: g.sha` 只是把 review 关联到当前 head commit。

**限流保护**（源码常量）：

```go
const maxCommentsPerRequest = 30
const maxFileComments = 10
```

超出 30 条的行内评论不会丢，而是塞进 review 的 `Body` 里，用 `<details>` 折叠：`"Remaining comments which cannot be posted as a review comment to avoid GitHub Rate Limit"`。源码注释解释了原因：

> “Only posts maxCommentsPerRequest comments per 1 request to avoid spammy review comments. An example GitHub error if we don't limit the # of review comments. > 403 You have triggered an abuse detection mechanism...”

### 1.6 门禁

README “Exit codes”：

> “By default (`-fail-level=none`) reviewdog will return `0` as exit code even if it finds errors. reviewdog will exit with code 1 with `-fail-level=[any,info,warning,error]` flag if it finds at least 1 issue with severity greater than or equal to the given level.”

- **默认不失败**（`-fail-level=none`），要失败必须显式指定阈值。
- `-level` 决定**默认上报级别**，同时决定 `github-pr-check` 的 check 状态（见 1.1）。
- 另有 `-fail-on-error`（README 在 Filter mode 一节中出现）。
- 还有一条**权限降级路径**（Actions 中 fork PR 的 `GITHUB_TOKEN` 只读）：

> “`GITHUB_TOKEN` for Pull Requests from a forked repository doesn't have write access to Check API nor Review API... Instead, reviewdog uses Logging commands of GitHub Actions to post results as annotations similar to `github-pr-check` reporter. Note that there is a limitation for annotations created by logging commands, such as max # of annotations per run.”

源码里对应 `isPermissionError` → `FALLBACK` 标签，`fallbackToLog = true` 后全部走 `logWriter`。

### 1.7 非行级发现（重要）

源码里明确区分三档，本项目可直接借鉴：

1. **在 diff 文件内、且在 diff 上下文内** → 普通行内 review comment（可带 code suggestion）。
2. **在 diff 文件内、但在 diff 上下文之外** → 走**文件级评论**：

```go
func buildPullRequestFileComment(c *reviewdog.Comment, body string, sha string) github.CreatePullRequestCommentRequest {
	return github.CreatePullRequestCommentRequest{
		Path: ..., Side: new("RIGHT"), Body: body, CommitID: sha,
		SubjectType: new("file"),   // ← 文件级
	}
}
```
并有独立配额 `maxFileComments = 10`。
3. **不在 diff 文件内** → GitHub Review API 无法承载；在 GitHub Actions 中降级为 Actions 注解，否则丢弃（但控制台仍打印全部）。

另外，`github-check` reporter 本身就能上报 PR diff 之外的结果。

---

## 2. Danger / Danger JS

**总体可信度：`官方文档` + `官方源码`（高）**

- 官方文档：<https://danger.systems/js/>、<https://danger.systems/js/reference.html>、<https://danger.systems/js/guides/faq.html>
- 官方源码（npm 12.3.3 发布产物）：`unpkg.com/danger@12.3.3/distribution/...` 下的 `platforms/github/GitHubAPI.js`、`runner/Executor.js`、`runner/templates/githubIssueTemplate.js`、`commands/ci/runner.js`、`commands/utils/sharedDangerfileArgs.js`

### 2.1 评论模式

Danger 的心智模型是**「一条汇总评论（表格）+ 可选行内评论」**，不是逐条 finding 评论。

Reference 给出的四个通信原语，签名本身就说明了模式：

```ts
fail(message: MarkdownString, file?: string, line?: number) => void      // Fails a build
markdown(message: MarkdownString, file?: string, line?: number) => void  // Adds raw markdown into the Danger comment, under the table
message(message: MarkdownString, file?: string, line?: number) => void   // Adds a message to the Danger table
warn(message: MarkdownString, file?: string, line?: number) => void      // Highlights low-priority issues, but does not fail the build
```

> “Assuming Danger has access to write a comment, then `fail`, `warn`, `message` and `markdown` will report directly inline.”

**关键**：**传不传 `file`/`line` 就决定了走汇总表格还是行内评论**。`Executor.js` 里用 `isInline(violation)`（即 `Violation` 是否带 file/line）把它们分流成 `resultsIntoInlineResults()` / 汇总结果。

行内评论的实现走两种 GitHub API：单条 `POST repos/:repo/pulls/:pr/comments`，或批量 `POST repos/:repo/pulls/:pr/reviews`（`event: "COMMENT"`）。

### 2.2 Diff 范围与「只报新增」

**Danger 不做 diff 过滤，把判断权交给 Dangerfile 作者。**

它提供的是数据：`danger.git.modified_files` / `created_files` / `deleted_files`、`danger.git.diffForFile()`、`danger.git.structuredDiffForFile()`、`danger.git.linesOfCode()`、`danger.git.fileMatch`（Chainsmoker）。规则里自己写 `if (danger.git.modified_files.includes('package.json'))`。

**唯一的「diff 范围」开关**是 `--ignoreOutOfDiffComments`，默认 `false`：

> `"--ignoreOutOfDiffComments", "Ignore inline-comments that are in lines which were not changed", false`

源码里的作用：
```js
inlineLeftovers = this.options.ignoreOutOfDiffComments ? emptyDangerResults : inlineLeftovers;
mergedResults = sortResults(mergeResults(mergedResults, inlineLeftovers));
```
即：**默认情况下**，落在未改动行上的行内评论会「落回」汇总表格里（不丢）；打开开关才丢弃。

### 2.3 抑制 / ignore

**没有内置抑制语法，也不托管状态**。抑制就是 Dangerfile 里的普通 JS `if`。README 首页的示例就是这个思路：

```js
const hasChangelog = danger.git.modified_files.includes("changelog.md")
const isTrivial = (danger.github.pr.body + danger.github.pr.title).includes("#trivial")
if (!hasChangelog && !isTrivial) { warn("Please add a changelog entry for your changes.") }
```

（即「在 PR 描述里写 `#trivial` 就跳过检查」这类约定完全由用户自定义。）

### 2.4 重复评论避免 + 更新 vs 新建（源码级）

核心是 **danger ID 作为隐藏标记写在评论正文里**：

```js
var dangerIDToString = function (id) { return "DangerID: danger-id-".concat(id, ";"); };
var dangerSignature = function (results) { ... return "Generated by :no_entry_sign: <a href=\"...\">dangerJS</a>"; };
```

汇总评论的模板把摘要塞进 HTML 注释（GitHub 上不可见）：

```js
function template(dangerID, results, commitID) {
  return "\n<!--\n".concat(buildSummaryMessage(dangerID, results), "\n-->\n")
       .concat(table("Fails", "no_entry_sign", results.fails), ...)
}
```

识别「我上一条评论」：

```js
this.getDangerCommentIDs = function (dangerID) {
  ...
  return allComments
    .filter(comment => comment.body.includes(dangerIDMessage))  // 含正确 danger ID
    .filter(comment => comment.body.includes("Generated by"))   // 像 danger 的评论
    .map(comment => comment.id);
};
```
更新走 `PATCH repos/:repo/issues/comments/:id`（`updateCommentWithID`）。

**默认行为是「更新或创建一条」**（`Executor.js`）：

```js
if (!this.options.newComment) { issueURL = await this.platform.updateOrCreateComment(dangerID, comment); }
else { issueURL = await this.platform.createComment(dangerID, comment); }
```

flag 与默认值（`sharedDangerfileArgs.js`）：

| flag | 默认 | 官方描述 |
| --- | --- | --- |
| `-i, --id [danger_id]` | `"Danger"`（源码 `dangerID: app.id \|\| "Danger"`） | “Specify a unique Danger ID for the Danger run” |
| `--newComment` | `false` | “Makes Danger post a new comment instead of editing its previous one” |
| `--removePreviousComments` | `false` | “Removes all previous comment and create a new one in the end of the list” |
| `--ignoreOutOfDiffComments` | `false` | “Ignore inline-comments that are in lines which were not changed” |

**行内评论的复用规则**（`Executor.js`）——按 `file + line` 匹配，匹配不上的**删除**：

```js
// For every inline result check if there is a comment already
// if there is - update it and remove comment from deleteComments array
// if there isn't - create a new comment
// Leftovers in deleteComments array should all be deleted afterwards
var index = deleteComments.findIndex(p => p.body.includes(fileLineToString(inlineResult.file, inlineResult.line)));
```

其中 `fileLineToString(file, line)` 的行内标记格式是：
```js
var fileLineToString = function (file, line) { return "  File: ".concat(file, ";\n  Line: ", line, ";"); };
```
并且有个省流优化：`if (body == previousComment.body) { return; }`——**内容没变就不发 API 请求**。

**空结果时主动清理**：

```js
if (isResultsEmpty(mergedResults)) { this.platform.deleteMainComment(dangerID); }
```
另有环境变量 `DANGER_SKIP_WHEN_EMPTY === "true"` 时跳过投递。

### 2.5 门禁

两条独立的失败通道：

1. **Commit status（默认主通道）**：`Executor.updatePrStatus(!failed, issueURL, results, dangerID)` → `POST repos/:repo/statuses/:ref`，`state = passed ? "success" : "failure"`（可为 `"pending"`），**`context = dangerID || "Danger"`**。可用 `--no-publish-check` 关闭（“Don't add Danger check to PR”）。
   - 若 status 发不出去（token 权限不足）且结果是失败：`successPosting=false && !passed` → `process.exitCode = 1`，并打印 “If the build fails, then danger will use a failing exit code.”
2. **退出码**：`-f, --failOnErrors`，**默认 `false`**（“Fail if there are fails in the danger report”）：

```js
if (this.options.failOnErrors && results.fails.length > 0) { this.process.exitCode = 1; }
```

即：**默认情况下 Danger 靠 commit status 卡合并，而不是靠 CI 退出码**。另外 `--use-github-checks`（默认 `false`）可改用 `POST repos/:repo/check-runs`（需 GitHub App token）。

失败文案固定：`githubIssueTemplate.messageForResultWithIssues = "Found some issues. Don't worry, everything is fixable."`；全绿时是 `"All green. " + compliment()`。

### 2.6 非行级发现

这正是 Danger 的**主场**：`warn`/`fail`/`message` 不带 file/line 时进入汇总评论的 HTML 表格（`table("Fails", "no_entry_sign", ...)` / `table("Warnings", "warning", ...)` / `table("Messages", "book", ...)`），官方首页示例全是这类——「没写 CHANGELOG」、「PR 太大」、「没指定 assignee」、「有库改动但没有测试改动」。另有 `markdown()` 把任意 Markdown 追加到表格下方。

---

## 3. SonarQube / SonarCloud

**总体可信度：`官方文档`（高）**

- PR 分析（Cloud）：<https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/pull-request-analysis.md>
- PR 分析（Server）：<https://docs.sonarsource.com/sonarqube-server/discovering/code-analysis/pull-request-analysis.md>
- GitHub 中的问题展示：<https://docs.sonarsource.com/sonarqube-server/user-guide/issues/in-devops-platform/github.md>
- GitHub 项目绑定与卡合并：<https://docs.sonarsource.com/sonarqube-server/project-administration/creating-project/github/configure-binding.md>
- 质量问题 / 质量门：`.../quality-standards-administration/managing-quality-gates/introduction-to-quality-gates.md`
- 编辑/抑制问题：<https://docs.sonarsource.com/sonarqube-server/user-guide/issues/managing.md>
- New Code 配置：`.../project-administration/adjusting-analysis/configuring-new-code-calculation.md`

### 3.1 评论模式（同时用「三种」通道）

> “Besides the pull request analysis summary found in the **Checks** and **Conversation** tabs, you will also see issues reported as **inline annotations** directly within the **Files changed** tab.”

即 SonarQube 一次分析同时产出：
1. **Check run**：质量门状态被注册成一个 GitHub check，名字是 **`SonarQube Code Analysis`**（“SonarQube Cloud adds the quality gate status as a GitHub check.”）
2. **Conversation 汇总评论**（PR decoration summary comment）——**可单独关闭**：`Project Settings > General Settings > DevOps Platform Integration` 下的 **“Enable analysis summary under the GitHub Conversation tab”**
3. **Files changed 行内注解**（inline annotations）

额外通道：安全问题会同步为 GitHub **code scanning alerts**（`Security > Code scanning alerts`），且**状态双向同步**：“if you change the status of a code scanning alert in GitHub, that change is reflected in SonarQube.”

### 3.2 Diff 范围与「只报新增」——SonarQube 的精华

PR 分析的算法（Cloud 文档写得更细）：

> “When SonarQube Cloud analyzes a pull request **P** for the merge of branch **B** into base branch **T** it scans the HEAD commit of **B** and compares the result with the most recent scan of **T**. **Only issues that appear in B but not in T are reported** in the analysis results.”

并且明确警告了脏数据的来源：

> “In cases where T includes new issues added since the most recent scan (in other words the scan is outdated) those additional issues will appear as part of the pull request analysis, even though they were not introduced by the pull request. **Therefore, an up-to-date analysis of T is required** to correctly detect which issues are new.”

质量门在 PR 上只套用新代码条件：

> “The quality gate used is the one set at the project level, however, **only the conditions on new code within the quality gate are applied**.”

**「New Code」不是 CLI 参数，而是服务器上的项目/分支级配置**。可选的定义（`Configuring new code calculation`）：

- **Previous version**
- **Number of days**
- **Reference branch**
- **Specific analysis**（只能通过 Web API 设置）
- 默认是「全局级 new code 基线（baseline for new code）」

Web API：`api/new_code_periods/set`；另有分析参数 `sonar.newCode.referenceBranch`。
注意实施条件：用 Previous version 时，版本号来自 `pom.xml` / `build.gradle`，否则必须显式设 `sonar.projectVersion`。

**存量问题**：

> “During a pull request analysis, **new issues found on old code are not reported**, because pull request analysis reports issues on new code only. As a result, the first analysis of the target branch after the merge can report new issues on old code that the pull request analysis did not report.”

即：PR 阶段看不见的存量问题，会在合并后的目标分支分析里「冒出来」（官方称之为 issue backdating）。

### 3.3 抑制模型 + 生命周期 + 存储位置

**（a）服务器端状态（主要方式）** —— 存放在 SonarQube 服务器数据库中，**不在仓库里**：

- 需要 `Administer Issues` 权限
- 状态：**Open**（默认）→ 可 **Accept**（变为 **Accepted**）、**False positive**、**Confirm**
- **Reopen** 会把 Accepted / False positive 重置回 **Open**
- 语义（官方原文）：“SonarQube Server **ignores** accepted issues in the quality reports and ratings of the code.”（False positive 同理）
- 可加 **Status change comment**，历史在问题的 **Activity** 页
- 批量操作：Bulk change（Assign / Add tags / Remove tags / Change status）

⚠️ 关于常见说法中的五态 `Open / Confirmed / Resolved / Closed / Won't Fix`：**当前 2026.5 文档只描述 Open / Accepted / False positive / Confirmed + Reopen**，未见 `Resolved` / `Closed` / `Won't Fix` 的表述 → 见第 6 节 `无法证实`。

**（b）仓库内抑制**：

- **`//NOSONAR`**（放在行尾）：

> “In all languages except Dockerfile, you can use the `//NOSONAR` comment at the end of a line to suppress **all issues on that line**.”
> “`//NOSONAR` indiscriminately suppresses every issue (current and future) on a given line, regardless of its nature or severity.”

- **`sonar-resolve`**（比 NOSONAR 精细，可审计）：形如 `sonar-resolve [status] ruleKey(s) comment`

> “an optional resolution status in brackets: `[accept]` (default) or `[fp]`... at least one rule key (e.g. `cpp:S4962`); separate multiple keys with commas... a mandatory comment”
> “Supported languages: C/C++/Objective-C.” / “The use of `sonar-resolve` must be enabled for your project.”
> “`sonar-resolve` is evaluated on **every analysis** and takes precedence over previous issue state.”
> “If an existing issue is resolved in the code with `sonar-resolve`, and `sonar-resolve` is disabled or the specific `sonar-resolve` is removed, **the issue is reopened** by the next analysis.”

**（c）外部问题的边界**：外部工具导入的问题，“managing an external issue within SonarQube Server **has no impact on its state in the external tool**”。

### 3.4 重复评论避免

**`无法证实`**。官方文档只说明汇总出现在 Conversation / Checks 标签页且可开关，**没有描述该评论是编辑复用还是每轮新建，也没有描述去重机制**。此结论经 SonarQube 文档自带的问答端点确认：

> “The available SonarQube documentation **doesn't specify** whether its PR summary comment is edited or recreated, or describe a deduplication mechanism. ... So, don't rely on these docs to establish SonarQube's exact duplicate-prevention behavior.”
> —— `docs.sonarsource.com/.../pull-request-analysis.md?ask=...`（文档站点内置问答）

### 3.5 门禁

质量门的定义与判定：

> “A quality gate consists of a set of conditions against which the code is measured during analysis... **If one of the conditions is met, the quality gate fails.**”
> 每条条件 = 指标 + 比较符 + 误差值。例：`Blocker issue > 0`。

内置 **Sonar way** 的四个条件：

- No new issues are introduced
- All new Security Hotspots are reviewed
- New code test coverage is **>= 80.0%**
- Duplication in the new code is **<= 3.0%**

**PR 上只应用 new code 条件**，且有 **fudge factor（默认开启）**：

> “The conditions on duplication are ignored until the *number of new lines* is at least 20. The conditions on coverage are ignored until the *number of new lines to cover* is at least 20.”

**两条卡住 PR 的路径**：
1. **GitHub 侧**：把 `SonarQube Code Analysis` 这个 check 加入 required status checks —— 通过 **branch ruleset**（Settings > Rules > Rulesets > Require status checks to pass）或 **branch protection rule**（Require status checks to pass before merging）。文档同时建议勾选 “Require branches to be up to date before merging”。
2. **CI 侧**：文档写明 “The quality gate status can be reported to your CI pipeline. It can be used to fail your CI pipeline if the quality gate fails.”（各平台有各自的页面，本报告未逐一展开。）

### 3.6 非行级发现（重要限制）

SonarQube **明确不做** PR 上的文件级问题上报：

> “Due to its mechanism for identifying issues on new code, **pull request analysis doesn't report issues raised at the file level**.”

这类问题（如「文件级」指标、重复代码块等）只在**合并后目标分支的完整分析**里出现。这与 reviewdog 的文件级评论、Danger 的汇总表格形成鲜明对照，是设计时的重要权衡点。

---

## 4. Semgrep

**总体可信度：`官方文档`（高）**

- CLI 参考（含 `--baseline-commit`、`nosem`、退出码）：<https://docs.semgrep.dev/cli-reference.md>
- 忽略文件/文件夹/代码：<https://docs.semgrep.dev/ignoring-files-folders-code.md>
- 去重与指纹：<https://docs.semgrep.dev/semgrep-code/remove-duplicates.md>
- 处置（triage）与修复：<https://docs.semgrep.dev/semgrep-code/triage-remediation.md>
- GitHub PR 评论设置：<https://docs.semgrep.dev/semgrep-appsec-platform/github-pr-comments.md>
- 为何评论不是行内：<https://docs.semgrep.dev/kb/semgrep-appsec-platform/inline-pr-comments.md>
- 为何收不到评论：<https://docs.semgrep.dev/kb/semgrep-appsec-platform/missing-pr-comments.md>
- 统一策略：<https://docs.semgrep.dev/semgrep-appsec-platform/unified-policies/overview.md>
- 自定义 CI 任务（diff-aware）：<https://docs.semgrep.dev/deployment/customize-ci-jobs.md>

### 4.1 评论模式

两种命令，行为差异很大（CLI 参考的折叠面板原文）：

> “The `semgrep scan` command is primarily used for local scans... **By default, these scans don't return failing error codes on findings** for further handling.”
> “The `semgrep ci` command is primarily used in CI pipelines for both full scans of codebases, as well as **diff-aware scans** that are initiated in the context of a pull request or a merge request... **By default, these scans return failing error codes on findings** for further handling.”

是否在 PR 上留评论**不由 CLI 决定，而由服务端策略决定**：

> “A comment appears when Semgrep generates a finding for a rule included in a **remediation policy** configured to leave PR or MR comments.”

统一策略（Unified Policies）把动作拆成两类：**Detection policies**（用哪些规则扫）和 **Remediation policies**（发现之后做什么），后者的动作包括 **Comment on PR/MR** 与 **Block merge and comment on PR/MR**：

> “**Automation 1** ... Actions: **Comment on the PR or MR**”
> “**Automation 2** ... Actions: **Block PR or MR**”

前置条件：必须配置 SCM 连接，且 `semgrep-app` 需要 `Read and write access to actions, pull requests, secrets, security events, and workflows` 权限。

### 4.2 Diff 范围与「只报新增」

**概念**：
> **Full scan**：“Scans the repository in its entirety. It is recommended to perform full scans on mainline branches”
> **Diff-aware scan**：“Diff-aware scans are performed on non-mainline branches, such as in pull requests and merge requests. Diff-aware scans traverse the repository's files based on **the commit where the branch diverged from the mainline branch**.”
> 且：“Unlike full scans, diff-aware scans **only consider changes within modified files**. **Cross-file analysis is not supported for diff-aware scans.**”

**CLI 侧 baseline**（这是 `--baseline-commit` 的权威定义）：

> `--baseline-commit=VAL (absent SEMGREP_BASELINE_COMMIT env)`
> “**Only show results that are not found in this commit hash.** Aborts run if not currently in a git directory, there are unstaged changes, or given baseline hash doesn't exist.”

**CI 侧**另有 `SEMGREP_BASELINE_REF`（值通常是 trunk 分支名，如 `main` / `master`），用于 Jenkins 等需要手工配置 diff-aware 的场景。

**实现细节（很有参考价值）**：diff-aware 其实跑**两次**扫描——HEAD 一次、baseline ref 一次；baseline 只扫「当前有 finding 的文件集」，所以更小。并且：

> “baseline scans are **not** conducted if all findings in the current commit are in files added by the PR or MR, because those findings could not have been present before.”
> 日志：`Skipping baseline scan, because all current findings are in files that didn't exist in the baseline commit.`

**存量问题**：不在 diff 范围的结果**根本不会成为「新 finding」**，因此不会评论（见 4.4）。另有跨分支自动处置的差异：

> “**Full scans**: if the current branch includes a finding that was previously introduced in another branch and triaged to a specific state, then the finding in the current branch is triaged to that same state.”
> “**Diff-aware scan**: findings introduced in a diff-aware scan are **not automatically triaged at scan time**, even if there are other instances of that finding on branches that have been triaged.”

### 4.3 抑制模型 + 存储位置

Semgrep 把「忽略」**明确分成两类**，这个区分对本项目极其有用：

> “**Ignoring as exclusion.** Exclude or skip specific files and folders from the scope of Semgrep scans...”
> “**Ignoring as triage action.** Ignore specific parts of code that would have generated a finding. Ignoring in this context means that Semgrep **generates a finding record and automatically triages it as Ignored**, a triage state.”

**（a）`nosemgrep` —— 行内，仓库内**

> “Create a comment, followed by `nosemgrep`, at **the first line or preceding line** of the pattern match. This generates a finding that is automatically ignored.”
> 示例：`// nosemgrep`、`// nosemgrep: rule-id`、`# nosemgrep`
> “To ignore blocks of code for a **specific rule**, enter its `rule-id` as follows: `nosemgrep: RULE_ID`. To ignore **multiple rules**, use a comma-delimited list. `rule-ids` must be referenced with their namespace.”

两条重要注意：
- **仍需组织级开启**：“Enable `nosemgrep` comments in Semgrep AppSec Platform: Settings > Global ... **Allow inline comments to disable Semgrep**”
- **不是「静音」而是「自动标记为 Ignored」**：“Ignoring code through this method still generates a finding. The finding is automatically set to the **Ignored** triage state.” —— 因此**可审计**。
- CLI 开关：`--enable-nosem` “Enabling by default”，`--disable-nosem` 取反。
- 旧写法已废弃：“Previous annotations for ignoring code inline, such as `nosem`, are deprecated.”

**（b）`.semgrepignore` —— 仓库内，文件/目录级**

放在**仓库根目录或工作目录**；“`.semgrepignore` syntax mirrors `.gitignore` syntax, with the following modifications”，多出一个 `:include ...` 指令（可把 `.gitignore` 引进来）。默认模板会跳过 `build/`、`vendor/`、`dist/`、`*.min.js`、`node_modules/`、`test/`、`tests/` 等。

**（c）Semgrep AppSec Platform 的 Path Ignores —— 服务器端，且是「加法」**

> “All files and folders defined using Semgrep AppSec Platform's **Path Ignores** feature, both for a specific project and globally, are **additive**.”
> “Adding items to Semgrep AppSec Platform's Path Ignores box **doesn't override** default Semgrep ignore patterns... both an existing local `.semgrepignore` file **and** the Path ignores box must be configured.”
> 并且：“This method is used by the `semgrep ci` command. For `semgrep scan`, you can only define ignored files and folders through `.semgrepignore`.”

**（d）Triage 状态 —— 服务器端**

| 状态 | 含义（官方原文节选） |
| --- | --- |
| **Open** | Findings are open by default... 与已启用规则匹配且未被忽略 |
| **Reviewing** | 需要调查以决定下一步 |
| **Provisionally ignored** | Multimodal Autotriage 判为很可能误报的「基于规则的」finding |
| **To fix** | 已决定修复（常表示已建 Jira 或派给人） |
| **Fixed** | 上次扫描发现、本次扫描不再出现 |
| **Ignored** | 存在于代码中但被判定可忽略 |
| **Removed** | 规则被禁用/改写、文件路径消失、或（GitHub 组织账号）检出该 finding 的 PR/MR 被关闭且未合并 |

Ignore 原因可选值：`False positive`、`Acceptable risk`、`No time`、`No triage reason`、`Duplicate`、`Ignored via nosemgrep`。
Bulk Triage API 的 `new_triage_state` 枚举：`open` / `reviewing` / `fixing` / `ignored` / `fixed`（`new_triage_reason` 必须配合 `new_triage_state=ignored`）。

**（e）通过 PR/MR 评论回复来 triage —— 这条对「机器人被人类反驳」的场景很关键**

> “You can triage your Semgrep AppSec Platform findings displayed as comments in PRs and MRs by **replying with another comment**.”

| 回复 | 效果 |
| --- | --- |
| `/fp <COMMENT>` | Ignored，原因 = false positive |
| `/ar <COMMENT>` | Ignored，原因 = acceptable risk |
| `/other <COMMENT>` | Ignored，原因 = No triage reason |
| `/open <REASON>` | 重新打开已 Ignored 的 finding |

限制与边界：
- “For security reasons, you **can't triage through PR or MR comments in public repositories**. This restriction does not apply to GitLab.com, GitLab Self-Managed, and GitHub Enterprise Server.”
- 需开启 `Allow developers to triage findings` + `Triage through code review comments`（非 GitHub 的 SCM 还需 webhook）
- 旧命令仍支持：`/semgrep ignore <REASON>`、`/semgrep open <REASON>`
- **重要**：“Triaging a finding as Ignored through a comment changes the status of the finding to Ignored in Semgrep AppSec Platform. However, **the pull request or merge request conversation itself is not automatically resolved** by this process.”

### 4.4 重复评论避免 —— 指纹模型（本报告中信息密度最高的部分）

Semgrep 用**两种指纹**跟踪「同一个 finding 在时间上的同一性」：

**`match_based_id`**（主指纹）：

> “Semgrep generates the `match_based_id` for a finding using the following information: The **file path**; The **name of the rule** that generated the finding; The **rule pattern with the metavariables' values substituted in**”
> “This information is combined and then hashed. At this point, Semgrep appends the **index**, a value generated by determining the number of times the rule involved matched code in the file... For example, `finding0` with `match_based_id = 123_0` and `finding1` with `match_based_id = 123_1` indicate that both were generated from the same rule matching the same code pattern in the same file.”

设计目标就是**抗行号漂移**：

> “Semgrep can determine if a given finding in a file is the same as a finding identified during a different scan, **even if the code snippet that the rule matched had been moved to a different location in the file**.”
> “This process enables Semgrep to **ignore lines that do not impact code function**.”（官方示例：在匹配区间内插入 `c_1 = 5` 这种赋值，`match_based_id` 不变）

**`syntactic_id`**：hash(文件路径 + 规则名 + **匹配到的字面代码** + index)；“primarily used by Semgrep for internal debugging purposes”。

**去重如何决定「不评论」**（`Why is my repository not receiving PR or MR comments?` 原文）：

> “**PR or MR comments are generated when a finding is new.** If a finding was seen in a previous scan, it is **not new** and a comment is not generated. **This prevents repeated comments on findings that have already notified developers.**”

**跨分支相关性**：
> “Semgrep's correlation of findings across branches based on their unique fingerprint allows for automatic consolidation of findings... a finding that has been triaged in one branch will be flagged as triaged if it's identified in another branch.”
> API 侧需显式开启：“set the `dedup` flag to `true` to deduplicate findings across refs or branches.”

`Removed` 的一种成因正是规则版本变化：“The rule... was updated and still matches the code, but identifies the issue as a new finding. The original finding is marked as **Removed** and a new finding is opened with the new version of the rule.”

> 官方免责：“The calculations used to determine whether findings are new are subject to change at any time as Semgrep improves its deduplication logic.” —— 即指纹**不是稳定契约**，不应作为外部依赖。

### 4.5 更新 vs 新建

**`无法证实`**。文档只正向描述了「只为**新** finding 生成评论」，**没有说明 Semgrep 是否会编辑它之前发过的评论**，也没有描述「如何识别自己上一条评论」。可确定的是：去重的判据是**指纹是否为新**，而不是「评论是否已存在」。

### 4.6 门禁

**（a）退出码（CLI 参考原文）**

`semgrep ci` 的 EXIT STATUS：

```
0   OK
1   some findings
2   fatal error
3   invalid target code
4   invalid pattern
5   unparseable YAML
7   missing configuration
8   invalid language
13  invalid API key
99  not implemented in osemgrep
```

`semgrep scan` 的退出码（不同）：
> “**0**: Semgrep ran successfully and found no errors (**or did find errors, but the `--error` flag is not being used**).”
> “**1**: Semgrep ran successfully and found issues in your code (while using the `--error` flag).”
> 另有 2 / 3 / 4 / 5 / 7 / 8 / 13 / 14(已废弃)

`--error` 的定义：“Exit 1 if there are findings. Useful for CI and scripts.”

可关掉失败：`--suppress-errors (absent SEMGREP_SUPPRESS_ERRORS env)` —— “If true, encountered errors are suppressed and **the exit code is zero (success)**.”

**（b）PR 卡合并**：Remediation policy 的动作 **Block merge and comment on PR/MR**；“Findings with a status of **provisionally ignored** block pull requests and merge requests if the matching rule is included in a remediation policy where the action taken is **Block merge and comment on PR/MR** when the policy's conditions are met.”

**（c）GitHub 原生兜底**：文档建议用 GitHub 的 “requiring conversation resolution before merging” 来阻止「Semgrep 已留评论的可达漏洞」被合并。

> ⚠️ Semgrep 在 GitHub 上「Block merge」的**具体实现机制**（是否为 status check、check 名称是什么）**`无法证实`** —— 文档只描述了策略动作名，未描述 GitHub 侧实现。

### 4.7 非行级发现 / 行内降级规则（官方写得很细）

> “When Semgrep comments on PR or MR findings, the comments are usually posted on the line of code where the finding is identified (inline). However, comments may appear as **summary comments** instead for several common reasons.”

三条降级原因：

1. **避免刷屏**：“if the same finding occurs **more than five times** in a PR or MR, the related comment is posted as a **summary (overall) comment**.” 且副作用明确：“Findings grouped together into a summary comment can **only be triaged in Semgrep AppSec Platform**.”（即不能在 PR 里用 `/fp` 处理）
2. **GitHub 的 diff 行限制**：“GitHub **only allows PR comments to be posted on lines of code that are shown in the default file-diff (Files changed) view**... If a finding appears outside those lines, Semgrep attempts to post the comment **as close to the finding as feasible**. If that fails, it posts the comment as a **summary comment**, visible in the Conversation view.”
3. **GitLab 差异**：“GitLab allows comments to be posted on lines of code outside the default file-diff view, but the comments don't appear in the file-diff (Changes) view.”

另有产品线层面的非行级 finding：Semgrep Supply Chain 的 **license violation** 与依赖类 finding、Semgrep Secrets 的 `--historical-secrets`（扫 git 历史）。这些的具体评论形态本报告未逐一验证。

---

## 5. 对比表

| 维度 | **reviewdog** | **Danger JS** | **SonarQube / SonarCloud** | **Semgrep** |
| --- | --- | --- | --- | --- |
| **评论模式** | 由 `-reporter` **显式**二选一乃至多选：`github-pr-check`（check run + 行内 annotation）、`github-check`（同上且支持 push）、`github-pr-review`（真 PR review comment）、`github-annotations` / `github-pr-annotations`（仅输出 Actions 注解到 stdout）、`local`（默认，仅 stdout）。**代码建议仅 `github-pr-review` 支持** | **一条汇总评论（HTML 表格）+ 可选行内评论**。`fail/warn/message/markdown` 的 `file?`/`line?` 参数决定走行内还是表格；行内走单条 `POST /pulls/:pr/comments` 或批量 `POST /pulls/:pr/reviews` | **三通道并用**：① 质量门注册为 GitHub check `SonarQube Code Analysis`；② Conversation 汇总评论（**可单独关闭**）；③ Files changed 行内注解。安全问题另走 code scanning alerts 且状态双向同步 | 由服务端 **remediation policy** 决定：**Comment on PR/MR** 或 **Block merge and comment on PR/MR**。默认行内；同一 finding 在 PR 内出现 **>5 次**、或 GitHub 不允许该行 → 降级为 Conversation 汇总评论 |
| **Diff 范围 / 只报新增** | `-filter-mode`：`added`（默认，仅新增/修改行）/ `diff_context`（±3 行）/ `file`（新增/修改文件内即可）/ `nofilter`。**存量问题默认直接不报** | **不做 diff 过滤**，只提供 `danger.git.modified_files/created_files/diffForFile/structuredDiffForFile/fileMatch` 等数据，规则自行判断。唯一开关 `--ignoreOutOfDiffComments`（默认 false，会把 diff 外的行内评论**落回汇总表格**而非丢弃） | PR 分析 = 扫 `B` 的 HEAD 与 `T` 最近一次扫描对比，**只报 T 中没有的问题**；质量门只应用 **new code 条件**。New Code 定义（服务器侧）：Previous version / Number of days / Reference branch / Specific analysis。`T` 的分析过期会把旧问题误报为新增 | **diff-aware scan**：以「分支与主线分叉的那个 commit」为基准，且**只考虑被修改文件内的变更**（不支持跨文件分析）。CLI `--baseline-commit` / `SEMGREP_BASELINE_COMMIT`：“Only show results that are not found in this commit hash”；CI 侧 `SEMGREP_BASELINE_REF`。实际跑两次扫描；若所有 finding 都在 PR 新增文件里则跳过 baseline |
| **抑制数据模型 & 存储位置** | **无抑制语法、无 baseline 文件**。「抑制」= 不在 diff 内。行内抑制交给上游 linter；**去重指纹存在 GitHub 的评论正文里**（无本地状态） | **无内置抑制语法、不托管状态**。抑制 = Dangerfile 里的普通 `if`（如 PR 描述写 `#trivial`）。状态仅在 `dangerID` 标记与 comment id | **服务器端（主）**：Open / Accepted / False positive / Confirmed，需 `Administer Issues` 权限；Accepted 与 False positive **被排除在质量报告与评级之外**。**仓库内（次）**：`//NOSONAR`（行尾，除 Dockerfile 外全语言，**静音该行所有问题**）、`sonar-resolve [accept\|fp] ruleKey "comment"`（仅 C/C++/Objective-C，需项目开启，**每次分析都重算且优先于历史状态**，移除即重新打开）。外部问题的状态与外部工具**不同步** | **两类语义明确区分**：① **排除**（不扫描）—— `.semgrepignore`（仓库内，gitignore 语法 + `:include`）、Platform 的 Project/Global **Path Ignores**（服务器端，**加法**，不覆盖默认值；`semgrep ci` 使用，`semgrep scan` 不适用）、`--exclude` / `--include`；② **处置**（仍生成 finding 并自动置为 **Ignored**）—— `nosemgrep` / `nosemgrep: rule-id`（需组织级开启，可审计）。服务器端 triage 状态：Open / Reviewing / Provisionally ignored / To fix / Fixed / Ignored / Removed，原因含 False positive / Acceptable risk / No time / No triage reason / Duplicate / Ignored via nosemgrep。**可用 PR 评论回复 triage**：`/fp`、`/ar`、`/other`、`/open`（公开仓库不可用） |
| **重复评论避免** | **指纹写在评论正文的隐藏 meta 注释里**（`Fingerprint(diagnostic)` + `toolName`）。每轮拉全量 PR 评论 → `ExtractMetaComment` → 按 `(path, line, fingerprint)` 判断 `IsPosted` 并跳过。**过期评论会删除**（`DeleteComment`），但**有人回复的评论保留**（`GetInReplyTo`）。`bitbucket-code-report` 则天然不去重（“Reports are stored per commit”） | **`dangerID` 标记写在评论正文的 HTML 注释里**：`<!-- DangerID: danger-id-<id>; ... -->`，配合 `Generated by :no_entry_sign: <a...>dangerJS</a>` 双条件识别自己的评论（`body.includes(dangerIDMessage) && body.includes("Generated by")`）。行内评论按 `File: <path>;\n  Line: <n>;` 匹配复用，**匹配不上的旧行内评论会被删除**；**生成内容与旧评论完全相同则完全不发 API 请求**。结果为空时 `deleteMainComment(dangerID)`；另有 `DANGER_SKIP_WHEN_EMPTY` | **`无法证实`** —— 文档未描述该评论的编辑/新建与去重机制（已用文档站问答端点确认） | 靠 **`match_based_id`** 判定「是否为新 finding」：hash(**文件路径 + 规则名 + 元变量代入后的规则模式**) + **index**；抗行号漂移，跨分支关联，triage 状态随之传播。**官方规则是：只为新 finding 生成评论**（“PR or MR comments are generated when a finding is new... This prevents repeated comments on findings that have already notified developers.”）。另一指纹 `syntactic_id` 仅内部调试用。官方声明该算法**可能随时变更**，非稳定契约 |
| **更新 vs 新建** | **每轮新建一个 Review**（`CreateReview`，`Event: "COMMENT"`，`CommitID: <sha>`），但通过指纹**跳过已存在评论**；识别「自己的评论」靠正文内 meta（指纹 + toolName），**不靠评论 ID、不靠 commit** | **默认 `updateOrCreateComment(dangerID, comment)` → 编辑旧评论**。`--newComment`（默认 false）改为新建；`--removePreviousComments`（默认 false）先全删再新建；`--id [danger_id]` 默认 `"Danger"`，正是用来隔离多个 Danger 机器人 | **`无法证实`**。已知：汇总评论出现在 Conversation / Checks，且可用 `Enable analysis summary under the GitHub Conversation tab` 关闭 | **`无法证实`** 是否会编辑旧评论。已确证：评论的生成条件是「指纹为新的 finding」 |
| **门禁** | **默认不失败**：`-fail-level=none` 时即使有 finding 也返回 0；`-fail-level=[any,info,warning,error]` 才在「存在 ≥ 该级别的问题」时返回 **1**。另有 `-fail-on-error`。展示层与失败层分离：`-level` 决定 `github-pr-check` 的 check 状态（`info`/`warning` → **neutral**，`error` → **failure**）。fork PR 无写权限时降级为 Actions 注解（有注解数量上限） | **默认靠 commit status 卡合并**：`POST /repos/:repo/statuses/:ref`，`state=success\|failure`，`context = dangerID \|\| "Danger"`；`--no-publish-check` 可关闭；`--use-github-checks`（默认 false）改用 check-runs。**`--failOnErrors` 默认 `false`**，故默认不以退出码失败；只有当 status 发不出去且结果为失败时才 `process.exitCode = 1` | 质量门 = 一组条件（指标 + 比较符 + 误差值），**命中任一条件即失败**。PR 上**只用 new code 条件**，且默认有 fudge factor（新代码行数 < 20 时忽略覆盖率/重复率条件）。内置 Sonar way：无新问题 / 新 Security Hotspot 全部已审阅 / 新代码覆盖率 ≥ 80% / 新代码重复率 ≤ 3%。**卡合并**：把 `SonarQube Code Analysis` 加入 branch ruleset 或 branch protection 的 required status checks；或把质量门状态回报给 CI 使其失败 | `semgrep ci` **默认在有 finding 时退出 1**（0 OK / 1 some findings / 2 fatal / 3 invalid target / 4 invalid pattern / 5 bad YAML / 7 missing config / 8 invalid language / 13 invalid API key）。`semgrep scan` 需显式 `--error` 才退出 1。`--suppress-errors` / `SEMGREP_SUPPRESS_ERRORS=true` 可令退出码为 0。PR 侧：remediation policy 的 **Block merge and comment on PR/MR**；或借助 GitHub 的 “require conversation resolution before merging” |
| **非行级发现** | **三档分流**：diff 上下文内 → 普通行内评论；diff 文件内但上下文外 → **文件级评论**（`SubjectType: "file"`，独立配额 10 条）；diff 文件外 → Actions 注解降级，否则不投递（控制台仍打印）。超过 30 条行内评论 → 塞进 review body 的 `<details>` 折叠块 | **主场**：不带 file/line 的 `fail/warn/message` 直接进汇总评论的 HTML 表格（Fails / Warnings / Messages），`markdown()` 追加到表格下方。官方示例多为「缺 CHANGELOG」「PR 过大」「没指定 assignee」「有库改动无测试改动」 | **明确不支持**：“pull request analysis **doesn't report issues raised at the file level**”，只能等合并后目标分支分析时出现 | 同一 finding 在 PR 内出现 **>5 次** → 折叠为汇总评论（副作用：**只能在 Semgrep AppSec Platform 里 triage**）；GitHub 不允许的行 → 尽量贴近，失败则 Conversation 汇总评论。另有 SCA license violation、依赖类 finding、`--historical-secrets` 等产品线级非行级发现 |

---

## 6. 对本项目「确定性规则 + LLM」架构的直接启示

1. **去重状态放进评论正文，不要自建库。** reviewdog 把 `Fingerprint(diagnostic)+toolName` 作为隐藏 meta 注释写进评论 body，Danger 把 `DangerID: danger-id-<id>;` 写进 HTML 注释——两者都实现了「服务重启不丢状态、多实例安全、无需数据库」。Spring Boot 侧建议：确定性规则用**规则ID + 文件路径 + 归一化代码片段**算指纹，连同 bot 标识写入评论末尾的隐藏标记（如 `<!-- aireview:fingerprint=...;tool=... -->`）。这也顺带解决了「确定性规则」与「LLM 评论」两类评论如何互不干扰地识别自己——reviewdog 用 `meta.SourceName == toolName` 做了这件事。

2. **指纹必须抗行号漂移。** Semgrep 的 `match_based_id` = hash(文件路径 + 规则名 + **元变量代入后的模式**)，官方示例证明在匹配区间内插入无关代码行不会改变指纹。若我们用行号或整行文本做身份，任何 rebase/格式化都会把同一问题判成新问题并重复评论。**要归一化**：去掉空白、把变量名/字面量用占位符替代后再哈希。

3. **确定性规则先跑，并且默认「只报本 PR 新增」。** reviewdog 的 `added` 是默认值，SonarQube 的 PR 分析只报「T 中没有的问题」，Semgrep 的 diff-aware 只考虑被修改文件。**建议流水线**：`git diff base...head` → 确定性规则全量跑 → 只保留落在新增/修改行上的结果 → 这些结果才是 LLM 的输入。这样既避免 LLM 被历史噪音淹没，也天然压低了 token 成本。同时保留 `diff_context` / `file` / `nofilter` 这类档位作为可配置项（reviewdog 的四档是很好的接口设计）。

4. **给「行级」和「非行级」准备两条独立通道，并接受 GitHub 的硬约束。** GitHub 只能给 diff 内、且在当前 diff 上下文内的行贴行内评论。reviewdog 为此做了三档分流（行内 / `SubjectType:"file"` 文件级 / Actions 注解降级），Semgrep 则「尽量贴近，失败就退化成 Conversation 汇总评论」。**「缺测试」「重复代码」「架构违规」这类 LLM 常见输出天生不是行级的**，应统一走一条**可编辑的汇总评论**（Danger 的表格形态最合适），而不是硬塞到某一行上。

5. **必须同时实现「更新/新建」与「删除过期」两种能力。** reviewdog 删除指纹不再出现的自己的评论，Danger 把未匹配上的旧行内评论删掉、并在结果为空时 `deleteMainComment`。LLM 评论比 linter 更容易过期（代码一改，意见就不成立），**遗留的过期 AI 评论会严重损伤信任**。建议：汇总评论 **update-or-create 单条**；行内评论按 `(file, line, fingerprint)` 匹配 → 命中则更新、未命中则删除；并留 `--new-comment` / `--remove-previous-comments` 两个逃生开关（Danger 的真实 flag 名）。

6. **把「展示级别」和「构建失败」彻底解耦。** reviewdog 用 `-level` 决定 check 状态（`info`/`warning` → neutral，`error` → failure），用 `-fail-level` 决定退出码，**默认还是 `none`（不失败）**；Danger 默认靠 commit status 卡合并、`--failOnErrors` 默认 false。**不要用「有没有产生评论」当失败条件**——LLM 的不确定性会让 CI 变成随机红灯。建议：确定性规则的严重度 → check 结论用固定映射表；LLM 输出默认为 neutral/comment-only，最多产出 warning。

7. **抑制语义要选「可审计的那一种」。** SonarQube 的 `//NOSONAR` 是「静音该行**所有**（含未来的）问题」，官方自己都在劝阻；Semgrep 的 `nosemgrep` 则是「**仍然生成 finding**，只是自动置为 Ignored」。后者可统计、可审计、可被 AI 学习为负样本。**给本项目设计 `// aireview-ignore: RULE-ID reason="..."` 这类带规则 ID 与原因的注解**，而不是一键静音；同时学 SonarQube 允许「带原因的忽略」（False positive / Acceptable risk / …），因为**原因字段就是未来微调规则的训练信号**。

8. **抑制状态要能在两处落地，并明确各自边界。** 仓库内（`.semgrepignore`、`//NOSONAR`、Dangerfile 逻辑）走代码评审流程、可版本化；服务器侧（SonarQube 的 Accepted/False positive、Semgrep 的 triage）无需改代码但会漂移，且 Semgrep 与 SonarQube 都提醒**外部/多分支状态可能不同步**。建议：**默认把抑制写进仓库**（PR 里可见、可 review），服务器侧只作为「临时忽略 + 失效时间」。特别注意 Semgrep 的落差——“Diff-aware scan: findings introduced in a diff-aware scan are **not automatically triaged at scan time**”，即 PR 上忽略过的 finding 仍可能在后续扫描里重新出现。

9. **为 fork PR 与限流准备降级路径，这是最容易在上线后炸的两处。** reviewdog 在 fork PR 上因 `GITHUB_TOKEN` 只读而**降级为 Actions 注解**（`::error line=...,file=...::`），且有「单次运行注解数量上限」；同时用 `maxCommentsPerRequest = 30`、`maxFileComments = 10` 防止触发 GitHub 的 abuse detection，超出部分折叠进 review body。**Spring Boot 侧清单**：① 无写权限时降级到 check run 注解或日志；② 单 PR 单轮评论总数封顶并对溢出做折叠汇总；③ 同一 finding 在一轮内重复 N 次时自动折叠（Semgrep 取 5，是个保守的好起点）。

10. **确定性规则应该产出「带指纹的结构化诊断」，而不是纯文本。** reviewdog 有一套显式的中间表示 **RDFormat（rdjson/rdjsonl）**，正是为了让任意 linter 的文本输出先归一化成「位置 + 严重度 + 规则 ID + URL + 建议」再决定投递形态；Danger 也有结构化的 `DangerResults`（`fails/warnings/messages/markdowns` + `file`/`line`）。**建议**：Java 侧定义一个 `RuleViolation` DTO（ruleId、severity、path、lineRange、fingerprint、是否在 diff 内、message、可选 fix），确定性规则与 LLM 都输出这个 DTO，由同一套投递层决定「行内 / 文件级 / 汇总 / 注解」——这样 `-filter-mode` 与 `-reporter` 就变成了投递层的两个策略参数，而不是散落在规则实现里的 if。

---

## 7. 无法证实的问题

| # | 未证实的问题 | 原因 |
| --- | --- | --- |
| 1 | **SonarQube 的 PR decoration 汇总评论是编辑复用还是每轮新建，以及它的去重机制** | 官方文档**未描述**。已通过文档站内置的 `?ask=` 端点交叉确认，其回答原文为：“The available SonarQube documentation **doesn't specify** whether its PR summary comment is edited or recreated, or describe a deduplication mechanism.” → `无法证实` |
| 2 | **SonarQube 五态生命周期 `Open / Confirmed / Reopened / Resolved / Closed`（及 `Won't Fix`）** | 当前 2026.5 文档只描述 `Open` / `Accepted` / `False positive` / `Confirmed` + `Reopen`。旧状态名可能已重命名或移除，本次未能找到权威出处 → `无法证实`。**请勿照抄这组状态名** |
| 3 | **Semgrep 是否会编辑它之前发出的 PR 评论，以及它如何识别「自己上一条评论」** | 文档只正向说明「只为新 finding 生成评论」（判据是指纹，而非评论是否存在），未描述编辑/复用与评论归属识别 → `无法证实` |
| 4 | **Semgrep 在 GitHub 上执行 `Block merge` 的具体机制**（是否注册 status check、check 名称为何） | 文档只给出策略动作名 `Block merge and comment on PR/MR`，未描述 GitHub 侧实现细节 → `无法证实` |
| 5 | **reviewdog 在 GitHub 上的最终呈现形态**（例如每轮 `CreateReview` 在 UI 上究竟表现为一条还是多条 review 记录；`github-pr-annotations` 与 `github-annotations` 在非 Actions 环境下的具体行为差异） | 只读到 README 的“Same as `github-annotations` but only works for Pull Requests.”，更深的差异与 UI 呈现无法从文档确认；且未能实机运行 → `无法证实`。已确证的是投递机制（每轮 `CreateReview` + 指纹跳过 + 删除过期），而非 UI 呈现 |
| 6 | **Danger（Ruby 版）的 flag 名与默认值是否与 Danger JS 完全一致** | 本报告只验证了 `danger-js@12.3.3` 的 npm 发布产物。搜索摘要提到 Ruby 版有 `--new-comment`（danger/danger#641）与 `--danger_id`（GitLab 工单），但均属 `搜索引擎摘要`，未取得正文 → 对 Ruby 版 `无法证实` |
| 7 | **Semgrep 各产品线（Supply Chain / Secrets / AI-powered detection）非行级 finding 的具体评论形态** | 文档只给出零散线索（license violation 会产生评论、`--historical-secrets` 扫 git 历史），未系统性描述其 PR 呈现 → `无法证实` |
| 8 | **各项目在 GitHub 上的原始 issue / Actions 示例源码**（如 reviewdog #568 关于「把过期评论标记为 obsolete」、Danger 的 issues、`semgrep.yml` 示例） | `github.com` 与 `raw.githubusercontent.com` 被网络策略封锁（解析到非公网 IP，fetch 失败）；`deepwiki.com` 返回 Vercel 429；`sourcegraph.com` 返回 403 firewall；`r.jina.ai` 代理 fetch failed。本报告因此改用 reviewdog 自建 GitLab 镜像、pkg.go.dev、unpkg 与各项目官方文档站，**未使用任何 GitHub 页面** |

---

### 附：本报告使用的可达信源清单

- reviewdog README（GitLab 镜像）：<https://gitlab.com/reviewdog/reviewdog/-/raw/master/README.md>
- reviewdog 投递源码：<https://gitlab.com/reviewdog/reviewdog/-/raw/master/service/github/github.go>
- reviewdog README（pkg.go.dev 渲染，含 v0.10.0 旧版 Exit codes 章节）：<https://pkg.go.dev/github.com/reviewdog/reviewdog>、<https://pkg.go.dev/github.com/mattbailey/reviewdog@v0.10.0>
- Danger JS：<https://danger.systems/js/>、<https://danger.systems/js/reference.html>、<https://danger.systems/js/guides/faq.html>
- Danger JS 源码（npm 产物）：`unpkg.com/danger@12.3.3/distribution/{platforms/github/GitHubAPI.js, runner/Executor.js, runner/templates/githubIssueTemplate.js, commands/ci/runner.js, commands/utils/sharedDangerfileArgs.js}`
- SonarQube 文档：`docs.sonarsource.com/sonarqube-server/...` 与 `.../sonarqube-cloud/...`（各页 URL 见第 3 节，均以 `.md` 取到纯文本）
- Semgrep 文档：`docs.semgrep.dev/{cli-reference, ignoring-files-folders-code, semgrep-code/remove-duplicates, semgrep-code/triage-remediation, semgrep-appsec-platform/github-pr-comments, semgrep-appsec-platform/unified-policies/overview, deployment/customize-ci-jobs, kb/semgrep-appsec-platform/{missing-pr-comments, inline-pr-comments}}.md`
