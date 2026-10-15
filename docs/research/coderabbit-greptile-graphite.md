# 生产级 AI Code Review 系统机制调研（CodeRabbit / Greptile / Graphite AI Reviews）

> 目的：为「Java 实现的 GitHub PR 审查机器人」设计文档提供机制参考。
> 证据口径：**只采用官方来源**（官方文档站、官方 changelog、官方博客）。三家文档站均为 Mintlify，`.md` 后缀可直接取到干净正文，因此绝大部分结论是原文引用而非二手转述。
> 规则：凡官方文档未写明的行为，一律标注 `未公开`，不做推测。

> **命名与站点更正（重要）**：Graphite 的 AI 审查能力原名 **Diamond**，该名称已于 **2025-10-08 弃用**，Diamond（AI reviewer）与 Graphite Chat 合并为 **Graphite Agent**；官方文档与博客已不再使用 Diamond 一词（旧博客页顶有弃用横幅）。同时 `graphite.dev` 现已 **301 跳转到 `graphite.com`**，文档在 `https://graphite.com/docs/...`，其 `/changelog` 为 404（changelog 实为 `https://graphite.com/blog?category=changelog`）。本文中「Graphite AI Reviews」= 原 Diamond。

---

## 一、对照矩阵（10 个机制 × 3 个系统）

| # | 机制 | CodeRabbit | Greptile | Graphite AI Reviews |
|---|---|---|---|---|
| 1 | **跨 push 去重** | 靠**增量审查 + 主动 resolve 已修复线程**，非指纹：再次审查时「checks its open threads and resolves those that the changes addressed」 `[6]`；增量审查「tracks what's new since the last review」「without repeating resolved comments」 `[5]` | **未公开指纹机制**；文档化的是**已处理状态跟踪**「marks the corresponding review comments as addressed」 `[32]`，API 暴露 `addressedComments[]`/`unaddressedComments[]`/`hasNewCommitsSinceReview` `[31]`；持久 summary 评论带 Review counter 与 Last reviewed commit `[25]` | **未公开**。无去重/指纹/编辑持久评论的任何描述 |
| 2 | **新 push 语义** | 默认增量：`auto_review.auto_incremental_review` 默认 `true`，逐 push 重审且仅聚焦「the commits added since the last review」 `[3]`；手动 `@coderabbitai review` = 增量，`@coderabbitai full review` = 全量 `[8]`；`auto_pause_after_reviewed_commits` 默认 5 后暂停 `[3]` | 触发事件已文档化（`autoReview: ["open","push","rebase"]`，各自蕴含前者） `[28]`；但**重审是全量还是 delta、diff baseline 是什么：`未公开`**；唯一明示 baseline 的是 CLI：「compares the current branch with the repository's default branch」 `[34]` | **未公开**。仅在「PR 状态」里区分 Running / Completed / Not running `[22]` |
| 3 | **`suggestion` 块** | **是**。可提交条件被精确文档化：「a fenced `suggestion` block inside a comment anchored to real lines on the **new** side of the diff, with a valid start-to-end range」；挂在被删除行或无行锚点则不可应用；仅 GitHub 与 GitLab `[10]`；另有 `reviews.enable_prompt_for_ai_agents` 生成「🤖 Prompt for AI Agents」块 `[1]` | **是**。MCP 文档给出真实评论体：`"```suggestion\n...\n```"` 配 `hasSuggestion: true` / `suggestedCode`；「Most comments include a code suggestion you can apply」 `[31][25]`。**触发条件与开关：`未公开`**（`fixWithAI` 是给 coding agent 的提示，不是 suggestion 块） `[27]` | **极可能是 GitHub 原生**，但文档本身不写围栏：docs 只写「adds a comment directly on the relevant lines」与「choose to commit suggestions — just like your teammates'」 `[22]`；官方 features 页展示的 UI 为 **"Suggested change"**（+/- diff）+ **"Commit suggestion"** 按钮 `[43]`。「Suggested fixes」按套餐提供（Starter 无） `[44]`。**围栏语法与触发条件：`未公开`** |
| 4 | **误报抑制 / 学习** | 最完整。① **Learnings**：在 PR 里用自然语言回复 `@coderabbitai` 即生成 org 级学习记录，回复评论会追加 **Learnings added** 折叠区块 `[12]`；② `knowledge_base.learnings.scope` = `local`/`global`/`auto`，`approval_delay` 0–30 天 `[12][1]`；③ 源 PR 的 learning「remains available only to that source pull request」直到合并 `[12]`；④ 写入时凭证脱敏；⑤ API `GET/PATCH/DELETE /v1/learnings` `[13]` | ① **👍/👎 反应**：「Only 👍 and 👎 train the system. Other emojis are treated as neutral.」 `[29]`；② 文档化抑制流程「Ignored 3+ Times? → Critical Bug? → Suppress Comment Type」，安全类永不抑制 `[35]`；③ 官方博客给出实现：对历史评论做 embedding，与 ≥3 条被 👎 的评论余弦相似度超阈值则**拦掉该评论** `[36]`；④ 配置键 `strictness`/`commentTypes`/`ignorePatterns`/`disabledRules` `[28][27]` | ① **Exclusions**（自然语言，说明「不该评论什么」，可限定仓库/目录/模式） `[21]`；② **Custom rules** 与文件式规则，二者都有指标 → 用指标判断规则是否值得保留 `[21]`；③ 👍/👎 反应（主要针对 experimental 评论）；**Graphite 里 👎 会弹窗追问原因** `[24]`；④ **无** repo 内 AI 规则配置文件（见 #7） |
| 5 | **大 PR 处理** | **有硬性文件上限**：`Files/review` 按套餐 Free/Essentials 150、Team/Advanced/Enterprise 300；且「Reviews with more than 300 files are not supported」 `[9]`；超限时可 offer「Review on demand using usage pricing」 `[9]`；排除默认忽略路径（dist、lock、生成代码、二进制…） `[14]` | **未公开**。明确的反向证据是旧限制被取消：「Previously saved review and auto-approval file limits no longer apply.」 `[27]`。唯一数值启发式是「Files changed is More than 30 → 提到 Apex 档」 `[33]`；其余是行为建议（拆 PR、`ignorePatterns`，可提速 30–50%） `[28][37]`；耗时 Small 1–2 / Medium ~3 / Large 3–5 分钟 `[26]` | **有硬性字符上限**：PR 状态会显示「Not running: The PR won't be analyzed (e.g., if the PR exceeds **200,000 characters**)」 `[22]`。**分块与部分审查报告：`未公开`** |
| 6 | **跨文件发现** | 发布在 walkthrough 评论的 **Review details → Additional context used**，按 linked repository 分组；**Review info** 另列出考虑过的仓库并标注 `manual`/`auto-detected`；「Findings also surface in inline review comments and comment replies when relevant」 `[15]`。跨仓需 `knowledge_base.linked_repositories`（`repository` + `instructions`，≤20 条） `[15]`。安全侧另有 Architecture Review，产出 Minimal→Critical 风险标签与留存关注点 `[16]` | 发布为「PR summary + inline comments」，summary 含 `issuesTableSection`（全部问题表）、`summarySection`、`confidenceScoreSection`、`sequenceDiagramSection`，各有 `{included, collapsible, defaultOpen}` `[25][27]`。能力主张：「Surfaces impacted callers and contracts」「Detects cross-file inconsistencies」，基于预建 codebase graph 查询 `[38][39]` | **仅 inline**：「adds a comment directly on the relevant lines of code」是文档中唯一输出位置 `[22]`；PR 级只有状态提示「它会显示 Graphite Agent 是否发现了问题」 `[46]`。**关键：仓库级上下文对 AI 审查的收益被官方明确标注为「*(coming soon)*」**，即**文档口径下尚未上线** `[23]`；marketing 页的「analyzes your entire codebase」不可作为已实现证据 `[20]`。**多文件/架构级发现的发布通道：`未公开`** |
| 7 | **状态 / 记忆持久化** | 服务端。Learnings 存「an internal database that it associates with your Git platform organization」 `[12]`；API 文档点名存储为 **PostgreSQL**（canonical learning）与 **LanceDB**（embedding 索引）以及 patterned-learning caches `[13]`；配置来源链为 Workspace overrides → Org overrides → Repo `.coderabbit.yaml` → Central YAML(`coderabbit` 仓库) → Repo UI → Org UI → Workspace → Defaults `[17]`；`knowledge_base.opt_out: true` 立即删除全部留存数据 `[18]` | 自托管文档给出完整存储：**PostgreSQL + pgvector** 存「Repository metadata and summaries; Code embeddings; Review history and analytics; User accounts and settings」，Redis 做「Caching, rate limiting」；索引 worker 为 `greptile-indexer-chunker` / `-summarizer` `[40]`。状态按版本固化：`sectionVersions` =「Immutable version docs was read from」 `[31]`。**图数据库具体实现与再索引触发条件：`未公开`** | **无任何 AI 规则配置文件**：rules/exclusions 全部在 dashboard（UI/DB 侧），存储实现 `未公开` `[21]`。仓库内唯一影响 AI 审查的文件是 **`.gitattributes`** 的 `linguist-generated=true`（且只能排除） `[21]`。注意 `.git/.graphite_repo_config` 等 `.graphite*` 路径属 **CLI 专用**，与 AI 审查无关，不可混用 `[45]`。默认「**doesn't store your code**」，可选 code indexing 才建索引且「deleted within 30 days」 `[23]` |
| 8 | **正式审核结论** | **是，且可配置**。`reviews.request_changes_workflow`（默认 `false`）→ 当审查产生可执行 inline 评论时提交 **REQUEST_CHANGES**，满足条件后自动 **APPROVE**：最新 commit 已完成审查 + 必需线程全 resolved + 无 Pre-Merge Check 失败 `[7]`。`reviews.allow_author_approval`（默认 `true`）可禁止作者自我批准 `[7][1]` | **只 APPROVE（beta）**：`autoApprove.enabled` 默认 `false`，要求「a clean **5/5** Greptile review」+ `riskCeiling`（默认 `low`）+ filters `[30]`。**`未公开` REQUEST_CHANGES**；另有 `statusCheck` 发 GitHub status check（pass/fail） `[27]`。亮点：策略从 **base 分支**读取，「a PR that edits `.greptile` cannot loosen the policy for itself」 `[30][27]`；新 commit 到达即 dismiss 旧 approval `[30]` | **未公开**。三篇 AI 文档均未提及提交 APPROVE/REQUEST_CHANGES |
| 9 | **评论预算与折叠** | ① 预算靠分级：`reviews.profile` = `quiet`（只 inline 最重要的，其余「groups the other review comments in the review summary」）/`chill`（默认）/`assertive` `[1]`；② **折叠是显式配置**：`reviews.collapse_walkthrough` 默认 `true`，「Wrap the walkthrough in a Markdown collapsible section」 `[1][11]`；③ 被抑制评论可上报：`reviews.review_details` 「suppressed comments, etc.」 `[1]`；④ **无评论条数上限** | ① **无评论条数上限**（两份配置参考均无该键） `[28][27]`；② 替代手段：`strictness`、`commentTypes`、`updateSummaryOnly`（「Only update summary, don't post individual inline comments」）、`hideFooter` `[27]`；③ 折叠用 **section 布尔字段**（`collapsible`/`defaultOpen`），**是否真用 `<details>`：`未公开`** `[27]`；④ 降噪主要靠学习：历史基线「~19% were good, 2% were flat-out incorrect, and 79% were nits」 `[36]` | ① **无评论条数上限**；② 替代手段是 **PR 级过滤**（作者/路径/label/标题描述/父分支），明写目的是「Control usage and costs」 `[21]`；③ 折叠：被 `linguist-generated` 标记的文件「Automatically collapsed」且不参与评审 `[21]`；④ **未公开** severity/priority 过滤开关 |
| 10 | **可观测性与成本** | ① `reviews.disable_cache`（默认 `false`）可关缓存；缓存「expires after a maximum of **seven days**」，加密、不用于训练 `[19]`；② **Review Usage 仪表盘**：review events、rate limited（含 blocked/continued 拆分）、Daily Activity、Review Log、per-developer 自适应限速；明确声明是「operational telemetry… **not a billing ledger**」 `[20]`；③ **`Files/review` 与 pr-developer-per-hour 限额公开成表** `[9]`；④ **单次审查 token 数/美元成本：`未公开`** | ① **信用点制**：`1 credit = 1 Base review`，Plus 3、Apex 10 `[33][41]`；「Billing counts completed reviews, not PRs. Skipped reviews don't count.」；review 记在 **PR 作者**账上 `[41]`；② 可设 **flex usage 美元上限** `[41]`；③ Analytics：PRs Reviewed / Avg Merge Time / Addressed rate / Critical bugs caught + upvote-downvote 比，可导出 `[42]`；④ V4 changelog 用「LLM-as-judge」判定评论是否被 addressed `[41]`；⑤ **单次审查 token 数：`未公开`** | ① **AI review dashboard**：Overview 时序指标（Issues found / Issues accepted / **Acceptance rate** / PRs reviewed / **Downvote rate**），可按时间与仓库过滤；Comment feed；Rules & exclusions 逐规则指标 `[22][21]`；② code indexing 为性能选项非成本项 `[23]`；③ **token/成本上报与缓存：`未公开`** |

---

## 二、引用清单

1. Configuration reference — https://docs.coderabbit.ai/reference/configuration
2. PR Walkthroughs — https://docs.coderabbit.ai/pr-reviews/walkthroughs
3. Automatic review controls — https://docs.coderabbit.ai/configuration/auto-review
4. PR Summaries — https://docs.coderabbit.ai/pr-reviews/summaries
5. Pull Request Reviews — https://docs.coderabbit.ai/overview/pull-request-review
6. Request changes workflow — https://docs.coderabbit.ai/pr-reviews/request-changes-workflow
7. Review commands — https://docs.coderabbit.ai/reference/review-commands
8. Manage code reviews（commands 指南）— https://docs.coderabbit.ai/guides/commands
9. Plans and pricing（含 `Files/review` 与限额表）— https://docs.coderabbit.ai/management/plans
10. Change Stack — Review and merge — https://docs.coderabbit.ai/change-stack/reviewing
11. Change Stack — Understand findings — https://docs.coderabbit.ai/change-stack/findings
12. Learnings — https://docs.coderabbit.ai/knowledge-base/learnings
13. Learnings API（update / delete）— https://docs.coderabbit.ai/api-reference/learnings-update
14. Path-based review instructions — https://docs.coderabbit.ai/configuration/path-instructions
15. Multi-Repo Analysis — https://docs.coderabbit.ai/knowledge-base/multi-repo-analysis
16. Security Architecture Review — https://docs.coderabbit.ai/security/architecture-review
17. Configuration inheritance — https://docs.coderabbit.ai/configuration/configuration-inheritance
18. Knowledge Base overview — https://docs.coderabbit.ai/knowledge-base
19. Caching — https://docs.coderabbit.ai/reference/caching
20. Review Usage dashboard — https://docs.coderabbit.ai/management/review-usage-dashboard
21. Graphite — Customization — https://graphite.com/docs/ai-review-customization
22. Graphite — Review comments（含 200,000 characters 上限）— https://graphite.com/docs/ai-review-comments
23. Graphite — Code Indexing — https://graphite.com/docs/code-indexing
24. Graphite — Experimental comments — https://graphite.com/docs/ai-review-experimental-comments
25. Greptile — Anatomy of a Review — https://www.greptile.com/docs/code-review/first-pr-review
26. Greptile — Developer Essentials — https://www.greptile.com/docs/code-review/developer-essentials
27. Greptile — `.greptile/` File Reference — https://www.greptile.com/docs/code-review/greptile-config-reference
28. Greptile — Controlling Nitpickiness — https://www.greptile.com/docs/code-review/controlling-nitpickiness
29. Greptile — Training the Learning System — https://www.greptile.com/docs/code-review/training-the-learning-system
30. Greptile — Auto-approve PRs — https://www.greptile.com/docs/code-review/auto-approve-prs
31. Greptile — MCP Tools Reference — https://www.greptile.com/docs/mcp-v2/tools
32. Greptile — Fix with your Agent — https://www.greptile.com/docs/integrations/fix-with-your-agent
33. Greptile — Review Tiers — https://www.greptile.com/docs/code-review/review-tiers
34. Greptile — Greptile CLI — https://www.greptile.com/docs/code-review/greptile-cli
35. Greptile — Reducing Nitpicks — https://www.greptile.com/docs/how-greptile-works/nitpicks
36. Greptile 官方博客 — How to Make LLMs Shut Up — https://www.greptile.com/blog/make-llms-shut-up
37. Greptile — Troubleshooting Common Issues — https://www.greptile.com/docs/troubleshooting/common-issues
38. Greptile — Graph-based Codebase Context — https://www.greptile.com/docs/how-greptile-works/graph-based-codebase-context
39. Greptile — Key Features — https://www.greptile.com/docs/code-review/key-features
40. Greptile — System Architecture（自托管）— https://www.greptile.com/docs/system-architecture
41. Greptile — Billing — https://www.greptile.com/docs/code-review-bot/billing-seats
42. Greptile — Analytics Dashboard — https://www.greptile.com/docs/analytics

---

## 三、值得借鉴的机制

### CodeRabbit

- **Learnings（自然语言反馈 → 可检索的团队记忆）** — 用户在 PR 里用大白话回复评论即被抽取成一条 learning，并显式回一个「Learnings added」折叠块让用户知道「你的话改变了它今后的行为」；再用 `scope`（local/global/auto）+ `approval_delay` 控制作用域与审批。值得借鉴的原因是：它把「误报抑制」从配置负担变成了对话副产品，同时又给了组织一个可审计、可回滚（Dashboard 编辑/删除、`/v1/learnings` API）的治理面——我们若做 Java bot，可以把「fingerprint 抑制表」升级成「自然语言偏好表 + 审批延迟」。
- **路径级指令与全局强制的分离（`path_instructions` / `path_filters` / Global Overrides）** — `reviews.path_instructions[]`（`path` glob + `instructions`，后者上限 20000 字符）负责「怎么审」，`path_filters`（`!` 前缀排除）负责「审不审」，再加一层 Global Overrides 让管理员强制覆盖所有仓库。值得借鉴的原因是：三者职责正交，避免了「一个 ignore 配置既管范围又管规则」的经典混乱；尤其 `path_filters` 同时影响 `git sparse-checkout` 这一点，提示我们文件过滤应从 clone 阶段就生效以省成本。
- **评论预算用「档位」而非「条数」表达（`profile: quiet/chill/assertive`）** — `quiet` 明确承诺「只把最重要的 inline，其余归入 review summary」。值得借鉴的原因是：单纯的 `max_comments` 会丢掉信息，而「inline 与 summary 分级投放」既压住了噪音又保留了完整性，正好可以映射到我们 bot 的 severity → 投放位置路由。
- **正式结论是状态机而非一次性动作（request_changes_workflow）** — REQUEST_CHANGES 与 APPROVE 不是两个命令，而是一组可验证前置条件（最新 commit 已审 + 线程全 resolved + pre-merge checks 无失败），并且**批准前重新校验 HEAD 未变**。值得借鉴的原因是：这套「approve 前重验 HEAD」的防竞态设计，正是我们做审查状态机时最容易漏掉的一环。
- **把「已解决的评论」作为去重的一等信号** — 它不做指纹，而是在下一次审查时主动 resolve 被本次改动修掉的线程，并让 `@coderabbitai resolve/approve` 支持批量收敛。值得借鉴的原因是：实现代价远低于 finding fingerprint，且天然避免了指纹漂移（同一问题因代码位移而换指纹）的问题。

### Greptile

- **用 embedding 相似度做「同类误报」拦截（官方博客）** — 对历史评论按团队做向量化，若新评论与 ≥3 条被 👎 的评论余弦相似度超阈值就拦掉，反之与 ≥3 条 👍 相似则放行。值得借鉴的原因是：这是**语义级**去重/抑制，比行号或哈希指纹更抗代码漂移，且能跨 PR 生效——对 Java bot 而言只需一个 pgvector 表即可落地。
- **评论「已处理状态」作为可查询的一等 API 字段** — `reviewAnalysis` 暴露 `commitsSinceLastReview` / `lastReviewDate` / `hasNewCommitsSinceReview` / `addressedComments[]` / `unaddressedComments[]`，`search_greptile_comments` 还能 `includeAddressed`。值得借鉴的原因是：它把「这条评论还在不在」变成可编程事实，从而让增量审查、去重、成本控制都建立在同一份状态上，而不是各自猜测。
- **审查档位与成本显式绑定（Base 1 / Plus 3 / Apex 10 credits；`effort: auto`）** — 档位可按 org / 仓库 / 目录（级联，取最高档）/ filter 规则 / 单 PR 命令覆盖，且「计费按完成的 review 计数，skipped 不计」。值得借鉴的原因是：它给「大 PR 怎么办」提供了一个比「截断」更有产品感的答案——**为复杂度付费**，同时用「只升不降的单 PR 覆盖」防滥用。
- **审批策略从 base 分支读取（防自放宽）** — `autoApprove` 是唯一从 PR **base** 分支而非 source 分支读取的设置，因此「a PR that edits `.greptile` cannot loosen the policy for itself」；并且新 commit 一到就 dismiss 旧 approval。值得借鉴的原因是：这是把安全边界写进配置读取语义里的范例，我们的 bot 也应在批准类策略上采用 base 分支口径。
- **折叠能力按 section 声明（`included` / `collapsible` / `defaultOpen`）** — 每个输出段（summary / issues table / confidence / sequence diagram）独立可折叠，而非全局一个开关。值得借鉴的原因是：长评论的噪音问题本质是「哪一段该收起」，粒度高一个层级就够用。

### Graphite AI Reviews

- **大 PR 闸门先扣除生成文件，再按字符数判定（200,000 characters）** — 文档把两件事拆开：`linguist-generated=true` 的文件「Excluded from AI review when determining if a PR is too large」并「Skipped during the AI review process」；只有扣完之后的体量才去撞 200k 字符上限，且状态里直接写明「Not running」并给出原因。值得借鉴的原因是：比单纯的文件数上限更贴近真实评审成本（一个巨大的 lockfile 不该消耗额度），且**明确告知未审查**而不是静默降级。
- **规则/排除的效果回流成指标，用数据决定留哪条规则** — 每条 custom rule 有 issues found / accepted issues / **acceptance rate** / upvote-downvote，每条 exclusion 有 issues checked / issues caught / percentage caught：规则本身被当作可评估的实验对象。值得借鉴的原因是：这套指标闭环让「误报抑制」从直觉调参变成可度量的迭代，直接可用于我们 bot 的规则生命周期管理。
- **规则书写规范被当作产品能力来教（规则 → 反例 → 正例 → 理由；禁用非指示性动词）** — 文档明确列出坏规则（「Don't suggest performance improvements」过宽；「comment on」「flag」属非指示性动词）与好规则（含 bad/good 代码对与 reasoning）。值得借鉴的原因是：LLM 审查系统的输出质量上限很大程度由「用户怎么写规则」决定，把提示工程规范产品化是低成本高收益的设计。
- **成本控制内建为 PR 级过滤维度** — 可按作者 / 文件路径 / label / 标题与描述 / 父分支筛选是否运行 AI review，官方给出的动机之一就是「Control usage and costs」。值得借鉴的原因是：对按量计费或自托管的 Java bot，这比事后限流更可控，且过滤维度正好是 webhook payload 里现成的字段。
- **默认不存储代码，仓库级索引是显式 opt-in 且有 TTL** — 「By default, Graphite doesn't store your code」，任何展示都回源 GitHub；索引可选开启，「All code stored in the index is deleted within **30 days**」。值得借鉴的原因是：对企业客户，这是最容易通过安全评审的姿态；我们的 bot 若需缓存代码上下文，也应给出显式开关与保留期。

---

## 四、无法证实的问题

以下为「官方来源无法确证」的组合。标注 `未公开` 表示官方文档通篇没有该行为的描述。

**CodeRabbit**
- **评论去重的具体实现（是否有 finding ID / 指纹）**：`未公开`。文档只描述效果（「without repeating resolved comments」「resolves those that the changes addressed」）而不描述标识机制。不足以支撑我们照搬的原因：无法判断它是靠语义匹配、行锚点，还是内部 ID——这正是我们设计 fingerprint 时最想知道的。
- **单次审查的 token 数与美元成本**：`未公开`。只有 review 次数限额与配额档位（`@coderabbitai rate limit`）、Review Usage 遥测。缺口影响：无法从 CodeRabbit 反推「一次审查的合理 token 预算」。
- **`learnings` 被自动推断的确切触发信号**：仅见「Which types of suggestions your team typically accepts or rejects」，未文档化是否读取 GitHub 的 👍/👎 reaction。缺口影响：我们无法确认「reaction 驱动的学习」在 CodeRabbit 是否成立，只能确认 Greptile/Graphite 有。

**Greptile**
- **重审是全量还是仅 delta、以及 diff baseline**：`未公开`。触发事件 `open`/`push`/`rebase` 有完整文档，但没有任何一句说明重审的比对基准（唯一明示 baseline 的是 CLI：与仓库默认分支比）。缺口影响：这是最关键的空白，直接决定我们是否需要在设计里引入「上次审查 commit」这一状态位。
- **`suggestion` 块的触发条件与配置开关**：`未公开`。只知存在与 `hasSuggestion`/`suggestedCode` 字段，不知何时生成、能否关闭。
- **评论条数上限 / 字面 `<details>` 用法**：`未公开`。折叠只能通过 section 布尔字段表达，无法确认渲染出的 HTML 形态。
- **REQUEST_CHANGES 结论**：`未公开`。全部文档只描述 APPROVE（beta 自动批准）与 status check。
- **图数据库实现（是否 Neo4j）与再索引的触发条件**：`未公开`。自托管文档只写「Graph Storage — Stores the complete graph for instant querying」，未点名产品；`context.repos`/Repo Cluster 与知识库的重建时机也未文档化。
- **REST API `POST /v2/review` 的参数（`genius` / `confidence` / `notify`）**：**无法证实**。公开文档站没有 API reference 页（sitemap 中不存在，`/docs/api-reference/introduction` 重定向回 introduction）；`api.greptile.com` 的报错把文档主机指为 `docs.greptile.com`，但该主机在本环境网络不可达。已确认的只是路由存在：`GET https://api.greptile.com/v2/review` 返回 `No route matches GET /v2/review.`。**建议从普通浏览器网络重试 `https://docs.greptile.com`。**
- **两个登录网关页面可能藏着 #1/#2 的答案**：`/docs/code-review-bot/trigger-code-review`（被计费页引用）与 `/docs/integrations/github-gitlab-integration` 均重定向到 `/docs/login?redirect=...`，且不在公开 sitemap 中。
- **文档自相矛盾一处（需注意）**：`strictness` 在配置文件参考中写明「Must be 1, 2, or 3」，而 MCP 工具参考的示例把它描述为「Review strictness level (1-5)」。我们会按配置参考的 1–3 处理，但引用时需注意此冲突。

**Graphite AI Reviews**
- **跨 push 去重机制**：`未公开`。三篇 AI 文档均无任何「不重复报告」「编辑既有评论」「finding 指纹」的描述。
- **是否使用 GitHub 原生 ```suggestion``` 围栏**：`未公开`。只能确认评论「含具体修法」且可一键 commit suggestion，无法确认底层是否为 suggestible block。
- **多文件 / 跨文件发现的发布位置**：`未公开`。文档只描述 inline comment；虽然另有 code indexing 能力，但其对 AI review 的收益被明确标注为「*(coming soon)*」，因此**不能**据此推断它已具备仓库级跨文件审查。这是三家对比中信息最薄的一栏。
- **正式审核结论（APPROVE / REQUEST_CHANGES）**：`未公开`。
- **状态持久化位置与 AI 规则的存储形式**：仅知「默认不存代码」与可选索引（30 天 TTL）；**自定义 rules / exclusions 存在何处（纯 UI 还是 DB）未文档化**，也未文档化是否存在仓库内配置文件——需注意 Graphite 的 CLI/stack 配置（`.graphite` 相关）属于产品其它部分，**不**适用于 AI review 规则，不可混用。
- **大 PR 的分块策略与部分审查报告**：`未公开`。只知道超过 200,000 字符直接「Not running」，不知是否分批、是否部分出结果。
- **token 与成本上报、缓存**：`未公开`。只有按规则/类别的接受率指标，无成本或 token 口径。

---

## 五、对 Java PR Review Bot 的直接启示（择要）

1. **去重不要只做指纹**：CodeRabbit（open-thread resolve）+ Greptile（embedding 相似度拦截 + `addressedComments` 状态）说明更稳的组合是「**状态字段为主 + 语义相似度为辅**」。
2. **增量审查必须有显式的 baseline 状态位**：CodeRabbit 明确按「since the last review」的 commits 增量；Greptile 恰恰**没有**文档化这一点，是我们应当避免的模糊区。
3. **配置分三层、权限分明**：`path_filters`（审不审）/ `path_instructions`（怎么审）/ Global Overrides（强制）。批准类策略一律从 **base 分支**读取（Greptile）。
4. **大 PR 闸门：先剔除生成文件，再按体量判定，超限要显式告知**（Graphite 的 `linguist-generated` + 200k 字符 + 「Not running」状态）。
5. **评论预算用「投放位置分级 + 可折叠 section 字段」表达**，不要只做条数截断（CodeRabbit `profile` + `collapse_walkthrough`；Greptile `collapsible`/`defaultOpen`）。
6. **学习闭环必须可观测可回滚**：Learnings 的 scope/approval_delay/API 与 Graphite 的 acceptance rate 指标，共同指向「偏好是数据资产，需要治理面」。
