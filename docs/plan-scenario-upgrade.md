# CodePilot 场景矩阵与流程升级路线图（草案，待评审）

> 目的：把「一个 PR 从生到死会遇到的每一种情况」穷举成可核对清单，逐条对照当前实现，
> 再对标业界做法，最后排成可执行的升级批次。
>
> 状态标记：✅ 已实现 · ⚠️ 部分实现/有隐患 · ❌ 未实现 · ❓ 待核实（Phase 0 补）
> 本文所有结论在 Phase 0 会用代码逐条复核，复核结果回填到「证据」列。

---

## 0. 给计划前已核实的 5 个硬事实

这 5 条直接决定了升级重点，已用代码确认：

| # | 事实 | 证据 | 影响 |
|---|---|---|---|
| 1 | **inline 评论只支持单行，不支持多行区间，也不支持 `suggestion` 代码块** | `GithubClient.createPullRequestInlineComment(...)` 参数只有 `line` + `side`，请求体只放 `line`/`side`（`GithubClient.java:349-377`），无 `start_line`/`start_side`，也不生成 ```` ```suggestion ```` 块 | 无法给"一键应用修复"，审查价值大打折扣 |
| 2 | **不提交正式 GitHub Review verdict** | 全仓库没有 `APPROVE`/`REQUEST_CHANGES` 的 review event；搜到的 `approve*` 全部是 Fix 审批流程（`FixApprovalService`） | 无法与 branch protection / required review 联动 |
| 3 | **没有跨 headSha 的审查记忆** | 只有 `FixableIssueSelector:75,91` 用 headSha 防止 stale fix；没有任何"上次审查提过什么"的复用逻辑 | 每次 push 都从零开始，无法做到"只说新增问题" |
| 4 | **没有误报抑制 / 反馈闭环** | 无 dismiss、resolve、reaction、feedback 相关代码 | 人标记过"这是误报"的问题，下次照样提 —— AI review 最被诟病的点 |
| 5 | **没有增量审查** | 无 `baseSha`/`compare`/`incremental`/`delta` 逻辑；`synchronize` 会按新 headSha 建新任务并**全量重审整个 PR diff** | push 越多评论越多、token 越烧越猛 |

> 附带确认（来自前面已读代码）：`ReviewTaskResultStore.replaceAndComplete` 是原子替换，
> 所以同一 headSha 重跑不会留下半套结果；`ReviewTaskCreator` 的复用只对 Webhook 生效。

---

## 1. 场景全景（10 个维度 / 142 个场景）

### A. PR 生命周期（18）

| ID | 场景 | 现状 | 说明 |
|---|---|---|---|
| A1 | PR opened 首次审查 | ✅ | 触发审查，`review_comment_mode=INLINE_ONLY` |
| A2 | synchronize 新 commit | ⚠️ | 会审，但**全量重审**，无增量、无跨次记忆 |
| A3 | reopened | ✅ | 事件已支持 |
| A4 | closed / merged | ❌ | 无清理、无结案评论、无指标回收 |
| A5 | draft → ready_for_review | ❌ | 无 `ready_for_review` 处理 |
| A6 | ready → draft | ❌ | 同上 |
| A7 | force push / rebase | ⚠️ | headSha 变 → 新任务；但旧 inline 评论变 outdated，无收敛处理 |
| A8 | base branch 变更 | ❌ | 不感知 base 变化 |
| A9 | PR 标题/描述编辑 | ❌ | 不审描述（描述里常有背景与验收标准） |
| A10 | 添加/移除 label | ❌ | 无 `skip-review` 之类的 label 控制 |
| A11 | review requested | ❌ | 不感知 reviewer 指派 |
| A12 | 出现 merge conflict | ❌ | 不感知 |
| A13 | PR 长期无活动 | ❌ | 无 stale 提醒 |
| A14 | 手动 `/review` 重跑 | ✅ | `ReviewCommandHandler` |
| A15 | 人工 review 被 dismiss 后重跑 | ❌ | 不感知 |
| A16 | commit 数量很多 | ⚠️ | 只按最终 diff 审，不按 commit 维度组织 |
| A17 | 短时间多次 push（抖动） | ⚠️ | Redis 30s 去重只挡同一 headSha；不同 headSha 会各自建任务，可能排队积压 |
| A18 | 来自 fork 的 PR | ❓ | 需确认 allowlist / token 权限 / secret 差异如何处理 |

### B. PR 内容形态（20）

| ID | 场景 | 现状 | 说明 |
|---|---|---|---|
| B1 | 单文件小改 | ✅ | |
| B2 | 大 PR（>30 文件） | ⚠️ | `max-files-per-task=30` 会**静默截断**，摘要里没有"哪些没审" |
| B3 | 单文件超大 patch | ⚠️ | 超 `max-patch-chars-per-file=12000` 直接 skip，理由只进 skip_reason，用户看不到 |
| B4 | 二进制/生成文件 | ✅ | patch 为空 → skip |
| B5 | 纯改名/移动 | ❓ | rename 时 GitHub 给的 patch 形态需确认 |
| B6 | 纯删除 | ❓ | 删除行的评论落点（LEFT side） |
| B7 | 纯新增文件 | ✅ | |
| B8 | 仅测试改动 | ✅ | 优先级 350，会被审 |
| B9 | 依赖升级 | ✅ | 优先级 650 |
| B10 | 数据库迁移 SQL | ✅ | 优先级 900 + SQL 规则 |
| B11 | 配置/密钥文件 | ✅ | 优先级 700/1000 + Secret 规则 |
| B12 | 仅文档 | ✅ | 优先级 −250 |
| B13 | i18n / 资源包 | ❓ | 无专门分类 |
| B14 | 生成代码（protobuf/openapi） | ⚠️ | 只按目录名跳过，可能漏 |
| B15 | vendored 第三方 | ⚠️ | 同上 |
| B16 | submodule 变更 | ❓ | |
| B17 | CI / workflow 文件变更 | ⚠️ | 无专门的 CI 安全规则（权限提升、`pull_request_target` 注入） |
| B18 | 锁文件 | ✅ | 显式 skip |
| B19 | 混合内容 | ✅ | 按优先级分配预算 |
| B20 | 空 diff | ✅ | `ReviewFileReviewer` 空列表直接返回 |

### C. 评论落点与形态（18）

| ID | 场景 | 现状 | 说明 |
|---|---|---|---|
| C1 | 新增行（RIGHT）inline | ✅ | |
| C2 | 删除行（LEFT）inline | ✅ | `side` 参数支持，有测试 |
| C3 | 上下文行 inline | ✅ | 有测试 `shouldCommentOnContextLine` |
| C4 | **多行区间评论** | ❌ | 无 `start_line` |
| C5 | **`suggestion` 一键应用块** | ❌ | 见 0.1 |
| C6 | 非 diff 文件的问题（如"缺测试"） | ✅ | location guard 拦下 → 走 summary |
| C7 | Summary marker 幂等更新 | ✅ | |
| C8 | 回复已有 review thread | ❌ | 只能新建评论 |
| C9 | resolve / unresolve thread | ❌ | |
| C10 | **正式 Review verdict** | ❌ | 见 0.2 |
| C11 | inline 预算超限（10） | ⚠️ | 有 `ReviewCommentBudgetAllocator`，但溢出部分对用户的可见性需确认 |
| C12 | location guard 拒绝 | ⚠️ | 只进 summary，用户不知道"这条本想在 X 行说" |
| C13 | fingerprint 重复 | ✅ | 同 headSha 内去重 |
| C14 | 行已过期（outdated） | ❌ | 不检测旧评论是否已失效 |
| C15 | 编辑/更新已发评论 | ⚠️ | Summary 会更新；inline 只新建 |
| C16 | 评论到 PR 描述 | ❌ | |
| C17 | 评论到 commit | ❌ | |
| C18 | 评论正文脱敏 | ✅ | `SensitiveDataSanitizer` + `MarkdownSanitizer` |

### D. 多文件协调（14）

| ID | 场景 | 现状 | 说明 |
|---|---|---|---|
| D1 | 跨文件影响（改签名→调用方） | ⚠️ | 有 `ReviewContextRelationshipExtractor` / 仓库图谱，但**只作为上下文**，不保证产生跨文件 finding |
| D2 | 接口 + 实现同时改 | ⚠️ | 同上 |
| D3 | DTO + mapper + SQL | ⚠️ | 同上 |
| D4 | 配置项新增但未使用 / 使用但未定义 | ❌ | 无交叉校验规则 |
| D5 | API 契约变更（controller + client） | ⚠️ | eval 里有 `API 契约变化` 场景，但是靠 LLM 语义 |
| D6 | 源码 + 对应测试配对 | ✅ | `RepoSourceTestPairCandidateResolver` |
| D7 | 依赖方向 / import 图 | ⚠️ | 有 `RepositoryGraphSnapshotBuilder` |
| D8 | 同根因跨多文件重复 | ⚠️ | `ReviewFindingDuplicateResolver` 存在，但**是否跨文件合并成一条**待核实 |
| D9 | 预算分配公平性 | ⚠️ | 贪心按优先级；单个大安全文件可能吃掉大量 `max-total-patch-chars` |
| D10 | 审查顺序 | ✅ | `ReviewFilePrioritizer` 按语义计划排序 |
| D11 | 单文件失败 vs 整任务失败 | ✅ | 部分失败继续，全失败才抛 |
| D12 | 仓库图谱跨文件上下文 | ✅ | `RepositoryGraphSnapshotBuilder` |
| D13 | 相关 patch 抽取 | ✅ | `ReviewRelatedPatchExtractor` |
| D14 | 多文件整体风险判断 | ⚠️ | `ReviewImpactPlanner` / `ReviewPlanQualityEstimator`，效果未评估 |

### E. 记忆与上下文（16）

| ID | 场景 | 现状 | 说明 |
|---|---|---|---|
| E1 | 任务内共享 Context | ✅ | `ReviewContextBuilder` 每任务构建一次 |
| E2 | **跨评审复用上次结论** | ❌ | 见 0.3 |
| E3 | **上次已提问题不重复提** | ❌ | 见 0.3 |
| E4 | 跨评论 Agent 对话 | ✅ | 5 轮 / 6000 字符，标记为不可信数据 |
| E5 | Agent 异步恢复（continuation） | ✅ | `agent_run`/`agent_step` + `AgentWorkflowResumeJob` |
| E6 | 仓库级团队规范 | ✅ | RAG 规则库 |
| E7 | **仓库级历史误报抑制** | ❌ | 见 0.4 |
| E8 | LLM 审查缓存 | ✅ | `ai_review_cache`，TTL 7 天 |
| E9 | RAG 召回缓存 | ✅ | TTL 300s / LRU 256 |
| E10 | **👍/👎 反馈学习** | ❌ | 见 0.4 |
| E11 | LLM 调用日志 | ✅ | 保留 30 天，含耗时 |
| E12 | 评论 resolution 跟踪 | ❌ | |
| E13 | 已发 inline 指纹去重 | ✅ | `GitHubInlineCommentFingerprintExtractor` |
| E14 | 命令任务日志 | ✅ | `pr_command_task_log` |
| E15 | 会话压缩 | ⚠️ | 只有字符数截断，无摘要压缩 |
| E16 | 跨 PR 的仓库画像 | ❌ | |

### F. Agent 模式（18）

| ID | 场景 | 现状 | 说明 |
|---|---|---|---|
| F1 | 纯问答 | ✅ | |
| F2 | 团队规范检索 | ✅ | RAG tool |
| F3 | 联网检索 + 正文抓取 | ✅ | 默认关闭 + SSRF 防护 |
| F4 | 启动审查 workflow | ✅ | 异步 + 恢复 |
| F5 | dry-run 修复预览 | ✅ | 默认关闭 |
| F6 | 批准发布 | ✅ | 两阶段审批 |
| F7 | 批准合并 | ✅ | 需第二次独立审批 |
| F8 | 多轮追问 | ✅ | 受 `conversation-max-turns=5` 限制 |
| F9 | 要求澄清 / 拒答 | ❓ | 依赖 prompt 约束，无强保证 |
| F10 | Tool 预算耗尽 | ⚠️ | 有上限，但耗尽后的用户可见行为待核实 |
| F11 | 运行超时 | ⚠️ | `run-deadline=30m`，超时后是否回复用户待核实 |
| F12 | 同一 PR 并发多个 Agent 请求 | ⚠️ | 各有独立 run，**跨 run 的对话上下文可能互相看不见** |
| F13 | issue_comment 非 PR | ✅ | 无 PR 身份 → ignored |
| F14 | **PR review comment 里的 @mention** | ❓ | 目前只处理 `issue_comment`，review comment 是另一个事件类型，**很可能不支持** |
| F15 | PR 描述里的 @mention | ❌ | 不处理 |
| F16 | diff 里的 prompt injection | ✅ | eval 有覆盖 |
| F17 | 网页内容的 prompt injection | ✅ | 标记为不可信 |
| F18 | 用户要求取消运行 | ❌ | 无取消机制 |

### G. 协作与反馈（8）

| ID | 场景 | 现状 |
|---|---|---|
| G1 | 人回复 bot 评论 | ❌ 不感知 |
| G2 | 人 resolve / dismiss | ❌ |
| G3 | 人 👍/👎 | ❌ |
| G4 | 人手动编辑 bot 评论 | ❌ |
| G5 | 人无视 bot 直接合并 | ❌ 无复盘 |
| G6 | 多 reviewer | ❌ |
| G7 | 人反馈"这是误报" | ❌ |
| G8 | 人要求只重审某个文件 | ❌ |

### H. 性能与成本（8）

| ID | 场景 | 现状 | 说明 |
|---|---|---|---|
| H1 | 首评延迟 | ⚠️ | 有 Timer 指标，无 Prometheus，无 SLO |
| H2 | 单 PR token 成本 | ⚠️ | eval 里只统计 `estimatedPromptTokens`，生产无统计 |
| H3 | 缓存命中率 | ❌ | 无命中率指标 |
| H4 | 并发背压 | ✅ | prefetch=1 + 文件级并行 = 2 |
| H5 | GitHub rate limit | ✅ | retry/backoff |
| H6 | 队列积压 | ❌ | 无积压指标/告警 |
| H7 | 大 PR 审查时长 | ⚠️ | 有 Timer，无分解 |
| H8 | Embedding 成本 | ❌ | |

### I. 故障（12）

| ID | 场景 | 现状 |
|---|---|---|
| I1 | LLM 超时/不可用 | ✅ 降级到确定性规则（有确定性结果时） |
| I2 | GitHub 403/429/5xx | ✅ retry + backoff |
| I3 | Embedding 不可用 | ❓ |
| I4 | Redis 不可用 | ✅ 限流降级内存；webhook 去重失败则放行 |
| I5 | RabbitMQ 不可用 | ✅ outbox 重试 + 发布锁恢复 |
| I6 | DB 不可用 | ❓ 无熔断 |
| I7 | worker 崩溃 | ✅ 租约恢复 |
| I8 | 部分发布成功 | ⚠️ 无"哪条发出去了"的精确记录 |
| I9 | 毒消息 → DLQ | ✅ 但**无 DLQ 消费/告警/重放工具** |
| I10 | Flyway 迁移失败 | ✅ `validate-on-migrate` |
| I11 | App token 过期 | ⚠️ 有提前刷新，长任务中途过期待核实 |
| I12 | 日志/磁盘 | ❓ |

### J. 安全（10）

| ID | 场景 | 现状 |
|---|---|---|
| J1 | diff 中的 secret | ✅ 检测 + 脱敏 |
| J2 | diff 中的 prompt injection | ✅ |
| J3 | 恶意 PR URL / SSRF | ⚠️ 只有 `GithubPrUrlParser` 格式校验，无出网限制 |
| J4 | 不可信网页内容 | ✅ |
| J5 | 审批伪造 | ✅ 服务端校验持久化评论 |
| J6 | allowlist 绕过 | ✅ |
| J7 | 非协作者评论 | ✅ association 白名单 |
| J8 | bot 自我循环 | ✅ bot 自评论过滤 |
| J9 | 日志泄露 | ✅ 脱敏 |
| J10 | 评论里回显敏感信息 | ⚠️ 待核实：secret finding 的 description 是否含原始值 |

---

## 2. 从矩阵得出的 12 个优先改进项（按 ROI 排序）

| # | 改进项 | 覆盖场景 | 为什么优先 | 预估工作量 |
|---|---|---|---|---|
| 1 | **审查覆盖度透明化**：summary 里明确列出"未审查文件 + 原因"（预算截断/超大 patch/生成物） | B2 B3 C11 C12 | 当前静默截断会让人误以为"全审过了"，**是正确性问题而非体验问题** | 小 |
| 2 | **`suggestion` 代码块** | C5 | GitHub 一键应用，是 AI review 最高价值交互 | 中 |
| 3 | **跨评审去重（跨 headSha 记忆）**：新 headSha 审查时，比对上次成功任务的 finding，未变化的行不重复评论 | E2 E3 A2 A7 | 直接解决"push 一次刷一次屏" | 中 |
| 4 | **误报抑制（dismiss/👎 学习）** | E7 E10 G2 G3 G7 | AI review 工具留存的关键 | 中 |
| 5 | **增量审查**：只审 `base...head` 中有变化的 hunk | A2 A17 H2 | 省 token、省时间、少噪音 | 大 |
| 6 | **多行区间评论** | C4 | 一个 finding 常跨多行 | 小 |
| 7 | **正式 Review verdict（开关）** | C10 | 与 branch protection 联动 | 中 |
| 8 | **DLQ 运维工具**：DLQ 查看 + 重放 + 告警 | I9 | 现在 DLQ 只进不出 | 小 |
| 9 | **队列积压与失败率指标** | H6 H3 I8 | 可观测性缺口 | 小 |
| 10 | **配置交叉校验规则**（新增但未用 / 使用但未定义） | D4 | 确定性规则，高精度低成本，契合"规则优先"架构 | 中 |
| 11 | **PR review comment 里的 @mention 支持** | F14 | 现在只支持 issue_comment，用户在 code review 里 @bot 无反应 | 中 |
| 12 | **CI/workflow 变更安全规则** | B17 | 权限提升 / `pull_request_target` 注入是真实高危 | 中 |

---

## 3. 对标调研计划

### 3.1 调研对象（按优先级）

**第一梯队（架构最接近，必看）**

| 系统 | 为什么看它 | 重点挖什么 |
|---|---|---|
| **PR-Agent / Qodo Merge**（开源） | 同样是"工具 + LLM + PR 事件"的 Java/Python 工程，可直接读源码 | 增量审查（`/review` 的 dynamic context）、`improve` 如何生成 suggestion、持久化状态存哪、如何避免重复评论 |
| **CodeRabbit** | 商用流水线最成熟 | incremental review 机制、`.coderabbit.yaml` 路径级指令、learnings（从聊天反馈学习）、评论折叠（`<details>`） |
| **Greptile** | 以"代码库图上下文"为卖点 | 跨文件上下文怎么建、custom rules 怎么落 |

**第二梯队（机制参考）**

| 系统 | 重点 |
|---|---|
| **Graphite Diamond** | stacked PR 感知、增量 diff |
| **Cursor Bugbot** | 与 IDE 上下文联动、评论形态 |
| **GitHub Copilot code review** | 官方原生实现，verdict 与 suggestion 的用法 |
| **Sweep / Ellipsis** | 从 review 到 fix PR 的闭环 |
| **Danger / Reviewdog / SonarQube / Semgrep** | 确定性工具的评论模式（`github-pr-review` vs `github-pr-check`）、增量扫描、抑制基线（baseline/suppression file）|

### 3.2 要回答的 10 个具体问题

1. 新 push 之后，它们**怎么做到只说新增问题**？（增量 diff？finding 指纹比对？评论编辑？）
2. 重复评论是怎么避免的？（持久化评论 + 更新 vs 新建 + 指纹）
3. `suggestion` 块在什么条件下生成？如何保证 patch 可应用？应用失败怎么办？
4. 误报抑制的数据模型是什么？（dismiss 记录 / reaction / 显式命令 / 自动阈值）
5. 大 PR 怎么切？（按文件 / 按 hunk / 按依赖聚类 / 分层摘要）
6. 跨文件 finding 怎么表达？（一条评论挂哪个文件？还是只在 summary？）
7. 记忆存在哪？（PR 描述 / 隐藏评论 / 外部 DB / 仓库内配置文件）
8. review verdict 的默认策略？（从不 / 仅在零问题时 / 可配阈值）
9. 评论预算与折叠策略？（超量时折叠、分优先级、`<details>`）
10. 怎么做成本控制与可观测？（token/PR、缓存命中、队列积压）

### 3.3 产出物

`docs/benchmark-ai-review-tools.md`：10 个系统 × 10 个机制 的对比矩阵 + 每个结论的出处链接 + "CodePilot 可借鉴 / 不适用 / 已超越" 三分类。

---

## 4. 执行计划

### Phase 0：场景矩阵核实（把 ❓ 全部消掉）

- 产出：`docs/scenarios.md`（本文升级为已核实版，每行带 `类:方法:行号` 证据）
- 手段：读代码 + 必要时写最小复现测试
- 交付判据：**142 行里不再有 ❓**

### Phase 1：对标调研

- 产出：`docs/benchmark-ai-review-tools.md`
- 手段：web 检索 + 读 PR-Agent 等开源实现
- 交付判据：3.2 的 10 个问题都有明确答案 + 出处

### Phase 2：差距分析与设计

- 产出：`docs/roadmap.md`，12 个改进项每项含：
  问题 / 方案 / **替代方案与不选的理由** / 数据模型与迁移 / 配置开关与默认值 / 测试计划 / 回滚方式 / 风险
- 交付判据：每个改进项都能独立评审，不互相绑死

### Phase 3：分批实现（每批独立可交付、全绿）

| 批次 | 内容 | 涉及 | 验证 |
|---|---|---|---|
| 批 1 | 覆盖度透明化 + DLQ 工具 + 积压指标 | `ReviewReportFormatter`、`ReviewTaskPublicationJob`、`ReviewMetrics`、新增 DLQ 控制器 | 单测 + 真中间件 |
| 批 2 | 评论形态：`suggestion` + 多行区间 + 脱敏复核 | `GithubClient`、`GitHubInlineCommentBodyBuilder`、`ReviewIssueLocationGuard` | 单测 + 沙箱 E2E |
| 批 3 | 记忆与去重：跨 headSha finding 比对 + 误报抑制表 | 新增迁移 V18、`ReviewFindingDuplicateResolver`、新增 suppression 服务 | 单测 + 集成测试 |
| 批 4 | 增量审查 | `GithubClient`（compare API）、`ReviewFilePlanner`、`ReviewTaskCreator` | 集成测试 + 评估集扩充 |
| 批 5 | 协作：Review verdict（开关）+ review comment mention | `GitHubCommentService` 扩展、`GitHubWebhookService` 事件扩展 | 沙箱 E2E |

每批都必须：`mvn clean test` 全绿 + Docker 集成测试通过 + `docs/code-map.md` 同步更新。

### Phase 4：指标与文档收口

- 扩充离线评估集（当前只有 6 个 case，是最大短板）
- 更新 README / architecture / code-map
- 补 `docs/observability.md`

---

## 5. 需要你决定的事

1. 这一轮只出**文档**（Phase 0–2），还是**文档 + 实现**（到 Phase 3）？
2. 12 个改进项里先做哪几个？（我建议 1 + 3 + 4，因为这三条是"正确性/留存"问题，且不依赖大改）
3. 对标调研做多深？（快速 6 个系统 vs 深度 12 个系统 + 读 PR-Agent 源码）
4. 是否允许引入新的 **GitHub 写操作**（Review verdict、suggestion、编辑旧评论）？这些必须默认关闭 + 沙箱验证
5. 增量审查（第 5 项）会改变 `synchronize` 语义（从"全量重审"变成"只审 delta"）。是否接受？这会影响现有测试与文档承诺
