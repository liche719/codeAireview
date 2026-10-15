# AI Code Review 系统机制对标

> 目的：搞清业界同类系统在**十个具体工程机制**上怎么做，为本项目升级路线图提供"别人怎么做、我们该不该抄"的依据。
>
> ## 可信度声明（必读）
>
> | 标记 | 含义 |
> |---|---|
> | **【源码】** | 读取了该项目**实际源代码**，附文件与行号。置信度最高 |
> | **【官】** | 厂商官方文档，由子代理检索官方文档站获得（附 URL）。**未逐条二次打开核验** |
> | **【社】** | 社区讨论 / 提交信息。**不作为事实依据** |
> | **【未】** | 未能核实 |
>
> **本次会话网络受限与绕过路径**：`github.com`、`raw.githubusercontent.com`、`deepwiki.com`（429）、`sourcegraph.com`（403）均不可用；`docs.github.com` 只返回导航壳。
> 实际取证的绕过路径记录如下（对后续补做有用）：
> - **PR-Agent**：本地完整 clone（`C:\Users\33721\AppData\Local\Temp\pr-agent`，commit `330f0813b5fe0d1f54b794077c2d02aa85a1fb63`）
> - **reviewdog**：`gitlab.com` 上的**项目自建镜像**可读完整 README 与源码（`pkg.go.dev` 渲染版会在 Exit codes 前截断）
> - **Danger JS**：`unpkg.com` 上的 **npm 发布产物**比文档更能反映真实行为
> - **SonarQube / Semgrep**：文档站是 GitBook，**URL 后加 `.md`** 得到干净 Markdown（Semgrep 需先 301 到 `docs.semgrep.dev`）
> - **Copilot / Cursor Bugbot / Ellipsis**：`docs.github.com`、`cursor.com/docs`、`ellipsis.dev/docs` 的文档路径**后加 `.md`** 可拿到干净 markdown
> - **Sweep**：`docs.sweep.dev` / `sweep.dev` **DNS 不可解析**；GitLab 镜像只有归档仓库，且**其中不含 PR 评审产品**
>
> 可靠覆盖 **11 个系统**：PR-Agent、CodeRabbit、Greptile、Graphite、reviewdog、Danger JS、SonarQube、Semgrep、GitHub Copilot code review、Cursor Bugbot、Ellipsis。
> **Sweep 已从对标范围移除**——它是"规则驱动的 issue→PR 代理"，不是代码审查产品（详见第 3ter 节）。
> 因此"12 系统"这一目标以 **11 个已核实 + 1 个经核实后判定不适用** 收口。
>
> **命名更正（重要）**：Graphite 的 AI 审查能力原名 **Diamond**，该名称已于 **2025-10-08 弃用**，Diamond 与 Graphite Chat 合并为 **Graphite Agent**；`graphite.dev` 现已 **301 跳转到 `graphite.com`**。本文中「Graphite」一律指 **Graphite AI Reviews**（原 Diamond）。
>
> **原始调研附录**（含逐条出处，本文是其整合版）：
> - [`docs/research/pr-agent-deep-dive.md`](research/pr-agent-deep-dive.md) —— PR-Agent 源码级深读
> - [`docs/research/coderabbit-greptile-graphite.md`](research/coderabbit-greptile-graphite.md) —— CodeRabbit / Greptile / Graphite 官方文档调研（45 条编号引用）
> - [`docs/research/deterministic-review-tools.md`](research/deterministic-review-tools.md) —— reviewdog / Danger / SonarQube / Semgrep 调研（含 8 项无法证实清单）
> - [`docs/research/ai-native-reviewers.md`](research/ai-native-reviewers.md) —— Copilot code review / Cursor Bugbot / Ellipsis / Sweep 调研（27 条编号引用）

---

## 1. 对比矩阵

**PR-Agent 列全部为【源码】证据**；CodeRabbit / Greptile / Graphite 为【官】。

| # | 机制 | PR-Agent（源码） | CodeRabbit | Greptile | Graphite | Copilot |
|---|---|---|---|---|---|---|
| M1 | **跨 push 重复评论避免** | **三层，互相独立**：①`persistent_comment=true` 默认开，靠评论正文**前 5 行**内的隐藏 HTML 身份标记定位并 `edit_comment` 覆盖 ②**持久 finding 状态**藏进评论正文 ③inline 指纹去重（**默认关**，opt-in） | **不用指纹**。增量审查"track what's new since the last review"，并"checks its open threads and resolves those the changes addressed" | 摘要 footer 显示 `Reviews (2)` + `Last reviewed commit`，同一条摘要被反复编辑；是否比对 finding 指纹**未公开** | 未在文档中说明 | 【社】社区称其始终只提交 COMMENTED |
| M2 | **新 push 语义** | **默认全量重审**！增量只在显式 `-i` 时开，且要过阈值门 + **必须能找到上一次 review 评论**，否则强制退回全量。增量基线 = **上一次 review 时间之前的最后一个 commit**（优先 committer date，因 rebase 会保留 author date）；非增量基线 = **merge base**，不是 PR base SHA | `auto_incremental_review` **默认 true**，聚焦"自上次审查以来新增的 commit"；`auto_pause_after_reviewed_commits` **默认 5** | `autoReview: ["open","push","rebase"]` 可配；基线**未公开** | 【未】 | 【未】 |
| M3 | **`suggestion` 一键应用块** | 会生成。关键在**降级策略**：`validate_comments_inside_hunks()` 发现建议行落在 hunk 外时，**把 ```suggestion 块替换成 ```diff 块**——宁可取消一键应用，也不发一个应用不了的 suggestion。GitHub 返回 422 时逐条重发并剥掉 suggestion、只锚第一行。行号定位失败（position=-1）**不丢弃，降级为 file-level 评论** | committable 条件明确：锚定 diff **新侧真实行** + **有效的 start-to-end 区间**；**对已删除行拒绝生成**；仅 GitHub/GitLab | inline 含"问题+原因+具体修复"，可 commit suggestion | inline 含问题与修复，可 commit | 【未】 |
| M4 | **误报抑制 / 学习** | **人类 resolve Agent 开的 thread = 教它闭嘴**。`_load_dismissed_key_issues()` 只接受 `status=="resolved"` **且作者是 agent** 的线程，取最后一条回复作为 `state="DISMISSED"` 注回 prompt。同一 finding 再现会 `reopened_count+1`，已 RESOLVED 的保留在 `<details>` 区块。另有配置级静态抑制（ignore_pr_title / branches / labels / authors / repositories / language / file_filter） | **最完整**。自然语言 PR 回复 → **org 级 learning 存内部 DB**；`scope`=local/global/auto；`approval_delay` 0–30 天审批；**源自某 PR 的 learning 在该 PR 合并前只作用于该 PR**；写入时凭据脱敏；有 CRUD API | 👍/👎 **训练**系统（其他 emoji 视为中性）；**被忽略 3 次后自动抑制**；2–3 周完成适应；`disabledRules[]` 显式禁用 | 对评论 👍/👎；按规则统计 issues found / PRs reviewed / accepted issues / **acceptance rate** | 【未】 |
| M5 | **大 PR 处理与覆盖度上报** | **必须重点抄**。`max_model_tokens=32000`；压缩 diff 按文件 token 数**从大到小**塞预算，塞不下的收进 `remaining_files_list` 并告知模型；`enable_large_pr_chunking` **默认 false**（opt-in，`max_number_of_calls=3`）；`large_patch_policy="clip"`；**`enable_review_coverage_footer=true`（默认开）**，输出"⚠️ Review coverage: The following files were not included in this review because of the token budget"（超 50 条折叠）；抓不到内容的文件**插占位说明让模型标为需人工审查**，避免"跳过"被误当"干净" | 硬上限按套餐：**150 / 300** 文件；**>300 即使加钱也不支持**；`reviews.review_details` 用于**上报被抑制的评论** | 【未】 | **硬闸门**：`Not running: ...exceeds 200,000 characters`；**`linguist-generated` 文件在"是否过大"判定中先被排除** | 【未】 |
| M6 | **跨文件 finding 落点** | 【未】 | 【未】 | 【未】 | 代码索引存在，但 AI reviews 的收益标注为 **"*(coming soon)*"** | 【未】 |
| M7 | **状态/记忆持久化位置** | **零外部 DB**：状态序列化成版本化隐藏标记 `<!-- pr-agent-review-state:v1 {json} -->` 塞在 review 评论正文里，存 `finding_id`(sha256[:12]) / `state: ACTIVE\|RESOLVED` / `reopened_count` / `last_run.head_sha` / `complete` / `excluded_files`。专门处理了 `-->` 转义与"截断时先截人读文本、保住标记"。配置：`configuration.toml` 全局 + per-repo `.pr_agent.toml`（`use_repo_settings_file`，支持 per-directory） | learning 存**内部 DB**（org 级）+ 仓库内 `.coderabbit.yaml`（若存在则优先于 `.coderabbit.config.ts`）；`reviews.path_instructions[]` = `{path: glob, instructions}`（上限 20000 字符）；`reviews.path_filters[]` 支持 `!` 排除 | `.greptile/` 目录（`config.json`+`rules.md`+`files.json`）**按目录级联**，优先于仓库根 `greptile.json`；键含 `strictness`/`commentTypes`/`autoReview`/`ignorePatterns`/`rules[]`/`disabledRules[]`/`updateSummaryOnly`/sections 的 `collapsible` | **全部 UI/仪表盘**（Rules & exclusions），**无文档化的仓库根配置文件**；排除项是自然语言 | 【未】 |
| M8 | **正式 review verdict** | **不提交**。所有 `create_review` 都是 `event="COMMENT"`；`REQUEST_CHANGES` **全仓库零命中**；`auto_approve()` 存在但**唯一调用点被注释掉**，配置注释直言"PR-Agent does not approve PRs"。替代通道：`publish_as_check_run`（发成 Check 而非评论，此时持久状态标记失效）。另有 `require_merge_recommendation` 只让**模型输出文本** `no_concerns_found\|needs_review\|changes_required`，**不改变 GitHub review 的 event 类型** | `request_changes_workflow` **默认 false**；开启后发 REQUEST_CHANGES，满足"最新 commit 已审 + 全部 thread resolved + 无失败 pre-merge check"时**自动转 APPROVE**；`allow_author_approval` 控制作者自我批准 | auto-approve 需显式开启，条件是 5/5 审查 + `riskCeiling` + 过滤器。**策略从 PR 的 BASE 分支读取**，故"a PR that edits `.greptile` cannot loosen the policy for itself"；**有新 commit 时撤销自己先前的 approval** | 【未】 | 【社】始终只提交 COMMENTED，从不 APPROVED |
| M9 | **评论预算与折叠** | 【未】（`max_number_of_suggestions` 未证实） | `reviews.profile` = `quiet`\|`chill`\|`assertive`（默认 `chill`）；`collapse_walkthrough` **默认 true**，walkthrough 包进 `<details>`；`review_details` 控制被抑制评论展示 | sections 支持 `collapsible`/`defaultOpen` | 【未】 | 【未】 |
| M10 | **成本与可观测** | 【未】（`run_details.py`/`run_output.py`/`telemetry/` 未读） | `disable_cache` 可关缓存，缓存最长 **7 天**；Review Usage 仪表盘定位是运营遥测**非计费账本**；**无单次审查 token 计数** | 【未】 | 支持按作者/路径/label/标题/父分支过滤 PR 来"**Control usage and costs**" | 【未】 |

---

## 2. PR-Agent 深度（源码级，最有参考价值）

### 2.1 增量审查的真实设计

与直觉相反：**PR-Agent 默认是全量重审，增量是 opt-in**。

- 开关：`pr_reviewer.py:286-293` `parse_incremental()` —— 只有显式传 `-i` 才 `is_incremental = True`。
- 门槛：还要过 `_can_run_incremental_review()`（`pr_reviewer.py:1617-1665`）的 `minimal_commits_for_incremental_review` / `minimal_minutes_for_incremental_review` 阈值。
- **硬前提**：必须能**找到上一次 review 评论**，否则 `github_provider.py:314-316` 直接把 `is_incremental = False` 退回全量。
- 基线不是 base SHA，而是**上一次 review 时间之前的最后一个 commit**：`github_provider.py:325-335` 从最新往回走，`committer_date > previous_review.created_at` 的进 `commits_range`，第一个不满足的成为 `last_seen_commit`；old 侧文件内容按该 SHA 抓取后**重算 patch**（`github_provider.py:508-512`）。
- **用 committer date 而非 author date**：`_commit_timeline_date()` 注释解释——rebase 会改写内容但保留 author date，所以 committer date 更可信（`github_provider.py:318-323`）。跳过 merge commit（`:310-312`）。
- 非增量路径的基线是 **merge base**：`github_provider.py:517-529` `compare.merge_base_commit`，注释 "Use the merge base instead of a potentially advanced target branch"。

> **对我们的意义**：我们"每次 push 全量重审"并非落后于 PR-Agent 的默认行为。真正值得学的是它**增量必须建立在"能找到上一次 review"之上**——这要求先有持久评论身份（M1）。

### 2.2 三层去重（M1）

**(a) 持久评论 + 隐藏身份标记（默认开）**
`configuration.toml:187 persistent_comment=true`；`git_provider.py:1066-1131 publish_persistent_comment_full()` 找已有评论并 `edit_comment`。身份标记在 `algo/comment_identity.py:16-19`：
```
PRReviewIdentity.REGULAR     = "<!-- pr-agent:review:full -->"
PRReviewIdentity.INCREMENTAL = "<!-- pr-agent:review:incremental -->"
```
匹配只在**正文头部 5 行内**精确比对（`comment_identity.py:115-125`，`_REVIEW_IDENTITY_HEADER_LINES = 5`）。

**(b) 持久 finding 状态（最关键）**
`algo/review_finding_state.py`，序列化为 `<!-- pr-agent-review-state:v1 {json} -->` 藏在评论正文里，字段：`finding_id`(sha256[:12])、`state: ACTIVE|RESOLVED`、`reopened_count`、`last_run.head_sha`、`complete`、`excluded_files`。

**`allow_resolution` 的门槛设计极有价值**（`reconcile_review_findings` L225-232）：只有同时满足以下条件，才允许把"这次没再出现"的 finding 标成 RESOLVED——
1. `previous_head_sha != 当前 head_sha`
2. 本次是**完整**审查（无失败 chunk、无 `remaining_files_list`、无被丢弃 finding、`len(current_findings) < num_max_findings`）

这条规则直接回答了"新 push 之后，旧问题算不算被修好了"：**只有确信这次看全了，才敢说它被修好了。**

**(c) inline 指纹去重（默认关，opt-in）**
`algo/inline_comment_dedup.py` 文件头 docstring 自述是为解决 issue #2037（同一 PR 多跑几次重复贴同样的 inline suggestion）。双指纹 **OR 语义**：
- `body_fingerprint = sha256(f"{file}|{line}|{normalized_body[:80]}")[:12]`（先剥 `**Suggestion:**` 前缀与 `[..., importance: N]` 标签、折叠空白、小写）
- `code_fingerprint = sha256(f"{file}|{line}|code|{首个 suggestion 块代码}")[:12]`（代码**不**小写）

设计意图（L12-25）：OR 匹配同时覆盖「同文不同码」与「同码不同文」两种 LLM 复述方式。

**关键实现细节**：指纹**只用 file path + 正文，绝不用 diff position**——因为 GitHub committable 评论锚在 diff position 上，**加 commit 就会漂移**（`github_provider.py:954-957`）。

指纹以 `<!-- pr-agent-dedup: xxx -->` / `<!-- pr-agent-dedup-code: xxx -->` 写回评论正文；下次运行扫全部已有评论正文重建 seen 集合（`InlineCommentStore.load()`）。加载失败只降级为"本次运行内去重"，不抛异常。

反向释放：`store.release()` 让被"过期线程清扫"关掉的线程不再抑制自己的替代评论；**人类手动 resolved 的线程不释放，继续抑制**。

### 2.3 suggestion 的"宁可不给，也不给错"（M3）

`validate_comments_inside_hunks()`（`github_provider.py:2202-2293`）逐条比对 suggestion 行号是否落在 diff hunk 内。落在外面时把 `body` 里的 ```suggestion 块**替换成 ```diff 块**（L2276-2281），并在注释里直说："make the suggestion non-committable, yet multi line"。

→ **这是一个可以直接抄的设计原则：可应用性优先于交互便利。** 我们的 R4（suggestion）必须先做 R5（多行区间），且必须带同样的降级。

### 2.4 误报抑制：靠"人 resolve thread"，不靠 reaction（M4）

`pr_reviewer.py:696-717 _load_dismissed_key_issues()` 遍历 review threads，只接受三个条件同时成立的线程：
1. `status == "resolved"`
2. `authored_by_agent is True`
3. 带 key-issue 标记

取最后一条回复作为 `state="DISMISSED"` + `reply`，注入 prompt 让模型别再提。渲染顺序 ACTIVE → DISMISSED → RESOLVED，旧 finding 以 JSON 注回 prompt，默认预算 `max_previous_findings_chars = 8000`。

→ **这比"监听 👍/👎 reaction"更工程化**：不需要额外的 reaction webhook，复用 GitHub 原生的 thread resolution 状态。对我们 R7 是更好的方案。

### 2.5 大 PR：覆盖度默认上报（M5）

- 预算：`OUTPUT_BUFFER_TOKENS_SOFT_THRESHOLD=1500` / `HARD=1000`；`max_model_tokens=32000`
- 单发 vs 压缩：`get_pr_diff()` 先全量试探，超 soft 预算 → `pr_generate_compressed_diff()`，**按文件 token 数从大到小排序后逐个塞预算**
- 塞不下的进 `remaining_files_list`，以文本块告知模型："Additional modified files (insufficient token budget to process):"
- **`enable_review_coverage_footer = true` 默认开**：向用户输出
  `⚠️ **Review coverage:** The following files were not included in this review because of the token budget`（超 50 条折叠成 "... and N more"）
- 抓不到内容文件：`_unreadable_file_notice()` 插占位说明并让模型"flag it for manual review"，**避免"跳过"被误当成"干净"**
- 分块（opt-in）：`enable_large_pr_chunking=false`、`max_number_of_calls=3`；失败的 chunk 会追加 "N chunk(s) failed and are not covered by this review"
- 超大单文件：`large_patch_policy="clip"`（可选 "skip"），裁过的文件进 `partial_files_list`

→ **这一节几乎逐条对应我们的 S2 缺陷。行业默认做法是"明确上报未覆盖"，而我们是静默截断 + 可能输出假「未发现问题」。**

### 2.6 verdict：明确不做（M8）

`configuration.toml:289` 直言 "PR-Agent does not approve PRs"；`auto_approve()` 存在但**唯一调用点被注释掉**（`pr_reviewer.py:323-326`）；`REQUEST_CHANGES` 全仓库零命中。

替代方案：`require_merge_recommendation` 只让**模型输出文本** `no_concerns_found | needs_review | changes_required`，**不改变 GitHub review 的 event 类型**。

→ **这是一个重要的设计参考**：把"结论"作为**评论正文里的一句话**输出，而不是提交正式 verdict，可以完全避开 branch protection 误伤的风险。我们可以在 R7（verdict）里先提供"文本建议"，把真正的 `create_review(event=...)` 放在开关后面。

---

## 3. CodeRabbit / Greptile / Graphite 值得借鉴的机制

### CodeRabbit

- **增量审查默认开启且有刹车** — `auto_incremental_review` 默认 true；`auto_pause_after_reviewed_commits` 默认 5，避免长 PR 被反复刷。
- **靠"关闭已修复的 thread"而非指纹做去重** — 与 PR-Agent 的 DISMISSED/RESOLVED 状态机思路一致，两个独立系统都选了这条路线。
- **learning 是 org 级持久记录且带作用域 + 审批延迟** — 防止误学污染全组织。
- **`request_changes_workflow` 的自动转正状态机** — REQUEST_CHANGES →（最新 commit 已审 + 全部 thread resolved + 无失败 check）→ 自动 APPROVE。
- **文件数硬上限且明确不支持超限** — 300 是硬顶，**明确拒绝而不是静默截断**。
- **`review_details` 上报被抑制的评论。**

### Greptile

- **策略从 PR 的 BASE 分支读取** — "a PR that edits `.greptile` cannot loosen the policy for itself"。**纯安全设计亮点**，与我们的 fix 两阶段审批同源。
- **有新 commit 时撤销自己先前的 approval。**
- **👍/👎 训练 + "忽略 3 次自动抑制"** — 不需要用户显式操作的噪声收敛。
- **目录级联配置 `.greptile/`** — 支持 monorepo 不同子目录不同规则。
- **摘要 footer 显示 `Reviews (n)` + `Last reviewed commit`** — 极低成本的透明化。

### Graphite AI Reviews（原 Diamond，现 Graphite Agent）

- **`linguist-generated` 文件在"PR 是否过大"判定中先被排除** — 用 GitHub 既有标注识别生成物，不必自维护路径规则。
- **超限时明确拒绝并在 PR 状态里写明原因。**
- **按作者/路径/label/标题/父分支过滤来控成本。**
- **按规则统计 acceptance rate** — 衡量建议被采纳率，而非只看提了多少。

---

## 3bis. 确定性工具组（reviewdog / Danger / SonarQube / Semgrep）

**这一组与本项目"确定性规则先于 LLM"的架构最像，参考价值最高。** 详细出处见 [`docs/research/deterministic-review-tools.md`](research/deterministic-review-tools.md)。

### reviewdog（源码级）

**评论通道是显式选择的，没有自动切换**：`local`（默认）/ `github-pr-check` / `github-check` / `github-pr-review` / `github-annotations` / `github-pr-annotations`，另有 GitLab/Gerrit/Bitbucket/Gitea reporter。

- 官方 README **明确承认** `github-pr-check` 的 Option 2（reviewdog 自建 GitHub App 服务器）不可靠——原文说该服务器"is running with haya14busa's pocket money"。**要 check run 又不依赖第三方服务器，就必须在 GitHub Actions 内运行。**
- **code suggestion 只有 `github-pr-review` 支持**（README 有官方支持表）。
- `-filter-mode` 四档：**`added`（默认）** / `diff_context` / `file` / `nofilter`，并有**逐 reporter 的支持差异表与降级说明**。
- `level` → GitHub 状态映射是硬编码的：`info`/`warning` → `neutral`，`error` → `failure`。
- **退出码默认 `-fail-level=none`——即"有 finding 也返回 0"**，必须显式 `-fail-level=[any,info,warning,error]` 才返回 1。

**去重机制（本次最有价值的源码发现）**：
- 指纹由 `serviceutil.Fingerprint(diagnostic)` 算出，连同 `toolName` 用 `BuildMetaComment` **写进评论正文的隐藏 meta 注释**。
- 每轮**分页拉取全量 PR 评论** → `ExtractMetaComment` → 按 `(path, line, fingerprint)` 判断 `IsPosted`，已发过则跳过。
- **并且会 `DeleteComment` 删除"指纹不再出现"的自己的评论**——但**有回复的除外**（靠 `GetInReplyTo` 判断）。
- 每轮仍调用 `CreateReview(CommitID: sha, Event: "COMMENT")`。
- 常量 `maxCommentsPerRequest = 30` / `maxFileComments = 10`，溢出部分进 review body 的 `<details>`。
- **三档分流**：行内 → `SubjectType:"file"` 文件级 → 非 diff 文件降级为 Actions 注解。
- **fork PR 因 `GITHUB_TOKEN` 只读，会走同一条降级路径。**

### Danger JS（npm 发布产物源码）

- **一条汇总评论**（HTML 表格）+ 可选行内；API 是 `fail/warn/message/markdown(message, file?, line?)`——**传了 file/line 就变成行内评论**。
- 去重靠正文里的 `<!-- DangerID: danger-id-<id>; ... -->` **加上** `Generated by ... dangerJS` **双条件**识别自己的评论；默认行为是 `updateOrCreateComment`（**编辑旧评论**）。
- 行内评论按 `File: <path>;\n  Line: <n>;` 匹配复用，**匹配不上的旧行内评论会被删除**。
- **生成内容与旧评论完全相同时不发 API 请求**（省配额）。
- 已从源码核实的真实 flag 与默认值：`-i/--id`（默认 `"Danger"`）、`--newComment`（`false`）、`--removePreviousComments`（`false`）、`--ignoreOutOfDiffComments`（`false`，**默认会把 diff 外的行内评论落回汇总表格，而不是丢弃**）、`--use-github-checks`（`false`）、**`-f/--failOnErrors` 默认 `false`**。
- **门禁主通道是 commit status**（`context = dangerID || "Danger"`），`--no-publish-check` 可关。

### SonarQube

**三通道并用**：
1. **质量门注册为 GitHub check，名字是 `SonarQube Code Analysis`**——卡合并的方式是把它加进 branch protection / ruleset 的 required status checks。
2. Conversation 汇总评论（可在 Project Settings > DevOps Platform Integration > "Enable analysis summary under the GitHub Conversation tab" 关闭）。
3. Files changed 行内注解。

- 安全问题另走 **GitHub code scanning alerts**，且**状态双向同步**。
- **PR 分析 = 扫 PR HEAD 与目标分支最近一次扫描对比，只报目标分支中没有的问题**；质量门只用 **new code** 条件。**官方明确警告：目标分支的分析过期会导致误报为"新增"。**
- **New Code 是服务器侧的项目/分支配置**（`api/new_code_periods/set`；参数 `sonar.newCode.referenceBranch`）。
- **抑制模型（服务器端为主）**：状态 `Open` / `Accepted` / `False positive` / `Confirmed`（需 Administer Issues 权限；**`Accepted` 与 `False positive` 会被排除在质量报告与评级之外**）。仓库内另有 `//NOSONAR`（行尾，除 Dockerfile 外全语言，**会静音该行所有现在与未来的问题，官方自己劝阻**）与 `sonar-resolve`（仅 C/C++/Objective-C）。
- **明确不支持文件级问题的 PR 上报**——只能等合并后在目标分支分析里看到。

### Semgrep

- PR 评论由服务端 **remediation policy** 决定（动作：Comment on PR/MR，或 Block merge and comment）。
- **把「忽略」明确拆成两类**，这是设计上最值得学的一点：
  - **排除**：`.semgrepignore`（仓库内，gitignore 语法）；Platform 的 Path Ignores（**服务器端，且是加法、不覆盖默认值**，`semgrep ci` 用而 `semgrep scan` 不用）。
  - **处置**：`nosemgrep` / `nosemgrep: rule-id`（需组织级开启）。**关键：它仍然生成 finding 并自动置为 `Ignored`——可审计**，语义上明显优于 SonarQube 自己都在劝阻的 `//NOSONAR`。
- 服务器端 triage 状态：`Open` / `Reviewing` / `Provisionally ignored` / `To fix` / `Fixed` / `Ignored` / `Removed`；原因含 `False positive` / `Acceptable risk` / `No time` / `Duplicate` / `Ignored via nosemgrep`。
- **可以用 PR 评论回复做 triage**：`/fp`、`/ar`、`/other`、`/open`（**公开仓库不可用**）；但 triage 之后 **PR 会话不会自动 resolve**。
- diff 范围：diff-aware scan 以"与主线分叉的 commit"为基准，只考虑被修改文件内的变更，**明确不支持跨文件分析**；CLI 提供 `--baseline-commit` / `SEMGREP_BASELINE_COMMIT`（"Only show results that are not found in this commit hash"），CI 侧另有 `SEMGREP_BASELINE_REF`。实际会跑两次扫描；若所有 finding 都落在 PR 新增文件里则跳过 baseline。
- **去重（最有参考价值的算法）**：`match_based_id` = `hash(文件路径 + 规则名 + 元变量代入后的模式)` + index。**官方示例证明：在匹配区间内插入无关代码行，指纹不变——即抗行号漂移**，且能跨分支关联、triage 状态随之传播。
- 官方原文：**"PR or MR comments are generated when a finding is new. If a finding was seen in a previous scan, it is not new and a comment is not generated."**
- 门禁：`semgrep ci` **默认有 finding 即退出 1**（码 0/1/2/3/4/5/7/8/13）；`semgrep scan` 需显式 `--error`；`--suppress-errors` / `SEMGREP_SUPPRESS_ERRORS=true` 可令退出码为 0。
- **行内降级三条规则**：同一 finding 在 PR 内 **超过 5 次 → 折叠为汇总评论**（副作用：之后只能在 Platform 里 triage）；GitHub 只允许给默认 diff 视图内的行贴评论，否则"尽量贴近，失败则落回 Conversation 汇总"。

### 确定性工具组速查表

| 机制 | reviewdog | Danger JS | SonarQube | Semgrep |
|---|---|---|---|---|
| 通道 | **显式选 reporter**（6 种 GitHub + 4 平台） | 汇总评论 + 可选行内 | **三通道并用**：check + 汇总 + 行内 | 服务端 remediation policy 决定 |
| 只报新问题 | `-filter-mode=added`（默认） | 无内置，靠 Dangerfile 自写 | 与目标分支最近扫描对比，只报"新增" | `--baseline-commit` / diff-aware |
| 去重状态存哪 | **评论正文隐藏 meta 注释**（无状态） | **评论正文 DangerID**（无状态） | **服务器端**（有服务） | **服务器端 + `match_based_id`**（有服务） |
| 抗行号漂移 | 指纹含 `(path, line, fingerprint)`——**仍含行号** | 行内按 `File:`+`Line:` 匹配——**仍含行号** | 服务端匹配 | **不含行号**（元变量代入后的模式哈希） |
| 是否删除过期评论 | **是**（有回复的除外） | **是**（匹配不上的旧行内） | 未文档化 | 未文档化 |
| 门禁 | 默认**不失败**（`-fail-level=none`） | 默认**不失败**（`--failOnErrors=false`），主通道是 commit status | **质量门 = required status check** | `semgrep ci` **默认失败** |

### 本节对本项目「确定性规则 + LLM」架构的直接启示

1. **"有 finding"与"构建失败"必须解耦。** reviewdog 与 Danger **默认都不失败**；SonarQube 用独立的 required status check 卡合并。我们目前**既没有 verdict 也没有门禁语义**，一旦引入必须分开设计：LLM 的输出默认只评论（neutral），**绝不能**用"有没有产生评论"当失败条件。
2. **去重状态写进评论正文**（reviewdog 的 fingerprint meta / Danger 的 DangerID）——服务重启不丢、多实例安全、无需数据库。但注意：**采用这条的三家都是无服务端的**（PR-Agent / reviewdog / Danger），而**有服务端的 SonarQube 与 Semgrep 都用服务器侧状态**。这与我们 R6 选数据库的判断一致。
3. **指纹必须抗行号漂移。** Semgrep 的 `match_based_id` 是唯一做到这一点的（只用路径+规则+元变量代入后的模式）。reviewdog 与 Danger 的指纹**仍含行号**，所以它们靠"删除过期评论"来兜底。**我们要做的是前者（不含行号）+ 后者（删除过期）两者都要。**
4. **必须有"删除过期评论"的能力**，且**有回复的不能删**（reviewdog 的 `GetInReplyTo` 判据）。我们目前完全没有删除/收敛能力（C14/C15）。
5. **"忽略"要区分"排除"与"处置"。** Semgrep 的 `nosemgrep` **仍然生成 finding 并标记为 Ignored（可审计）**；SonarQube 的 `//NOSONAR` **静音该行一切问题且不可审计**，官方自己都在劝阻。**我们要选前者**。
6. **确定性规则默认只报本 PR 新增行**（reviewdog `added` 默认 / SonarQube 只报目标分支没有的 / Semgrep diff-aware），并把结果作为 LLM 的**输入**而非全量代码。
7. **评论数要封顶并折叠**：reviewdog `maxCommentsPerRequest=30` / `maxFileComments=10`，溢出进 `<details>`；Semgrep 同一 finding 超过 5 次折叠为汇总。我们目前只有 inline 预算 10、**没有摘要侧折叠**。
8. **行内不可达时要有明确降级链**，而不是丢弃：reviewdog「行内 → 文件级 → Actions 注解」；Danger `--ignoreOutOfDiffComments=false` 时把 diff 外的评论**落回汇总表格**。我们的 location guard 是"拒绝后进 Summary"，方向对但**用户看不到为什么**（C12）。
9. **fork PR 的写权限降级必须提前设计。** reviewdog 明确因 `GITHUB_TOKEN` 只读而降级到 Actions 注解。我们的审查链路不感知 fork（A18），发布侧也没有降级路径。
10. **baseline 必须新鲜。** SonarQube 官方警告：目标分支分析过期会把老问题误报成新增。这直接约束我们 R6 的记忆——**"完整审查"判据里必须包含 baseline 的有效性**。

## 3ter. AI 原生审查产品（Copilot code review / Cursor Bugbot / Ellipsis / Sweep）

出处与 27 条编号引用见 [`docs/research/ai-native-reviewers.md`](research/ai-native-reviewers.md)。
**取证突破**：`docs.github.com` 的任意文档路径**后加 `.md`** 会直接返回干净 markdown（此前我一直只拿到导航壳）；Cursor 用 `cursor.com/docs/*.md`，Ellipsis 用 `ellipsis.dev/docs/*.md`。

> ⚠️ **Sweep 需要重新归类**：`docs.sweep.dev` / `sweep.dev` 在本环境 **DNS 不可解析**。通过 GitLab 镜像读到的归档仓库（2024 末 / 2025 初）**并不包含 PR 评审产品**——`handlers/on_review.py` 只是把 `base..head` 全量 diff 取出来的桩函数。**Sweep 实际是"规则驱动的 issue→PR 代理"，不是代码审查产品**，把它放进"12 个 AI review 系统"对标本身就是我最初的范围设定错误。

### 各产品要点

**GitHub Copilot code review**
- **默认发普通 `Comment` review**；可配 Approve（public preview 自动批准）；**没有文档化的 request-changes**。
- **官方明确承认做不到跨 push 去重**——文档原话：重新审查"may repeat previous comments, even if you resolved or downvoted them"。**即连原生实现都会重复评论。**
- **静态排除清单**（这是 R11 的重要参考）：锁文件、`**/generated/**`、`**/*.min.js`、`**/*.d.ts`、`**/vendor/**`、`**/bin/**` 等。
- **曾有 300 文件 / 20,000 行上限，已于 2026-08-27 移除。**
- **code review 没有任何评论触发入口**（`@copilot` mention 驱动的是 cloud agent，不是 review）。
- 成本给的是**估算区间**（Lite $0.05–1，Balanced $0.25–5）+ Actions minutes。

**Cursor Bugbot**
- **去重靠 `git patch-id`**：对同一份 diff 直接短路，另有 `review.incremental: true` 做增量。
- **不发 review 结论**，只发一个 `Cursor Bugbot` **check**（success / neutral / failure）——**这一点与 R17 的设计完全一致**。
- 有评论触发入口：`cursor review` / `bugbot run`。
- **analytics API 返回每次审查的结构化数据**：`cost_cents`、findings、`resolution_status`，**并且有 dryRun 模式**。

**Ellipsis**（这一组里记录最完整的）
- **最好的去重模型**：按 **commit 区间**记账，而不是按评论文本——每个 reviewer 只看到"它还没评审过的 commit"（`range_base_sha`）；锚定在这个区间之外的评论**被扣下不发，但仍可在 API 中查到**（`status: outside_range` / `not_in_diff`）。
- **最接近"覆盖度上报"的产品**：API 暴露 `reviewers[].reviewed_files` / `reviewed_lines`，加上被扣下评论的状态。**但它是通过 API 暴露，而不是在 PR 里告诉用户。**
- **结论能力完全可配**：`output.on_pass` = `silent` \| `comment` \| `approve`；`output.on_fail` = `comment` \| `request_changes`。
- **唯一给出评论预算数字的**：**64 条/次审查、64 条/每个 reviewer**；**超限的评论被保留但不发布**；**超限的 reviewer 直接判失败且不贡献任何内容**。
- **成本/token 是结构化 API 字段**：`cost`（按 llm / cpu / memory / fee 拆分，且按模型拆分）+ `tokens`（含 `cache_read` / `cache_creation`）+ 硬性 `budget.session`。
- 评论触发：`@ellipsis`，**不需要重复 mention**。

### 这一组带来的三条修正

**修正 A（推翻我的假设）："覆盖度上报"是真正的差异化空间。**

我原以为业界普遍在上报覆盖度。**实际结论是分裂的**：

| 阵营 | 是否上报"哪些文件没审、为什么" |
|---|---|
| **4 个商用 AI 原生产品** | **一个都没有在 PR 里告诉用户**。Copilot 只有静态排除清单；Ellipsis 只在 API 里暴露 `reviewed_files` |
| **PR-Agent（开源，源码已证实）** | **有**。`enable_review_coverage_footer=true` **默认开**，明确输出被 token 预算排除的文件 |

→ **R2 依然成立且优先级不降**：它既是**对标 PR-Agent 的成熟实践**，又是**对商用产品的差异点**。而且 PR-Agent 的存在证明它可实现、成本不高。

**修正 B（验证 R17）：Bugbot 只发 check、不发 verdict —— 我的门禁设计方向正确。**
Ellipsis 的 `on_pass` / `on_fail` 配置形态（`silent`/`comment`/`approve` × `comment`/`request_changes`）比我 R17 的方案更完整，值得借鉴其**形态**：把"通过时做什么"与"失败时做什么"分开配置。

**修正 C（验证 R7 的可审计抑制）：Ellipsis 的"超限评论被保留但不发布"正是我采纳的原则。**
同时它暴露了一个我没想到的失败模式：**"超限的 reviewer 直接判失败且不贡献任何内容"**——即预算溢出本身可以是一种失败。这提示 R2/R17 需要考虑"覆盖度过低是否应视为审查失败"。

**另外两条对 R10 / R11 / R14 的直接影响**：
- **R10**：Bugbot 与 Ellipsis 都把 **per-review cost + token** 作为结构化输出（含 `cache_read` tokens）。我们完全没有这一项，且这是可以直接照抄的字段设计。Bugbot 的 **dryRun 模式**也值得借鉴（用于验证发布路径而不真正发评论）。
- **R11**：Copilot 的排除清单（`**/generated/**`、`**/*.d.ts`、`**/vendor/**`、`**/bin/**`）可直接作为我们跳过规则的补充参考——我们当前**恰好缺这几类**。
- **R14**：**这 4 家里没有任何一家支持"在 diff 行上 @bot"**（Bugbot 与 Ellipsis 只支持会话流评论触发，Copilot 连评论触发都没有）。**R14 会让我们在这一组里领先**——但需注意 CodeRabbit/Greptile 是否支持尚未核实，所以只能说是相对这一组的差异点。

## 4. 对 CodePilot 的直接启示

| 启示 | 来源 | 对应我们的问题 | 路线图 |
|---|---|---|---|
| **覆盖度必须上报**（未审文件 + 原因），超量折叠 | PR-Agent【源码】+ CodeRabbit + Graphite | **S2** | **R2** |
| 抓不到内容的文件要插占位说明，**别让"跳过"看起来像"干净"** | PR-Agent【源码】 | **S2** | **R2** |
| 摘要里显示**审查次数 + 最后审查 commit** | Greptile | S2 | R2 |
| **持久状态藏进评论正文的版本化 HTML 标记**（零外部 DB、可 diff、可校验 schema） | PR-Agent【源码】 | E2/E3/R5/R6 的数据模型 | **R6** |
| **只有"完整审查"才允许把消失的 finding 标为 RESOLVED** | PR-Agent【源码】 | E2/E3 | **R6** |
| 指纹**只用 file + 正文，绝不用 diff position**（position 会随 commit 漂移） | PR-Agent【源码】 | C13 | **R6** |
| **误报抑制靠"人 resolve agent 开的 thread"**，不靠 reaction webhook | PR-Agent【源码】 | E7/G2/G7 | **R7** |
| suggestion 必须带降级：**行落到 hunk 外就改成 ```diff 块**，宁可不给也不给错 | PR-Agent【源码】 | C4/C5 | **R5→R4** |
| suggestion 必须锚定 **new side 真实行 + 有效行区间**，**拒绝在删除行生成** | CodeRabbit | C4/C5 | R4 |
| verdict 可以先只做**评论正文里的文本结论**，真 verdict 放开关后 | PR-Agent【源码】 | C10 | **R7 的替代方案** |
| 审查策略要**从 base 分支读**，防 PR 自改策略 | Greptile | 配置自保护 | R12 |
| 用 `linguist-generated` 识别生成物，而非自维护路径规则 | Graphite | B14/B15 | R11 |
| 评论折叠（`<details>`）与 profile 档位（quiet/chill/assertive） | CodeRabbit | C11 | R2 |
| 成本控制做成**可配置 PR 过滤器** | Graphite | H2 | R10 |
| 增量审查**必须建立在"能找到上一次 review"之上**，且有阈值刹车 | PR-Agent【源码】+ CodeRabbit | A2/A17/H2 | **R14（待决）** |

---

## 5. 我们已达标 / 明显落后的地方

### 不比这些系统差的地方

- **确定性规则先于 LLM 且有明确降级路径**（`AiReviewServiceImpl`）—— 对标对象里没有一个把这点作为显式机制文档化。
- **修复闭环的两阶段精确审批**（patch SHA-256 + 允许路径 + 同一请求者）比对标对象的 auto-approve 更保守、更安全。
- **Agent 可恢复运行时**（`agent_run`/`agent_step` + `WAITING_WORKFLOW` + continuation）在开源方案里少见。
- **外部检索 SSRF 防护**（私网 DNS / 重定向 / 大小 / 内容类型）比多数方案严格。
- **执行租约 + 命令 outbox** 的可靠性组合，对标对象没有等价物（它们是"跑一次算一次"）。

### 明显落后

1. **覆盖度上报** —— PR-Agent **默认开启** `enable_review_coverage_footer`，Graphite 超限直接拒绝并说明；我们静默截断甚至输出假「未发现问题」。**这是最危险的单点。**
2. **记忆与跨 push 状态** —— PR-Agent 有完整 finding 状态机（ACTIVE/RESOLVED/reopened_count + head_sha）；我们整块为零。
3. **误报抑制** —— 三个系统都有（resolve thread / learning / 👍👎 + 忽略 3 次）；我们为零。
4. **评论形态** —— `suggestion` + 多行区间是行业标配；我们都没有。
5. **路径级配置** —— 对标都有仓库内配置（`.coderabbit.yaml` / `.greptile/` / `.pr_agent.toml` per-directory）；我们只有全局 `application.yml`。
6. **增量审查** —— CodeRabbit 默认开、PR-Agent 可选开；我们没有（但注意 PR-Agent 默认也是全量，所以这条没有想象中紧迫）。

---

## 6. 未能核实与补做计划

### 已在第 3bis 节补齐（原为缺口，现已完成）

reviewdog / Danger JS / SonarQube / Semgrep 四个系统**已通过替代取证路径完成调研**：GitLab 自建镜像读 reviewdog 完整 README 与源码、`unpkg.com` 读 Danger JS 的 npm 产物源码、GitBook 文档站加 `.md` 读 SonarQube 与 Semgrep 官方文档。

GitHub Copilot code review / Cursor Bugbot / Ellipsis / Sweep 四个系统**也已完成**：关键突破是发现 **`docs.github.com` 的文档路径后加 `.md` 会返回干净 markdown**，Cursor 与 Ellipsis 同理。**Sweep 经核实后判定不属于代码审查产品**（详见第 3ter 节）。

### 仍然未能核实

| 系统 / 条目 | 原因 | 影响 |
|---|---|---|
| **Sweep 的评审相关格子** | `docs.sweep.dev` / `sweep.dev` **DNS 不可解析**；GitLab 镜像只有归档仓库，其中**不含 PR 评审产品**（`on_review.py` 是桩函数） | **已判定 Sweep 不属于代码审查产品**，从对标范围移除，而非"未核实" |
| **Cursor Bugbot / Copilot 的评论预算数字** | 官方未公开 | 评论数上限只能参考 Ellipsis（64/64）与 reviewdog（30/10） |
| **Cursor Bugbot / Copilot / Ellipsis 是否支持"在 diff 行上 @bot"** | 三家均未文档化行级触发 | 无法确认它们是否真的不支持，只能说**文档中没有** |
| **PR-Agent 的 M6 / M9 / M10** | 配额中断，负责的子 agent 失败 | 跨文件 finding 落点、评论数上限（`max_number_of_suggestions`）、token/cost 上报未证实 |
| **SonarQube 汇总评论的生命周期** | **官方文档确实未写明**（已用其文档站内建 `?ask=` 端点交叉确认，回答原文："doesn't specify whether its PR summary comment is edited or recreated, or describe a deduplication mechanism"） | 无法得知它是编辑还是新建汇总评论 |
| SonarQube 旧的五态生命周期 | 常见说法里的 `Open/Confirmed/Reopened/Resolved/Closed` + `Won't Fix` **在当前文档中已不存在**（现为 `Open`/`Accepted`/`False positive`/`Confirmed` + Reopen） | **请勿照抄旧状态名** |
| Semgrep 是否编辑旧评论、如何识别自己的评论 | 官方文档未写明 | — |
| Semgrep 的 `Block merge` 在 GitHub 上的具体实现 | 官方文档未写明是否 status check | — |
| reviewdog 各 reporter 的 UI 呈现差异 | 投递机制已确证，UI 呈现未实机运行验证 | — |
| Danger **Ruby 版** flag 名与默认值 | 只验证了 danger-js 12.3.3 | 本项目中不适用（我们用 Java） |

### 补做计划

1. **PR-Agent 补齐 3 项**（唯一还剩的实质缺口）：`pr_code_suggestions.py`（reflect 打分阈值、`max_number_of_suggestions`）、`algo/run_details.py` + `run_output.py` + `telemetry/`（token/cost 上报与缓存）、`servers/github_app.py` + `agent/pr_agent.py`（事件分发与重复投递防护）。**本地 clone 仍在 `C:\Users\33721\AppData\Local\Temp\pr-agent`，可直接读，不受网络限制。**
2. **Sweep 若要重新纳入**：需先确认其当前的代码审查产品线是否存在及文档地址（本次 DNS 不可解析，只能读到 2024 末的归档仓库）。
3. **补 Cursor Bugbot / Copilot 未公开的评论预算数字**：只能等官方文档更新。

**收口结论**：本文件覆盖 **11 个系统 × 10 机制**。原计划的"12 系统"中，**Sweep 经核实不属于代码审查产品**，因此不是"未完成"，而是**范围修正**。
