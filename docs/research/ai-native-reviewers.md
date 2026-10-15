# 四个 AI 原生代码评审产品在 GitHub PR 上的行为对比（面向 Java Spring Boot AI PR Review Bot 设计）

> 调研方法说明：本报告的结论只来自可达的一手文档（Cursor `*.md`、Ellipsis `*.md`/OpenAPI schema、GitHub Docs markdown 端点、`github.blog` changelog、GitLab 上 Sweep 归档仓库源码）。厂商宣传性描述已标注「厂商声称」。凡文档未写的一律写 `未公开`，无法验证的写 `无法证实` —— 不做推测性补全。
>
> 版本/时效：Cursor Bugbot、GitHub Copilot code review、Ellipsis 均为 2026 年中的当前文档；Sweep 的可达资料（`docs.sweep.dev` DNS 不可解析、`sweep.dev` 同样不可达）只有归档源码与旧文档树，属于「归档期（约 2024-11 至 2025 初）」，与检索到的 `docs.sweep.dev/ai-code-review` 页面索引不一致，见文末 `## 无法证实的问题`。

---

## 对比矩阵

| # | 机制 | GitHub Copilot code review | Cursor Bugbot | Sweep AI | Ellipsis |
|---|---|---|---|---|---|
| 1 | **跨 push 重复评论规避** | 自动 resolve 已被后续 commit 解决的评论，并在概览评论中把发现分成 Open / Resolved since last review / Previously missed 三组 [3][4]；但官方明确「重新评审时可能重复之前的评论，即使你 resolve 或点了踩」[5]。另有 resolution reason（Addressed / Won't fix / Incorrect）回传产品团队 [6] | 显式机制：把已连接的 PR 评论（顶层 + inline）作为上下文读入，以避免重复建议 [1]；`/review-bugbot` 存 diff 的 patch ID，SCM 端遇到同 patch ID 直接跳过并留一条说明 [1]；增量模式只评审新增部分 [1][2] | 归档文档未见任何评论级去重逻辑；可达源码里 `handlers/on_review.py` 只做 `repo.compare(base, head)` 生成 diff，无状态、无去重 [11] | 强机制且可证：每个 reviewer「只看到它此前没评审过的 commit」，落在这些 commit 之外的评论会被扣下不发（held back）[7][8]；`comments[].status` 有 `outside_range` / `not_in_diff` 状态 [10] |
| 2 | **新 push 语义 / 基线 / 可配置** | 默认只评审一次；「Review new pushes」开关（个人设置 / 仓库 ruleset / 组织 ruleset / 企业策略）才每次 push 都评审 [5][14][15]。基线=整个 PR diff；`未公开` 增量化。注意：用户自己的设置关不掉 ruleset 打开的 push 评审 [14] | 默认「每次 push 重评整个 PR」；`.cursor/config/bugbot.yaml` 的 `review.incremental: true`（默认）改为「只评审自上次评审以来的变更」，也可在 dashboard 关 Incremental Review 恢复全量 [1][2]。触发频率可设 `everyPush` / `oncePerPr` [1] | `未公开`。归档源码每次都对 `base.sha..head.sha` 全量 compare [11] | 天然可证：每个 reviewer 只见未评审过的 commit，基线是该 reviewer 的 `range_base_sha`（API 字段）[7][10]；reviewer 可用 `paths` 只在新 commit 命中其 glob 时启动 [7]；`pull_requests.*` 过滤器（base/head/draft/labels/paths/for）控制哪些 push 进入 [8] |
| 3 | **GitHub suggestion 一键应用块** | 是。官方描述为「ready-to-apply changes」「apply in a couple of clicks」，支持单条接受与批量接受并生成 commit message [5][3][4] | 文档只说「inline comments with explanations and **suggested fixes**」和 Fix in Cursor / Fix in Web 链接 [1]，未出现 GitHub `suggestion` 语法 → 是否输出可一键应用的 suggestion 块 `未公开` | `未公开` | `未公开`（schema 里 `comments[].body` 仅为字符串，无 suggestion 语义字段）[10] |
| 4 | **误报抑制 / 学习** | ①评论上 👍/👎 + 原因（本次评审的反馈）[5]；②Resolve conversation 旁的新下拉可选 `Addressed` / `Won't fix` / `Incorrect` [6]；③回复「请保持打开」会被尊重 [4]；④可 hide；⑤Copilot Memory（Pro/Pro+/Max，public preview）把学到的仓库细节用于后续评审 [14]。反例证据：回复「对其他协作者可见，但 Copilot 看不到」[5]，且重复评论问题被官方承认 [5] | 多通道：①在任意 PR 评论 `@cursor remember [fact]` 存为 learned rule [1]；②组织级 **Automatically Learn Rules**（从团队 GitHub 活动或仓库历史自动生成规则，可手工 backfill）[1]；③Team rules + 仓库 `.cursor/BUGBOT.md`（含按变更文件向上遍历的嵌套文件）[1]；④规则可被自动启用/停用，并有 Issues found / Accepted issues / Acceptance rate 规则分析 [1]；⑤第三方称学习信号含 reactions/replies/人工评审评论（非官方，次要证据）[16] | 归档机制是「改代码」而非「抑制评论」：仓库评论会重写整个 PR，PR 评论做实质性修改，文件级评论只改该文件 [12]；无 suppression 配置 | 无「评论级」抑制机制。抑制=把要求写进 prompt（每个 reviewer 自己的 `prompt`）或改配置；`ellipsis.enabled: false` 可暂停整个配置文件 [7][8]。评论是 GitHub 普通评论，可用 GitHub 自身 resolve/hide，但无「教它别再报」的文档化通路 |
| 5 | **大 PR 处理 + 覆盖度报告（关键项）** | ①文档化的**排除清单**：依赖锁文件、日志、SVG、`**/dist/**`、`**/node_modules/**`、`**/*.min.js`、`**/*.d.ts`、`**/coverage/**`、`**/vendor/**`、`**/generated/**`、`**/bin/**` 等，命中即「不纳入评审」[13]；②历史上有 **300 文件 / 20,000 行**上限，2026-08-27 已移除 [6]；③另有内容排除（repository/org/enterprise 级路径规则）[15]。**是否显式告诉用户「哪些文件我没看、为什么」→ 未公开**（文档只列排除规则本身，未描述 PR 上的未评审清单） | 有 `review.effort`（low/default/high/smart），high 花更多时间/额度「可能找到更多 bug」（厂商声称）[1]；`prSummary.riskScore` 输出风险分 [1]。文件数/token 上限、分块策略、以及**是否列出未评审文件** → 全部 `未公开` | 归档文档给了明确的能力边界（厂商自述局限）：「超大仓库 >5000 文件」可能卡在 0%，需用 `blocked_dirs` 手动排除；「大规模重构 >3 文件或 >150 行」非其强项；不处理图片等非文本资产 [9]。这是**产品局限说明**，不是「未评审文件清单」；是否有覆盖度报告 → `未公开` | 有**真实的覆盖度数字**，且是四者中唯一把「看了多少」结构化的：API 的 `reviewers[]` 返回 `reviewed_files`、`reviewed_lines`，评论带 `outside_range` / `not_in_diff` 状态，被扣下的评论仍保留在 review 记录里 [10]。文档化上限：**一次 review 最多发 64 条评论、每个 reviewer 最多返回 64 条**，超限评论「随 review 保留但不发布」（即静默丢弃，不带解释）；超出评论上限的 reviewer 会 fail 且不贡献任何内容 [7][8]。预算上 `budget.session`（美元/次）可逐 reviewer 设 [8] |
| 6 | **跨文件 / 架构类发现落点** | ①「分组评论」把同类问题合并，避免同一变量名建议重复 N 次 [17]；②概览评论汇总所有发现（含 **Previously missed**：不是新 commit 引入、但被后续评审新发现的问题，「因为它们在 PR 上没有别处评论」）[4]；③Balanced 档专门加强「复杂逻辑、安全敏感代码、跨服务变更」[14] | `prSummary.mode: comment \| description \| disabled` —— PR 摘要可发成评论或写进 PR 描述，并带风险分 [1]；实质性发现仍走 inline [1] | `未公开` | Reviewer 之间是**并行独立 session**，各自 prompt/模型/环境，可分工（如迁移 reviewer + 测试 reviewer），最后「合并成一次 review」发布；`output.summary: true` 时每个 reviewer 额外返回 summary 并置于 review body 顶部 [7][8] |
| 7 | **状态 / 记忆持久化位置** | 每个 PR 一条概览评论，随每次评审累积进度与历史发现 [4]；跨 PR/跨仓库的长期记忆是 Copilot Memory（Pro/Pro+/Max，public preview）[14]；`copilot-code-review.yml` 等配置从 **head 分支**读取 [15] | 规则与学习结果存在 Cursor 侧（团队/仓库/组织级），由 dashboard 管理 [1][16]；仓库侧配置文件从 **base 分支**读取，且「不读 head 版本，所以 PR 无法改变它自己被评审的方式」[1]；patch ID 用于 `/review-bugbot` 与 SCM 端同步 [1] | 仓库内 `sweep.yaml`（配置 + rules）[9]；归档 FAQ 称运行时访问代码，结束后删除，检索用 hashed 内容 + embeddings，不存明文 [12] | 平台侧：每个 reviewer 的 `range_base_sha` / `head_sha`、每条评论及其状态、每次 review 的 `cost`（cpu/memory/fee/llm，按模型拆分）与 `tokens`（input/output/cache_read/cache_creation，按模型拆分）都可通过 `GET /v1/reviews/{id}` 读回 [10]；配置在仓库/组织的 `.ellipsis/code-review.yaml`，**从默认分支读取**，解析失败则保留上一份可用版本并报 sync 错误 [7][8] |
| 8 | **正式评审结论（APPROVE / REQUEST_CHANGES / 仅评论）** | **默认发 "Comment" review，不是 Approve 也不是 Request changes**，因此默认不计入必需批准数 [5]；可配置 Copilot 发 Approve（Auto-approval：允许审批 + 计入合并要求 + 路径 glob，public preview）[15]，并支持企业/组织/仓库三级策略 [14][15]。**Request changes：文档未提供该选项 → `未公开`（四者中唯一明确「不能要求修改」的）** | 不发 GitHub APPROVE/REQUEST_CHANGES；结论以 **check run** 表达：GitHub 上名为 `Cursor Bugbot`，conclusion 为 `success`（无问题且无历史未解决评论）/ `neutral`（默认，含发现问题、被新 commit 取消、内部错误）/ `failure`（配置了 unresolved 即失败时）[1]。可用分支保护 required check 阻断合并（需先启用 fail-on-unresolved-issues）[1] | `未公开`（归档源码无提交 review 的逻辑） | **四者中结论能力最完整且全部可配置**：`output.on_pass` ∈ `silent`(默认) / `comment` / `approve`；`output.on_fail` ∈ `comment`(默认) / `request_changes` [7][8]；API `event` 字段实际枚举 `COMMENT \| APPROVE \| REQUEST_CHANGES` [10]。判定口径由 prompt 决定（要求 reviewer「仅在不可原样合并时标 blocking」）[8]。细节：reviewer 提前停止的 review 永不 approve；Ellipsis 自己开的 PR 一律只能发评论（GitHub 不允许 PR 作者给自己 approve / request changes）；无 `output` 时 pass 静默、fail 发评论 [7][8] |
| 9 | **评论预算与折叠 / 优先级** | ①severity 标签 High/Medium/Low [17][4]；②同类评论合并成一条（大 PR 上尤其明显）[17]；③概览评论按 Open / Resolved / Previously missed 分组 + 每条标题 + 严重度 + 跳转链接 [4]；**具体条数上限 / 折叠阈值 → `未公开`** | `prSummary` 摘要、`riskScore` 风险分、effort 档位控制「花多少算力」[1]；**条数上限 / 折叠 → `未公开`** | `未公开` | **数值化上限**：默认每次 review 最多 64 条、每 reviewer 最多 64 条，可用 `output.comments.max_per_review` / `max_per_reviewer` 或 reviewer 级 `comments.max` 调整；超 review 上限的评论保留但不发布；reviewer 超自己上限则整体失败、不贡献 [7][8]。优先级由「blocking / 非 blocking」二分表达（决定 pass/fail 与 review event），无 severity 分级 |
| 10 | **可观测性与成本** | 文档给出**估算区间**：Lite 约 $0.05–$1 AI credits/次，Balanced 约 $0.25–$5；另有 GitHub Actions minutes 成本；按 PR 作者或请求者归属，可改为组织计费；组织/企业可设预算，额度耗尽则评审被阻断 [14]；文档提到可查看 code review 运行消耗的 Actions 分钟数 [14] | **最完整的成本可观测性**：`GET /analytics/team/bugbot-reviews` 按 review 返回 `commit_sha`、`bugs_found`、**`cost_cents`**、每条 finding 的 `comment_id` / `resolution_status` / `severity`；支持 `dryRun`（跑完整分析但不发任何评论，仍计费）[1]。另有规则级分析（接受率）[1]，以及第三方记录的 ~$1.00–$1.50/次均值（非官方）[16] | 归档期无 token/成本上报；`gha_enabled` 控制是否读取 GitHub Actions 结果 [9] | **token 与成本都结构化到 API**：review 级 `cost`（charged/cpu/memory/fee/llm，按模型与订阅来源拆分）与 `tokens`（input/output/cache_read/cache_creation，按模型拆分）[10]；`budget.session` 是硬性美元上限，超预算的 reviewer 不贡献内容 [7][8]；另有账号/agent 级用量端点（`get account usage`、`get agent usage`）与 Observability 页 [18] |
| A | **触发面：diff 行内评论 vs issue/PR 会话评论** | 仅「Reviewers 侧栏 Request / REST API 指定 `copilot-pull-request-reviewer[bot]` / 自动 ruleset / 重新请求按钮」[5][14]；**没有文档化的评论命令触发代码评审**（`@copilot` 是 cloud agent 通道，不是 code review）[19] → 「行内评论触发」为**否**（无文档） | 会话评论命令：`cursor review` / `bugbot run`（含 `verbose=true` 变体）、个人设置可设为 only-when-mentioned [1]。**行内评论触发 → `未公开`**（文档只说「读取含 inline 的评论作为上下文」，未说是触发面）[1] | 归档期以 issue/PR 评论为触发主流：`Sweep:` 前缀重试 issue，PR/文件评论触发改码 [12]；「行内评论触发评审」`未公开` | 触发是**事件驱动**而非「评论即命令」：`pull_requests.on: [opened, pushed, review_submitted, commented, ...]`，其中 `commented` = 「添加了一条评论」，类型上包含行内评论但**文档未显式说明行内评论是否计入** [20]；另外 `@ellipsis` mention（issue/PR 评论）会开一个 session，且「回复继续同一会话，无需重复 mention」[21] |
| B | **多行评论范围** | 2025-07-02 changelog 称「soon including suggestions that span multiple lines of code」[22]；当前文档只讲「suggested changes 可一键应用 / 批量接受」[5] → **多行 range 的具体支持度 `无法证实`**（未找到 2026 年明确表述） | **有证据**：analytics dry-run 返回 `locations: [{file, start_line, end_line}]`，说明发现本身带行区间 [1]；但是否以 GitHub 多行评论发布 → `未公开` | `未公开` | **API 层有完整多行字段**：每条评论含 `line` / `side` / `start_line` / `start_side`，可表达区间评论 [10] |
| C | **PR 过大时的文档化行为** | 2026-08-27 前：>300 文件或 >20,000 行**直接不评审**（上限）；现已「不再适用」[6]。此外：runner/agentic 能力不可用时「降级为更有限的评审（reduced review）」，但仍产出评审 [14][15]；被排除文件类型+路径见 [13] | `未公开`（无任何文件数/行数上限或超限行为文档） | 厂商自述局限：>5000 文件仓库可能卡在 0%（需 `blocked_dirs`）；>3 文件或 >150 行的改动非强项 [9] | **无大小上限，改为按评论数与预算截断**：超 64 条的评论保留但不发布；reviewer 超预算/失败则不贡献且「下次 push 会重新覆盖同一批 commit」[7][8] |

---

## 值得借鉴的机制（面向 Java Spring Boot PR Bot）

### GitHub Copilot code review
- **概览评论做「发现生命周期」账本**：把发现分成 Open（含 `new` 标记：新 commit 引入）/ Resolved since last review / **Previously missed**（非新 commit 引入、本轮才发现的），并给每条 finding 标题、严重度、inline 链接 [3][4]。对 Java 大 PR 尤其有用：Bot 可以在不重发评论的前提下，让用户看到「上轮发现现在什么状态」。
- **自动 resolve + 解决原因枚举**：后续 commit 解决后自动 resolve，且原因只在 `Addressed` / `Won't fix` / `Incorrect` 中选 [6]；这是把「假阳性反馈」变成可统计信号的低成本设计（`Incorrect` 即假阳性）。注意其教训：官方承认重评仍可能重复旧评论、且回复对模型不可见 [5] —— 不要只靠 GitHub 的 resolve 状态做去重。
- **分组评论 + 严重度标签**：同类问题（如同一个命名问题在 PR 里出现多次）只报一次 [17]。Spring Boot PR 常出现同类横切问题（缺 `@Transactional`、日志格式、DTO 校验），按规则指纹聚合是核心降噪手段。
- **声明的排除清单是「覆盖率」的另一半**：先定义「永不评审」的类型/路径（依赖锁文件、`**/generated/**`、`**/*.d.ts`、`**/dist/**` 等）[13]，再谈覆盖率，且支持 org/repo 级内容排除 [15]。**但 Git 侧没有「哪些文件未评审、为什么」的清单** —— 这正是我们可以做得比它好的地方。
- **成本双账本**：AI credits（模型）+ Actions minutes（执行）分开计价，并给出量级区间 [14]；预算耗尽时评审被阻断而不是静默降质（可观测性优先）。

### Cursor Bugbot
- **把「已有 PR 评论」当上下文喂进去**：显式读取顶层 + inline 评论以避免重复建议、并在既有反馈上继续 [1]。对自研 Bot 的最小可用去重策略：把 PR 上所有既有评论（人 + bot）作为 prompt 上下文的一部分。
- **patch ID 去重**：用 `git patch-id` 标识「同一份 diff」，本地评审过就在 SCM 端跳过并留一条说明 [1]。比行号/文件指纹稳健得多（对 rebase、行号漂移免疫），很适合 Java 项目的频繁 rebase。
- **增量 vs 全量收益明确且默认增量**：文档直言默认全量重评「会在它已经看过并认可的代码上重复报新问题」，`review.incremental: true` 只评审上次以来的变更 [1][2]。**这条直接对应我们要解决的「跨 push 重复评论」问题。**
- **规则来源分层且可观测**：Team rules → 项目 `.cursor/BUGBOT.md`（含按变更文件向上遍历的嵌套文件）→ learned rules → manual rules，合并为一个 rules block；单条规则 30k 字符、整体 100k 字符上限，超限会被截断/省略，且可用 `bugbot run verbose=true` 打印「本次用了哪些规则、哪些被截断」[1]。**给用户一张「我到底用了哪些规则」的表，是可解释性的好做法。**
- **`@cursor remember [fact]`**：把一行评论直接变成持久化规则 [1] —— 极低成本的人工纠偏通道，比让用户写 YAML 现实得多。
- **配置文件只从 base 分支读**：防止「PR 改掉评审自身的规则」[1]；对比 Copilot 从 head 分支读 instructions [15]（便于测试但可被 PR 自我削弱），安全敏感场景建议取 Bugbot 的做法。

### Sweep AI
- **规则是「可执行声明」而不是「风格建议」**：`sweep.yaml` 的 rules 被逐条应用到每个 PR，命中即产出修复 PR；且「可以应用到仓库里任意 PR，此时 fix PR 以你的 PR 为目标分支而不是 main」[9]。对自研 Bot 的启发：把「规则命中」与「修复动作」串成同一条链路（评论 + 可选自动修复分支），而不是只留评论。
- **把能力边界写进文档**：仓库 >5000 文件、改动 >3 文件或 >150 行、图片资产，明确列为不擅长场景 [9]。这是**诚实覆盖度声明**的范例 —— 正是我们要做的「告诉用户哪些没看、为什么」的雏形。
- **人工纠偏走自然语言评论，且区分粒度**：issue 评论=重写整个 PR；PR（非文件）评论=较大改动；**文件级评论=只改该文件** [12]。这种「评论挂载位置决定作用域」的约定，比一堆命令更符合用户直觉。
- **注意可对照的失败模式**：归档源码里 `handlers/on_review.py` 只把 `base..head` 全量 diff 交给模型，没有任何评论级状态 [11] —— 如果我们的 Bot 也这么做，就必然在每个 push 上重复报同一问题。

### Ellipsis
- **「每个 reviewer 只看到它没评审过的 commit」+「范围外评论扣下不发」**：这是四者中最干净的去重模型 —— 用「已评审 commit 集合」而非「评论哈希」来定义增量；落在未评审范围外的评论 held back，但仍可通过 API 看到（`status: outside_range` / `not_in_diff`）[7][10]。**强烈建议 Java Bot 采用同构模型：按 commit 区间记账，评论必须锚定在区间内。**
- **blocking 二分驱动正式结论**：每条评论标 blocking / 非 blocking → review pass/fail → 映射到 GitHub `APPROVE` / `REQUEST_CHANGES`，且 `on_pass` / `on_fail` 全可配置 [7][8]。想「只评论不阻断」的团队用 `comment`，想卡门禁的用 `request_changes`，同一个引擎两种策略。
- **覆盖度与成本是一等 API 字段**：`reviewed_files`、`reviewed_lines`、per-review `cost`（含 llm/cpu/memory/fee 与按模型拆分）和 `tokens`（含 cache_read/cache_creation）[10]。**「我看了多少文件/行」直接可查询，是回答「你漏看了什么」的数据基础。**
- **预算即语义**：`budget.session`（$ / 次，逐 reviewer）超限时 reviewer 不贡献内容、且「下次 push 重新覆盖同一批 commit」，绝不留半截评审冒充完整 [7][8]。这种「要么完整要么明说没做完」的态度值得照抄。
- **配置从默认分支读、解析失败保留上一版可用配置并报 sync 错误**：评审配置的「安全回滚 + 错误可见」[7][8]。

---

## 无法证实的问题（question × system）

| 问题 | 系统 | 状态 | 原因 |
|---|---|---|---|
| 2 新 push 语义 | Copilot | 部分无法证实 | 「Review new pushes」开关与基线（整个 PR）有文档 [5][14]，但**没有**任何「只评审增量 diff」的官方选项或文档 |
| 3 suggestion 块 | Bugbot | 无法证实 | `cursor.com/docs/bugbot.md`、help 页、changelog 均只说 "suggested fixes" / "Fix in Cursor"，未出现 GitHub `suggestion` 语法或「一键应用」表述；不排除实际上有 |
| 3 suggestion 块 | Ellipsis | 无法证实 | 代码评审文档与 `GET /v1/reviews/{id}` schema 只把评论表示为 `body: string` [7][10]，无 suggestion 语义；文档站全文搜索不可用（无 llms-full 之外的可检索正文），无法排除 prompt 层面让模型输出 suggestion |
| 3 / 4 / 5 / 7 / 8 / 9 / 10 | Sweep | **大面积无法证实** | ①`docs.sweep.dev` 与 `sweep.dev` 在本环境 DNS 不可解析，检索到的 `docs.sweep.dev/ai-code-review` 页面无法读取；②可达的一手资料只有 GitLab 归档仓库（README/FAQ/config/源码，约 2024-11 至 2025 初），其中**没有** PR 评审产品化的文档；③Sweep 已转向 JetBrains 助手，归档资料与「2026 仍在运营的 AI code review」这一前提相互矛盾 [9][11][12]。因此其 suggestion 块、false-positive 抑制、大 PR 覆盖度、跨文件落点、状态持久化、正式结论一律无法证实 |
| 2 / 5 / 8 / 9 | Sweep | 未公开 | 归档 `sweep.yaml` 只有 `rules` / `gha_enabled` / `branch` / `blocked_dirs` / `draft` / `description`，无任何评审相关键 [9]；源码无对应实现 |
| 5 覆盖度报告 | Copilot | 无法证实（反向证据有限） | 文档给了**排除清单** [13] 和内容排除 [15]，也给了「大 PR 上限移除」[6]，但**没有任何地方**描述「Copilot 在 PR 上告诉用户哪些文件未评审、为什么」。只能说：未公开；是否实际存在无法证实 |
| 5 文件数/token 上限、分块策略 | Bugbot | 未公开 | bugbot.md 只有 effort 档位、规则字符上限（30k/100k）、`prSummary.riskScore`，没有文件数/行数/评论数上限或分块说明 |
| B 多行评论 | Copilot | 无法证实 | 只有 2025-07-02 changelog 的「soon including suggestions that span multiple lines」[22]；2026 年文档未再明确表态，未找到 GA 声明 |
| B 多行评论（发布形态） | Bugbot | 无法证实 | 仅有 analytics 的 `locations{start_line,end_line}` 证明**发现**是区间 [1]，不代表 GitHub 评论用多行 range 发布 |
| A 行内评论触发 | Bugbot / Sweep | 未公开 | 两方文档都只说「读/回复评论」，未把「diff 行内评论」列为评审触发面 [1][12]；Ellipsis 的 `commented` 事件语义未细化到行内 [20] |
| 1 / 4 评论解析与解析原因是否跨 push 生效 | Copilot | 无法证实 | 文档同时说「自动 resolve 已解决评论」[3] 和「重评可能重复已 resolve/点踩的评论」[5]，两者张力无法从文档判定实际行为边界 |
| 9 评论数上限/折叠阈值 | Copilot / Bugbot / Sweep | 未公开 | 三者均无文档化数值上限（只有 Ellipsis 给了 64/64） |
| — | 全部 | 说明 | 本报告未使用任何「不可达主机重试」得来的内容；`docs.github.com` 与 `github.blog` 的 markdown 正文可达（`.md` 端点），`github.com` / `raw.githubusercontent.com` 全程未访问 |

---

## 引用列表

1. Bugbot — Cursor Docs (markdown) — https://cursor.com/docs/bugbot.md
2. Bugbot is now over 3x faster, 22% cheaper, and finds 10% more bugs — Cursor Blog — https://cursor.com/blog/bugbot-updates-june-2026
3. Auto-resolution and analysis updates in Copilot code review — GitHub Changelog (2026-09-11) — https://github.blog/changelog/2026-09-11-auto-resolution-and-analysis-updates-in-copilot-code-review/
4. Copilot code review: An improved review experience — GitHub Changelog (2026-09-18) — https://github.blog/changelog/2026-09-18-copilot-code-review-an-improved-review-experience/
5. Using GitHub Copilot code review on GitHub — GitHub Docs (markdown) — https://docs.github.com/en/enterprise-cloud@latest/copilot/how-tos/copilot-on-github/use-copilot-agents/copilot-code-review.md
6. Copilot code review: Resolution reasons and expanded capabilities — GitHub Changelog (2026-08-27) — https://github.blog/changelog/2026-08-27-copilot-code-review-resolution-reasons-and-expanded-capabilities/
7. Code review — Ellipsis Docs (markdown) — https://www.ellipsis.dev/docs/code-review.md
8. Code review schema reference — Ellipsis Docs (markdown) — https://www.ellipsis.dev/docs/code-review/schema.md
9. Sweep README（归档，含 Limitations）— GitLab mirror — https://gitlab.com/sweepai/sweep/-/raw/main/README.md
10. GET /v1/reviews/{id} — Ellipsis Docs (markdown) — https://www.ellipsis.dev/docs/api/reviews/get-reviews-id.md
11. sweepai/handlers/on_review.py（归档源码）— GitLab mirror — https://gitlab.com/sweepai/sweep/-/raw/main/sweepai/handlers/on_review.py
12. Sweep FAQ（归档，评论粒度 / 数据留存）— GitLab mirror — https://gitlab.com/sweepai/sweep/-/raw/main/docs/pages/faq.mdx
13. Files excluded from GitHub Copilot code review — GitHub Docs (markdown) — https://docs.github.com/en/copilot/reference/review-excluded-files.md
14. About GitHub Copilot code review — GitHub Docs (markdown) — https://docs.github.com/en/enterprise-cloud@latest/copilot/concepts/agents/code-review.md
15. Configuring code review by GitHub Copilot — GitHub Docs (markdown) — https://docs.github.com/en/enterprise-cloud@latest/copilot/how-tos/copilot-on-github/set-up-copilot/configure-code-review.md
16. Cursor BugBot vs GitHub Copilot Code Review — CodeWalkers（第三方分析，非厂商文档）— https://codewalkers.com/ai-code-review/comparisons/bugbot-vs-github-copilot/
17. Copilot code review: Comment experience improvements — GitHub Changelog (2026-05-12) — https://github.blog/changelog/2026-05-12-copilot-code-review-comment-experience-improvements/
18. GET /v1/reviews（列表与状态枚举）与 Ellipsis Observability / API — Ellipsis Docs — https://www.ellipsis.dev/docs/api/reviews/get-reviews.md ；https://www.ellipsis.dev/llms.txt
19. Using Copilot cloud agent on GitHub（`@copilot` 通道，区别于 code review）— GitHub Docs (markdown) — https://docs.github.com/en/enterprise-cloud@latest/copilot/how-tos/use-copilot-agents/cloud-agent/use-cloud-agent-on-github.md
20. Triggers（`pull_requests.on` 含 `commented`）— Ellipsis Docs (markdown) — https://www.ellipsis.dev/docs/agents/triggers.md
21. GitHub（`@ellipsis` mention 与 handler）— Ellipsis Docs (markdown) — https://www.ellipsis.dev/docs/integrations/github.md
22. Copilot code review: Better handling of large pull requests — GitHub Changelog (2025-07-02) — https://github.blog/changelog/2025-07-02-copilot-code-review-better-handling-of-large-pull-requests/
23. Sweep config（归档，`sweep.yaml` 全字段与 rules 语义）— GitLab mirror — https://gitlab.com/sweepai/sweep/-/raw/main/docs/pages/usage/config.mdx
24. More ways to request and configure Copilot code reviews — GitHub Changelog (2026-09-23) — https://github.blog/changelog/2026-09-23-copilot-code-review-more-ways-to-request-and-configure-reviews/
25. Copilot code review: New configurations and controls（内容排除、字符上限移除）— GitHub Changelog (2026-06-12) — https://github.blog/changelog/2026-06-12-copilot-code-review-new-configurations-and-controls/
26. Copilot code review: Customization and configurability improvements（head 分支读 instructions、`copilot-code-review.yml`、防火墙）— GitHub Changelog (2026-07-17) — https://github.blog/changelog/2026-07-17-copilot-code-review-customization-and-configurability-improvements/
27. Bugbot — Cursor Help Center (markdown) — https://cursor.com/help/ai-features/bugbot.md

---

## 结论（给设计的 5 条硬要求）

1. **去重按 commit 区间记账，不按评论文本**：采用 Ellipsis 模型（`range_base_sha..head_sha`，范围外评论 held back），叠加 Bugbot 的 `git patch-id` 短路；仅靠 GitHub thread resolve 状态不可靠（Copilot 自己承认会重复 [5]）。
2. **必须输出覆盖度报告**：四者中只有 Ellipsis 把 `reviewed_files` / `reviewed_lines` 与「未发布的评论」结构化 [10]，且没有任何一家文档描述「告诉用户哪些文件没看、为什么」（Copilot 只给排除清单 [13]，Sweep 只在 README 写局限 [9]）。这是可以做出差异化的点：在概览评论里列出 skipped 文件 + 原因（排除规则命中 / 超预算 / 非文本 / 差异过大）。
3. **结论可配置**：`on_pass` ∈ silent/comment/approve、`on_fail` ∈ comment/request_changes，且默认不发 APPROVE [7][8]；若走 check run 路线参考 Bugbot 的 success/neutral/failure 三值 [1]。
4. **评论预算显式并带解释**：默认 64/64、超限「保留但不发布」并说明，而不是静默截断 [7][8]；配合 severity（[17]）+ 同类合并（[17]）。
5. **成本与 token 进 API**：per-review `cost`（按模型拆分）+ `tokens`（含 cache_read/cache_creation）+ 硬预算 `budget.session`，超预算的评审要么完整要么明说未完成 [7][8][10]。
