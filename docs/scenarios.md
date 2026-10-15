# CodePilot 场景矩阵（已核实版）

> 本文件是 Phase 0 的产出：把「一个 PR 从生到死会遇到的每一种情况」穷举成清单，逐条对照当前实现。
> **每一行的结论都来自实际代码**，附 `类:方法`（行号会随改动漂移，类与方法名不会）。
>
> 基线：commit `7f932dd`，`mvn clean test` = 540 tests / 0 failures / 0 errors。
> 状态图例：✅ 已实现 · ⚠️ 部分实现或有隐患 · ❌ 未实现
>
> **本文件不含「待核实」行。** 所有无法用代码证实的行为都已标注为 ❌/⚠️ 并说明依据。

---

## 0. 汇总

| 维度 | 场景数 | ✅ | ⚠️ | ❌ |
|---|---|---|---|---|
| A. PR 生命周期 | 18 | 4 | 5 | 9 |
| B. PR 内容形态 | 20 | 9 | 7 | 4 |
| C. 评论落点与形态 | 18 | 7 | 5 | 6 |
| D. 多文件协调 | 14 | 5 | 7 | 2 |
| E. 记忆与上下文 | 16 | 8 | 2 | 6 |
| F. Agent 模式 | 18 | 10 | 3 | 5 |
| G. 协作与反馈 | 8 | 0 | 0 | 8 |
| H. 性能与成本 | 8 | 1 | 3 | 4 |
| I. 故障 | 12 | 7 | 3 | 2 |
| J. 安全 | 10 | 7 | 3 | 0 |
| **合计** | **142** | **58** | **38** | **46** |

### 三个最严重的发现

| # | 发现 | 影响 | 证据 |
|---|---|---|---|
| **S1** | **Agent 回复与审查摘要在 GitHub 上互相覆盖** | 静默丢失用户可见内容 | `ReviewReportFormatter.DEFAULT_COMMENT_MARKER`(:18) 与 `application.yml:117` 的 `comment-marker` 默认值是同一字符串；`AgentChatTaskRunner.publishAndComplete`(:159) 用它给 Agent 回复加前缀；`GitHubCommentServiceImpl.findExistingCommentId`(:95-105) 只按 marker 过滤 + 取 `updatedAt` 最大，**无作者、无类型过滤** → 后写者覆盖先写者 |
| **S2** | **覆盖度完全不透明，且可输出假的"没问题"** | 大 PR 只审了部分文件，摘要读起来像全审过；若规划器跳过全部文件，摘要会输出「未发现问题。」 | `ReviewReportFormatter.formatMarkdown`(:44-76) 只渲染 issue，**从不提及被跳过的文件**；`:51-54` 在 `visibleIssues.isEmpty()` 时直接返回「未发现问题。」。跳过数据其实已持久化（`review_file.skipped`/`skip_reason`、`review_task.total_files`），formatter 未使用 |
| **S3** | **DLQ 只进不出** | 最终失败的任务消息永久滞留，无告警、无重放、无可见性 | 全仓库仅 2 个 `@RabbitListener`（`ReviewTaskConsumer`、`PrCommandTaskConsumer`），均指向主队列；DLQ 仅被声明与绑定（`RabbitMqConfig:39,82`） |

---

## A. PR 生命周期（18）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| A1 | PR opened 首次审查 | ✅ | `GitHubWebhookPayloadParser.PULL_REQUEST_EVENT`(:18) + `SUPPORTED_ACTIONS`(:22) 含 `opened`；`GitHubWebhookService.resolveReviewCommentMode` 对 `opened` 返回 `INLINE_ONLY` |
| A2 | synchronize 新 commit | ⚠️ | `synchronize` 在受支持动作内；但按新 headSha 建**新任务**并**全量重审整个 PR diff**，无增量、无跨次记忆（见 E2/E3） |
| A3 | reopened | ✅ | 在 `SUPPORTED_ACTIONS`(:22) 内 |
| A4 | closed / merged | ❌ | 无 `closed` 处理；`GitHubWebhookPayloadParser` 会以 `unsupported action` 忽略。无结案评论、无指标回收 |
| A5 | draft → ready_for_review | ❌ | `ready_for_review` 不在 `SUPPORTED_ACTIONS`，被忽略 |
| A6 | ready → draft | ❌ | 同上 |
| A7 | force push / rebase | ⚠️ | headSha 变 → 新任务（正确）；但旧的 inline 评论变 outdated，无检测、无收敛（见 C14） |
| A8 | base branch 变更 | ❌ | `GitHubWebhookPayloadParser` 不读 `pull_request.base`；`edited` 动作也不在受支持集合 |
| A9 | PR 标题/描述编辑 | ❌ | 解析器只读 `pull_request.title`(:55) 作为任务标题，**不读 body**，也不触发重审 |
| A10 | 添加/移除 label | ❌ | 无 `labeled`/`unlabeled` 处理，无 `skip-review` 类开关 |
| A11 | review requested | ❌ | 无 `review_requested` 处理 |
| A12 | 出现 merge conflict | ❌ | 无 `mergeable`/`mergeable_state` 感知（仅 Fix 流程在 merge 前校验，见 `FixMergeTaskRunner:140`） |
| A13 | PR 长期无活动 | ❌ | 无 stale 检测任务 |
| A14 | 手动 `/review` 重跑 | ✅ | `ReviewCommandHandler` 处理无 mention 的兼容命令 |
| A15 | 人工 review 被 dismiss 后重跑 | ❌ | 无任何 dismiss 感知 |
| A16 | commit 数量很多 | ⚠️ | 只按 PR 最终 diff 审查，不按 commit 组织；无 per-commit 视图 |
| A17 | 短时间多次 push（抖动） | ⚠️ | Redis 去重只覆盖同 headSha（`GitHubWebhookService.buildDedupKey` 用 headSha）；不同 headSha 各自建任务，可能排队积压且各自全量重审 |
| A18 | 来自 fork 的 PR | ⚠️ | 审查链路**不感知 fork**：解析器只取 `pull_request.head.sha`(:56)，不读 head repo。因 GitHub API 允许用 base repo 读 fork PR 的 diff（`GithubClient.listPullRequestFiles(baseOwner, baseRepo, pr)`），审查本身可用。allowlist 作用于 payload 的 base `repository`。**Fix 链路则正确读取 head repo**（`GithubPullRequestDetail.headRepoFullName`/`headRepoCloneUrl`，用于 `FixRequestAssembler`、`FixPullRequestWritePolicy`、`FixMergeTaskRunner`） |

---

## B. PR 内容形态（20）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| B1 | 单文件小改 | ✅ | 正常路径 |
| B2 | 大 PR（>30 文件） | ⚠️ | `ReviewFilePlanner.applyReviewLimits` 按 `max-files-per-task`（默认 30）贪心截断；未入选文件标 `skipped` + skip_reason。**用户看不到**（见 S2） |
| B3 | 单文件超大 patch | ⚠️ | 超 `max-patch-chars-per-file`（默认 12000）→ `markSkipped("patch exceeds per-file review limit")`。同样不可见 |
| B4 | 二进制/生成文件 | ✅ | `ReviewFilePlanner.toReviewFile` patch 为空 → `skipped=true`，reason `"patch is empty or file is binary/too large"` |
| B5 | 纯改名/移动 | ⚠️ | `GithubChangedFile` 字段只有 `filename/status/additions/deletions/changes/patch/rawUrl/blobUrl`，**无 `previous_filename`** → 改名来源不读取。无内容变更的改名通常 patch 为空 → 被 B4 规则跳过；有内容变更则按普通文件审 |
| B6 | 纯删除 | ✅ | 删除有 patch → 正常审查；`DiffLineMapper` 支持 LEFT side（测试 `GitHubInlineCommentServiceImplTest.shouldCommentOnDeletedLineOnLeftSide`） |
| B7 | 纯新增文件 | ✅ | 正常路径 |
| B8 | 仅测试改动 | ✅ | `ReviewFileClassifier.isTestPath`(:27) → 优先级 +350 |
| B9 | 依赖升级 | ✅ | `isDependencyManifestPath`(:76) → +650 |
| B10 | 数据库迁移 SQL | ✅ | `isDatabasePath`(:41) → +900，且 `SqlRiskTool` 覆盖 |
| B11 | 配置/密钥文件 | ✅ | `isConfigurationPath`(:59) → +700；`isSecuritySensitivePath`(:49) → +1000 |
| B12 | 仅文档 | ✅ | `isDocumentationPath`(:111) → −250 |
| B13 | i18n / 资源包 | ❌ | `ReviewFileClassifier` **无 i18n/资源包判定**；`ReviewFilePlanner.shouldSkipPath` 也不含。`.properties` 会被 `isConfigurationPath` 误判为配置（+700），`.json` 语言包得不到任何分类 → 优先级 0 |
| B14 | 生成代码（protobuf/openapi） | ⚠️ | 只靠目录名跳过：`ReviewFilePlanner.shouldSkipPath` 仅含 `dist/`、`target/`、`build/`。`src/main/java/**/generated/` 下的 protobuf 产物**不会被跳过**；`.proto` 本身被 `isPublicApiPath`(:106) 归为公共 API(+800) |
| B15 | vendored 第三方 | ❌ | 跳过列表（`shouldSkipPath`）**不含 `vendor/`、`third_party/`、`node_modules/`** → 若被提交会全量进入审查并消耗预算 |
| B16 | submodule 变更 | ⚠️ | submodule 指针变更的 patch 为空 → 恰好被 B4 规则跳过；但无显式识别，且 `.gitmodules` 会按普通文件处理 |
| B17 | CI / workflow 文件变更 | ⚠️ | `.github/workflows/` 被 `isConfigurationPath`(:70) 归为配置(+700)，**但没有任何 CI 专用安全规则**（如 `pull_request_target` 注入、权限提升、secret 外泄） |
| B18 | 锁文件 | ✅ | `shouldSkipPath` 含 `.lock`、`package-lock.json`、`yarn.lock`、`.min.js` |
| B19 | 混合内容 | ✅ | 按 `reviewPriority` 降序贪心分配预算（`ReviewFilePlanner.applyReviewLimits`） |
| B20 | 空 diff | ✅ | `ReviewFileReviewer.review` 对空列表直接返回 `List.of()` |

---

## C. 评论落点与形态（18）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| C1 | 新增行（RIGHT）inline | ✅ | `GithubClient.createPullRequestInlineComment(owner, repo, pullNumber, commitId, path, line, side, body)`(:349-385) |
| C2 | 删除行（LEFT）inline | ✅ | 同上，`side` 参与请求体；有测试覆盖 |
| C3 | 上下文行 inline | ✅ | 测试 `shouldCommentOnContextLine` |
| C4 | **多行区间评论** | ❌ | 请求体只写 `line` + `side`(`GithubClient:376-377`)，**无 `start_line` / `start_side`** |
| C5 | **`suggestion` 一键应用块** | ❌ | `GitHubInlineCommentBodyBuilder` 不生成 ` ```suggestion ` 围栏；全仓库无 `suggestion` 关键字 |
| C6 | 非 diff 文件的问题（如"缺测试"） | ✅ | `ReviewIssueLocationGuard` 拦下后只能进 Summary（见 `ReviewCommentPublisher.publish`） |
| C7 | Summary marker 幂等更新 | ✅ | `GitHubCommentServiceImpl.findExistingCommentId`(:95) + `updateIssueComment` |
| C8 | 回复已有 review thread | ❌ | 只调用 `createPullRequestComment` / `createPullRequestInlineComment`，无 `in_reply_to` 参数 |
| C9 | resolve / unresolve thread | ❌ | 无 GraphQL `resolveReviewThread` 调用 |
| C10 | **正式 Review verdict** | ❌ | 全仓库无 `APPROVE`/`REQUEST_CHANGES` review event（搜到的 `approve*` 全部属于 Fix 审批链路 `FixApprovalService`） |
| C11 | inline 预算超限（10） | ⚠️ | `ReviewCommentBudgetAllocator` 存在并有测试；Summary 会追加「另外还有 N 条问题未展示。」(`ReviewReportFormatter:61-64`)，但**不指出被丢弃的是哪些、为什么** |
| C12 | location guard 拒绝 | ⚠️ | 被拒绝的问题不落 inline，仍进 Summary 列表；`ReviewIssue.commentChannel`(:45) 记录渠道。但用户不会知道"这条本想在 X 行说但定位失败" |
| C13 | fingerprint 重复 | ✅ | `GitHubInlineCommentFingerprintBuilder`/`Extractor`；测试 `shouldSkipIssueWhenExistingInlineFingerprintWasAlreadyPosted` |
| C14 | 行已过期（outdated） | ❌ | 不检测已发评论是否 outdated，也不清理/收敛 |
| C15 | 编辑/更新已发评论 | ⚠️ | Summary 走 update；inline 只新建，无编辑 |
| C16 | 评论到 PR 描述 | ❌ | 不做 |
| C17 | 评论到 commit | ❌ | 不做 |
| C18 | 评论正文脱敏 | ✅ | `SensitiveDataSanitizer` + `MarkdownSanitizer.sanitizeInlineText`（`ReviewReportFormatter:125-127`），单字段截断 500 字符 |

---

## D. 多文件协调（14）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| D1 | 跨文件影响（改签名→调用方） | ⚠️ | `ReviewContextRelationshipExtractor`(308 行) 与 `RepositoryGraphSnapshotBuilder` 提供跨文件上下文，但**只作为 LLM 的输入**，不保证产出跨文件 finding |
| D2 | 接口 + 实现同时改 | ⚠️ | 同上 |
| D3 | DTO + mapper + SQL | ⚠️ | 同上 |
| D4 | 配置项新增但未使用 / 使用但未定义 | ❌ | `module/tool` 下只有 `SqlRiskTool`/`SecretScanTool`/`TestSuggestionTool` 三条规则，**无配置交叉校验规则**（`ToolProperties` 仅做开关） |
| D5 | API 契约变更 | ⚠️ | 离线评估含 `API 契约变化` 场景，但依赖 LLM 语义判断，无确定性规则 |
| D6 | 源码 + 对应测试配对 | ✅ | `RepoSourceTestPairCandidateResolver` |
| D7 | 依赖方向 / import 图 | ⚠️ | `RepositoryGraphSnapshotBuilder` + `RepositoryGraphNodeAccumulator` |
| D8 | 同根因跨多文件重复 | ⚠️ | `ReviewFindingDuplicateResolver` + `ReviewIssueDuplicateKey` 存在并有测试，但键的构成决定是否跨文件合并；当前设计以"同一问题在多个文件"为独立条目，不做根因归并 |
| D9 | 预算分配公平性 | ⚠️ | 贪心按优先级，受 `max-total-patch-chars`（80000）约束。一个 12000 字符的安全文件会占掉 15% 总预算，极端情况下后面的文件全被挤出 |
| D10 | 审查顺序 | ✅ | `ReviewFilePrioritizer.prioritize` 按语义计划的 priority score 降序，稳定排序 |
| D11 | 单文件失败 vs 整任务失败 | ✅ | `ReviewFileReviewExecutor.reviewFileSafely` 单文件兜底为系统 issue；`ReviewFileReviewer.review:99-103` 仅在**全部**可审文件失败时抛异常 |
| D12 | 仓库图谱跨文件上下文 | ✅ | `RepositoryGraphSnapshot`(190) + `RepositoryGraphSnapshotAssembler`(173) |
| D13 | 相关 patch 抽取 | ✅ | `ReviewRelatedPatchExtractor`(205) |
| D14 | 多文件整体风险判断 | ⚠️ | `ReviewImpactPlanner`(238) + `ReviewPlanQualityEstimator`(125)，效果无独立评估 |

---

## E. 记忆与上下文（16）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| E1 | 任务内共享 Context | ✅ | `ReviewFileReviewer.review` 构建一次 `ReviewContext` 供所有文件共享 |
| E2 | **跨评审复用上次结论** | ❌ | 无任何跨 headSha 的 finding 复用。仅 `FixableIssueSelector:75,91` 用 headSha 防止 stale fix |
| E3 | **上次已提问题不重复提** | ❌ | 无实现。新 headSha = 全新任务、全新问题集 |
| E4 | 跨评论 Agent 对话 | ✅ | `AgentConversationContextBuilder.build`：同 repo/pr、`commandType=CHAT`、`status=SUCCESS`、排除自身，取最近 N 条，`oneLine` 拍平后注入，标记为不可信 |
| E5 | Agent 异步恢复 continuation | ✅ | `AgentRunService.markWaiting`/`prepareResume` + `AgentWorkflowResumeJob`；`AgentChatTaskRunner:115-127` |
| E6 | 仓库级团队规范 | ✅ | RAG 规则库（`rule_document`/`rule_chunk`） |
| E7 | **仓库级历史误报抑制** | ❌ | 无实现。`ReviewIssue` 有 `publishDecision`(:41)/`suppressionReason`(:43) 字段，但只用于单次任务内的过滤，不跨任务累积 |
| E8 | LLM 审查缓存 | ✅ | `ai_review_cache`，TTL 7 天（`codepilot.llm.review-cache-ttl-days`） |
| E9 | RAG 召回缓存 | ✅ | `ReviewRagCache`，TTL 300s / LRU 256 |
| E10 | 👍/👎 反馈学习 | ❌ | 无 reaction 处理 |
| E11 | LLM 调用日志 | ✅ | `llm_call_log` + `LlmCallLogCleanupJob`（保留 30 天） |
| E12 | 评论 resolution 跟踪 | ❌ | 无 |
| E13 | 已发 inline 指纹去重 | ✅ | `GitHubInlineCommentFingerprintExtractor`（同 headSha 内有效） |
| E14 | 命令任务日志 | ✅ | `pr_command_task_log`；`AgentToolTraceAspect` 记录每次 Tool 调用 |
| E15 | 会话压缩 | ⚠️ | **无摘要压缩**：`AgentConversationContextBuilder:57` 为 `result.substring(result.length() - maxChars)`，盲截尾部字符，会把一条消息切成半句 |
| E16 | 跨 PR 的仓库画像 | ❌ | 无跨 PR 聚合 |

---

## F. Agent 模式（18）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| F1 | 纯问答 | ✅ | `AgentChatTaskRunner` + `GithubCommandChatTool` 只读工具 |
| F2 | 团队规范检索 | ✅ | RAG tool（测试 `shouldDelegateTeamRuleSearchToExistingRagService`） |
| F3 | 联网检索 + 正文抓取 | ✅ | `SearxngSearchService` / `WebDocumentFetchService`，默认 `enabled=false` |
| F4 | 启动审查 workflow | ✅ | `startReviewWorkflow` tool；`AgentChatTaskRunner:115-127` 置 `WAITING_WORKFLOW` |
| F5 | dry-run 修复预览 | ✅ | `startFixPreview`，强制 `dryRun=true`，受 `fix-enabled` 开关 |
| F6 | 批准发布 | ✅ | `approveFixPreview` → `FixApprovalService` 服务端校验 |
| F7 | 批准合并 | ✅ | `approveFixMerge`，第二次独立审批 |
| F8 | 多轮追问 | ✅ | `conversation-max-turns`（默认 5）/ `conversation-max-chars`（6000，运行时下限 500） |
| F9 | 要求澄清 / 拒答 | ⚠️ | 仅靠 prompt 约束（`github-command-chat-system-message.txt`），**无代码级保证**，也无相关测试 |
| F10 | Tool 预算耗尽 | ⚠️ | 超限抛 `IllegalStateException("Agent tool call limit exceeded")`（`AgentRunService:73-74`）→ 被 `AgentChatTaskRunner:132-151` 捕获 → 用户收到**通用失败文案**「暂时无法完成本次 Agent 请求，请稍后重试」，不说明真实原因 |
| F11 | 运行超时 | ⚠️ | `AgentRunService:135-136` 同样抛异常（`run-deadline=30m`），用户同样只看到通用文案 |
| F12 | 同一 PR 并发多个 Agent 请求 | ⚠️ | 每个评论独立 `pr_command_task` + `agent_run`；`AgentConversationContextBuilder` 只注入 **已 SUCCESS** 的历史任务 → **两个同时在跑的 run 互相看不见** |
| F13 | issue_comment 非 PR | ✅ | `GitHubWebhookPayloadParser:82-84` 无 `/issue/pull_request` → `ignored("not pull request comment")` |
| F14 | **PR review comment 里的 @mention** | ❌ | `GitHubWebhookPayloadParser` 只认 `pull_request`(:18) 与 `issue_comment`(:20) 两个事件；`pull_request_review_comment` 落到 `ignored("unsupported event")`(:40)。**用户在 diff 上直接 @bot 无任何反应** |
| F15 | PR 描述里的 @mention | ❌ | 解析器只读 `pull_request.title`(:55)，不读 body；`pull_request` 事件走审查链路而非 Agent 链路 |
| F16 | diff 里的 prompt injection | ✅ | 离线评估 `PromptRegressionEvalTest` + `AiReviewPipelineEvalTest` 有该场景；`PromptInputSanitizer.escapeUntrustedBlockDelimiters` |
| F17 | 网页内容的 prompt injection | ✅ | `AiReviewContextFormatter` 标记外部内容为不可信；`WebDocumentFetchService` 有防护 |
| F18 | 用户要求取消运行 | ❌ | 无取消机制。全仓库唯一的 `cancel` 是 `GithubRequestExecutor:53,56` 的 HTTP `future.cancel(true)` |

---

## G. 协作与反馈（8）— 整维度空白

| ID | 场景 | 状态 | 证据 |
|---|---|---|---|
| G1 | 人回复 bot 评论 | ❌ | 全仓库无 `in_reply_to` / reaction / dismiss / resolve 处理 |
| G2 | 人 resolve / dismiss | ❌ | 同上 |
| G3 | 人 👍/👎 | ❌ | 同上 |
| G4 | 人手动编辑 bot 评论 | ❌ | Summary 会被下次审查覆盖回去（marker 命中），无检测 |
| G5 | 人无视 bot 直接合并 | ❌ | 无 `closed`/`merged` 事件处理，无复盘数据 |
| G6 | 多 reviewer | ❌ | 不做 reviewer 维度区分 |
| G7 | 人反馈"这是误报" | ❌ | 无入口 |
| G8 | 人要求只重审某个文件 | ❌ | `/review` 无参数化能力 |

> 验证方式：`grep -r 'reaction|dismiss|resolveThread|unresolve|in_reply_to' src/main/java` → 0 命中。

---

## H. 性能与成本（8）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| H1 | 首评延迟 | ⚠️ | 有 `codepilot.review.task.duration` Timer，但无 Prometheus registry，**无 SLO、无分位直出** |
| H2 | 单 PR token 成本 | ⚠️ | 仅离线评估里统计 `estimatedPromptTokens`（`AiReviewPipelineEvalTest`），**生产不统计** |
| H3 | 缓存命中率 | ❌ | 无命中率指标。`grep 'cache.hit\|cacheHit'` → 0 命中 |
| H4 | 并发背压 | ✅ | listener `concurrency=2`/`max=4`/`prefetch=1` + `max-parallel-files=2` |
| H5 | GitHub rate limit | ✅ | `GithubRequestExecutor` + `rate-limit-max-attempts=3` 指数退避 |
| H6 | 队列积压 | ❌ | 无队列深度指标。`grep 'queue.depth\|queueDepth'` → 0 命中 |
| H7 | 大 PR 审查时长 | ⚠️ | 有 Timer，但无按文件数/跳过数的分解 |
| H8 | Embedding 成本 | ❌ | 不统计 |

> `AgentMetrics` 额外暴露 `codepilot.agent.model.turns`、`codepilot.agent.tool.calls`、`codepilot.agent.run.transitions`，但 `docs/architecture.md` 的指标清单**一个都没列**。

---

## I. 故障（12）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| I1 | LLM 超时/不可用 | ✅ | `AiReviewServiceImpl:70-83`：确定性规则有结果则降级返回；无结果才向上抛 |
| I2 | GitHub 403/429/5xx | ✅ | `GithubRequestExecutor` 指数退避 + `call-timeout=45s` |
| I3 | Embedding 不可用 | ⚠️ | `AiReviewServiceImpl:70-83` 的降级路径同样覆盖 RAG 抛出的异常；但 `EmbeddingDimensionBootstrap` 在启动期就需要 embedding 可用（`dimension=0` 时自动探测），启动期不可用会导致维度无法确定 |
| I4 | Redis 不可用 | ✅ | 限流降级到 `FixedWindowRateLimiter` 单实例内存（`RedisFixedWindowRateLimiter:58-62`）；webhook 去重失败则放行并交给 DB 唯一索引（`GitHubWebhookService:149-154`） |
| I5 | RabbitMQ 不可用 | ✅ | outbox 重试 + 发布锁超时重领（`outbox-max-attempts=10`、`outbox-lock-timeout=2m`） |
| I6 | DB 不可用 | ❌ | 未引入熔断或 DB 级重试（`grep 'CircuitBreaker\|@Retryable\|resilience4j'` → 0 命中）；连接池耗尽时任务直接失败并进 MQ 重试 |
| I7 | worker 崩溃 | ✅ | 执行租约 `execution-lease=15m` + `ReviewTaskExecutionRecovery`（30s 扫描）+ `claimForExecution` 的条件更新 |
| I8 | 部分发布成功 | ⚠️ | `publish_status` 记录终态，但**不记录具体哪几条 inline 评论已发出**，重试粒度是整个任务 |
| I9 | **毒消息 → DLQ** | ⚠️ | 死信路由已配置（`default-requeue-rejected=false`），但**DLQ 无消费者**，无查看/重放/告警工具（见 S3） |
| I10 | Flyway 迁移失败 | ✅ | `validate-on-migrate: true` + `clean-disabled: true` |
| I11 | App token 过期 | ⚠️ | `app-token-cache-skew-seconds=60` 提前刷新；但长任务（`run-deadline=30m`）中途过期后的重取行为无专门测试 |
| I12 | 日志/磁盘 | ⚠️ | LLM 调用日志有 30 天清理；应用日志无轮转配置（`.gitignore` 忽略 `logs/`，但无 size/rotate 策略） |

---

## J. 安全（10）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| J1 | diff 中的 secret | ✅ | `SecretScanTool`；且 `SensitiveDataSanitizer` 全局脱敏 |
| J2 | diff 中的 prompt injection | ✅ | 见 F16 |
| J3 | 恶意 PR URL / SSRF | ✅ | `GithubPrUrlParser` 用 `PR_URL_PATTERN` 强制 `https://github.com/{owner}/{repo}/pull/{number}`，出网目标由配置的 `api-base-url` 决定，**PR URL 无法把请求引到任意主机** |
| J4 | 不可信网页内容 | ✅ | `WebDocumentFetchService` + `AgentWebResourceService`：HTTPS、域名白名单、拒绝私网 DNS/重定向/非文本/超大响应 |
| J5 | 审批伪造 | ✅ | `FixApprovalService` 以持久化原始评论 + 用户 + repo/PR + head SHA + patch SHA-256 + 允许路径 + 开关共同校验 |
| J6 | allowlist 绕过 | ✅ | `GithubRepositoryPolicy` 在 webhook 与命令路由两处校验 |
| J7 | 非协作者评论 | ✅ | `GithubCommandRouter.isAllowedCommentAuthor:74`，association ∈ `OWNER,MEMBER,COLLABORATOR` |
| J8 | bot 自我循环 | ✅ | `GitHubWebhookService.isBotAuthoredIssueComment:157` + `GitHubWebhookPayloadParser:88` 的 marker 自过滤 |
| J9 | 日志泄露 | ✅ | `SensitiveDataSanitizer.redact` / `redactAndTruncate` |
| J10 | 评论里回显敏感信息 | ⚠️ | `SecretScanTool` 只输出行号与关键词上下文（`normalizeKeywordText`/`QUOTED_LITERAL` 均用于**匹配**，不写入消息体），构建 issue 文本时未发现直接拼接原始 secret；但缺少"禁止回显原文"的显式测试断言，属**未加固**而非已知漏洞 |

---

## K. 与简历/文档口径的对齐（供后续引用）

| 说法 | 核实结果 |
|---|---|
| "540 个自动化测试" | ✅ 正确（`mvn clean test`），Docker 开 2 skipped / 不开 6 skipped |
| "Precision 85.71% / Recall 100%" | ✅ 数字正确，但**样本仅 6 个 PR-like 场景**，且在 issueType 级别按 case 拼 key 计算（`AiReviewPipelineEvalTest`） |
| "文件级并发 / 单文件失败隔离" | ✅ 正确 |
| "patch verification + location guard" | ✅ 正确 |
| "Summary marker 幂等 / inline fingerprint 去重" | ✅ 正确，**但 marker 被 Agent 回复复用，存在 S1 覆盖缺陷** |
| "p95 157ms" | ❌ **不属于本项目**，实际来自隔壁 `letchat` |
| "DLQ 便于排查" | ⚠️ 死信路由存在，但**无消费者与工具**，实际不可运营 |
