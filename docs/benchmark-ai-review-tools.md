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
> **本次会话网络受限**：`github.com` 与 `raw.githubusercontent.com` 被 DNS 策略屏蔽，`docs.github.com` 抓取只返回导航壳。
> 因此 PR-Agent 是通过**本地完整 clone**（`C:\Users\33721\AppData\Local\Temp\pr-agent`，commit `330f0813b5fe0d1f54b794077c2d02aa85a1fb63`）读源码完成的；
> 其余开源项目（reviewdog / Danger / SonarQube / Semgrep）**源码级核实全部失败**，明确标注为【未】。
>
> 可靠覆盖：**PR-Agent（源码）、CodeRabbit、Greptile、Graphite（官方文档）** = 4 个系统 × 10 机制。
> 未覆盖：reviewdog、Danger、SonarQube、Semgrep、Codacy、Cursor Bugbot、Sweep、Ellipsis（见第 5 节补做计划）。
>
> **命名更正（重要）**：Graphite 的 AI 审查能力原名 **Diamond**，该名称已于 **2025-10-08 弃用**，Diamond 与 Graphite Chat 合并为 **Graphite Agent**；`graphite.dev` 现已 **301 跳转到 `graphite.com`**。本文中「Graphite」一律指 **Graphite AI Reviews**（原 Diamond）。
>
> **原始调研附录**（含逐条编号引用的完整出处，本文是其整合版）：
> - [`docs/research/pr-agent-deep-dive.md`](research/pr-agent-deep-dive.md) —— PR-Agent 源码级深读（本地 clone，commit `330f081`）
> - [`docs/research/coderabbit-greptile-graphite.md`](research/coderabbit-greptile-graphite.md) —— CodeRabbit / Greptile / Graphite 官方文档调研（45 条编号引用）

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

| 系统 | 原因 | 影响 |
|---|---|---|
| reviewdog / Danger / SonarQube / Semgrep / Codacy | `github.com`、`raw.githubusercontent.com` 被 DNS 策略屏蔽；`go-mod-viewer` 镜像 fetch 失败 | **失去"确定性工具"整组参考**，而这一组与我们的架构最像。是本次第二大缺口 |
| Cursor Bugbot / Sweep / Ellipsis | 未开始 | 影响较小 |
| GitHub Copilot code review | `docs.github.com` 只返回导航壳 | verdict 策略只有一条低置信度社区说法 |
| PR-Agent 的 M6/M9/M10 | 配额中断，负责的子 agent 失败 | 跨文件 finding 落点、评论数上限、token/cost 上报未证实 |

### 补做计划

1. **放开网络白名单**（`github.com`、`raw.githubusercontent.com`、`pkg.go.dev`），或改走 `api.github.com` 读取源码。
2. **PR-Agent 补齐 3 项**：`pr_code_suggestions.py`（reflect 打分阈值、`max_number_of_suggestions`）、`algo/run_details.py` + `run_output.py` + `telemetry/`（token/cost 上报与缓存）、`servers/github_app.py` + `agent/pr_agent.py`（事件分发与重复投递防护）。
3. **reviewdog 定向**：四种 reporter（`github-pr-check` / `github-pr-review` / `github-check` / `github-pr-annotations`）差异，以及如何只报 diff 内新问题。
4. **SonarQube 定向**：`New Code` 定义、issue 生命周期（Open→Confirmed→Resolved→Closed→Won't Fix）、为何不重复报已 triage 问题。
5. **Semgrep 定向**：`--baseline-commit`、`nosemgrep` 注解、PR 评论输出模式。

补齐后本文件升级为完整的 **12 系统 × 10 机制**矩阵。
