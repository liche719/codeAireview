# CodePilot 场景矩阵（已核实版）

> 本文件是 Phase 0 的产出：把「一个 PR 从生到死会遇到的每一种情况」穷举成清单，逐条对照当前实现。
> **每一行的结论都来自实际代码**，附 `类:方法`（行号会随改动漂移，类与方法名不会）。
>
> 基线：R1–R18 全部落地后的 `main`，`mvn clean test` = 775 tests / 0 failures / 0 errors / 2 skipped。
> （刻意不写死 commit sha：每次提交都会让它过期，而它过期时读起来仍然像是对的。）
> 状态图例：✅ 已实现 · ⚠️ 部分实现或有隐患 · ❌ 未实现
>
> **本文件不含「待核实」行。** 所有无法用代码证实的行为都已标注为 ❌/⚠️ 并说明依据。
>
> **修订记录（2026-10）**：初版基线为 `7f932dd`（540 tests）。R1–R7 与本轮 R7 prompt 注入落地后，
> 下方行状态已逐条重新对照代码更新。**同时修正了本文件自身的一个统计错误**——
> 初版的「0. 汇总」表数字与各维度明细行对不上（详见该表下方注）。
>
> 相关文档：[业界机制对标](benchmark-ai-review-tools.md) · [改进路线图](roadmap.md)（本文件是路线图的**事实输入**）

---

## 0. 汇总

| 维度 | 场景数 | ✅ | ⚠️ | ❌ |
|---|---|---|---|---|
| A. PR 生命周期 | 18 | 3 | 5 | 10 |
| B. PR 内容形态 | 20 | 18 | 2 | 0 |
| C. 评论落点与形态 | 18 | 10 | 3 | 5 |
| D. 多文件协调 | 14 | 5 | 8 | 1 |
| E. 记忆与上下文 | 18 | 16 | 0 | 2 |
| F. Agent 模式 | 18 | 14 | 2 | 2 |
| G. 协作与反馈 | 8 | 2 | 0 | 6 |
| H. 性能与成本 | 8 | 3 | 3 | 2 |
| I. 故障 | 12 | 7 | 4 | 1 |
| J. 安全 | 12 | 9 | 1 | 2 |
| **合计（A–J）** | **146** | **87** | **28** | **31** |
| K. 补漏 `C19` | 1 | 1 | 0 | 0 |
| **总计** | **147** | **88** | **28** | **31** |

> **上表是本文件自身的第二处修正。** 初版表格（`142 / 58 / 38 / 46`）与各维度明细行**对不上**：
> 例如 A 维度明细行是 `3 ✅ / 5 ⚠️ / 10 ❌`，初版表格却写 `4 / 5 / 9`；C 维度明细是 `7 / 3 / 8`，
> 初版写 `7 / 5 / 6`。现在这版数字是**脚本按明细行重新统计**得出的
> （按 `^[A-Z]+[0-9]+$` 识别场景行、只认每行第一个状态 emoji），不再手工估算。
> 该脚本统计到 **150 行**，即 147 个场景 **+ 上方 S1/S2/S3 三条发现**（它们的 ID 也符合该模式，故需扣除）。
>
> 另一个容易被误读的点：`C19` 按编号属于 C，但为保留"不修改既有编号"的自我修正记录而单列在 K，
> 所以 A–J 合计 146、再加 C19 才是 147。
>
> **本轮新增的行**（都不改既有编号）：`J11` 多租户判断、`J12` 自动审查路径的滥用防护、`E17` 路径级审查指令、`E18` 自省复核。

### 三个最严重的发现（**均已修复**）

| # | 发现 | 状态 | 修复 |
|---|---|---|---|
| **S1** | Agent 回复与审查摘要在 GitHub 上互相覆盖 | **✅ 已修复（R1）** | `CommentMarkers` 成为身份标记的唯一来源（摘要 / Agent 回复 / 命令提示 / 修复结果四类互不相同，有测试断言四者不相等）；`findExistingCommentId` 增加**作者过滤**（`isOwnSummaryComment`），只更新"本机器人发的 **且** 带摘要标记"的评论。**注**：该缺陷的完整修复还依赖 F1（PATCH 不可用）——只做身份分离而发不出 PATCH 的话，摘要仍无法更新 |
| **S2** | 覆盖度完全不透明，且可输出假的"没问题" | **✅ 已修复（R2）** | `ReviewReportFormatter` 新增「审查覆盖」区块（按原因分组列出未审查文件）与**前置**告警；一个文件都没审时输出"本次结论**不代表 PR 没有问题**"而非「未发现问题。」；`min-coverage-ratio`（默认 0.5）触发醒目告警 |
| **S3** | DLQ 只进不出 | **✅ 已修复（R3）** | `dead_letter_message` 表 + 手动 ack 的死信消费者 + `/api/admin/dead-letters` 查看/重放/丢弃 + 维护任务。重放受 `dlq.replay-enabled`（默认 **false**）门控 |

> 三项各有能捕获回退的回归测试；R1/R2 已在真实 PR 上端到端验证（见 `verify-r1-r2-on-a-real-pr.md`）。

> **端到端验证中新发现的既有缺陷（F1，不在原 18 项设计内）**：`SimpleClientHttpRequestFactory` 基于
> `HttpURLConnection`，**不支持 PATCH**，导致摘要评论永远无法更新（`Invalid HTTP method: PATCH`）——
> 即 S1 的"更新而非新建"路径实际不可用。改用 `JdkClientHttpRequestFactory` 后已在真实 PR 上验证
> （摘要长度 2053 → 382）。

---

## A. PR 生命周期（18）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| A1 | PR opened 首次审查 | ✅ | `GitHubWebhookPayloadParser.PULL_REQUEST_EVENT`(:18) + `SUPPORTED_ACTIONS`(:22) 含 `opened`；`GitHubWebhookService.resolveReviewCommentMode` 对 `opened` 返回 `INLINE_ONLY` |
| A2 | synchronize 新 commit | ⚠️ | `synchronize` 在受支持动作内；仍按新 headSha 建**新任务**并**全量重审整个 PR diff**（增量审查 D1 未做）。**但 R6 落地后不再是"失忆重审"**：`review_finding_memory` 跨 headSha 记住已提过的 finding，重复项标 `SUPPRESS` 不再评论；旧的未再现 finding 在"完整审查 + headSha 确实变化"双条件下才标 `RESOLVED`。配置 `finding-memory-enabled` 默认 **false** |
| A3 | reopened | ✅ | 在 `SUPPORTED_ACTIONS`(:22) 内 |
| A4 | closed / merged | ❌ | 无 `closed` 处理；`GitHubWebhookPayloadParser` 会以 `unsupported action` 忽略。无结案评论、无指标回收 |
| A5 | draft → ready_for_review | ❌ | `ready_for_review` 不在 `SUPPORTED_ACTIONS`，被忽略 |
| A6 | ready → draft | ❌ | 同上 |
| A7 | force push / rebase | ⚠️ | headSha 变 → 新任务（正确）。**R6 的指纹刻意不含行号**（`ReviewFindingFingerprint` 只用 `filePath\|issueType\|归一化标题`），所以 rebase 导致的行号漂移不会让去重失效。但旧的 inline 评论变 outdated 后**仍无检测、无收敛**（见 C14，属 R15 范围） |
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
| B2 | 大 PR（>30 文件） | ✅ | `ReviewFilePlanner.applyReviewLimits` 按 `max-files-per-task`（默认 30）贪心截断；未入选文件标 `skipped` + skip_reason。**R2 之后用户看得到**：摘要前置覆盖告警 + 「审查覆盖」区块按原因分组列出未审查文件，覆盖率低于 `min-coverage-ratio`（0.5）时醒目提示。截断本身是刻意的预算行为，不是缺陷 |
| B3 | 单文件超大 patch | ✅ | 超 `max-patch-chars-per-file`（默认 12000）→ `markSkipped("patch exceeds per-file review limit")`。同 B2，R2 后该跳过对用户可见并计入覆盖率 |
| B4 | 二进制/生成文件 | ✅ | `ReviewFilePlanner.toReviewFile` patch 为空 → `skipped=true`，reason `"patch is empty or file is binary/too large"` |
| B5 | 纯改名/移动 | ⚠️ | `GithubChangedFile` 字段只有 `filename/status/additions/deletions/changes/patch/rawUrl/blobUrl`，**无 `previous_filename`** → 改名来源不读取。无内容变更的改名通常 patch 为空 → 被 B4 规则跳过；有内容变更则按普通文件审 |
| B6 | 纯删除 | ✅ | 删除有 patch → 正常审查；`DiffLineMapper` 支持 LEFT side（测试 `GitHubInlineCommentServiceImplTest.shouldCommentOnDeletedLineOnLeftSide`） |
| B7 | 纯新增文件 | ✅ | 正常路径 |
| B8 | 仅测试改动 | ✅ | `ReviewFileClassifier.isTestPath`(:27) → 优先级 +350 |
| B9 | 依赖升级 | ✅ | `isDependencyManifestPath`(:76) → +650 |
| B10 | 数据库迁移 SQL | ✅ | `isDatabasePath`(:41) → +900，且 `SqlRiskTool` 覆盖 |
| B11 | 配置/密钥文件 | ✅ | `isConfigurationPath`(:59) → +700；`isSecuritySensitivePath`(:49) → +1000 |
| B12 | 仅文档 | ✅ | `isDocumentationPath`(:111) → −250 |
| B13 | i18n / 资源包 | ✅ | **R11**：`ReviewFileClassifier.isI18nPath` 覆盖 `/i18n/`、`/locale(s)/`、`/lang/`、`/translations/`、`/messages/`、`messages*`、`*.po`、`*.pot`、`*.xliff`、`*.xlf`。**只降优先级（−400）、不跳过**——翻译错误（占位符不匹配、漏翻）是真实缺陷。**关键修正**：仅在原基础上减分是不够的，`*.properties` 会命中 `isConfigurationPath`(+700) 再叠加 `src/main/` 源码加成(+600)，总分反而**高于**普通源码。因此 i18n 与"配置文件"加成改为**互斥**，否则"降优先级"的意图会被完全反转 |
| B14 | 生成代码（protobuf/openapi） | ✅ | **R11**：`isGeneratedPath` 按生成器命名约定判定（`.pb.go`、`.pb.grpc.go`、`_pb2.py`、`_pb2_grpc.py`、`.g.dart`、`.freezed.dart`、`.designer.cs`、`.generated.{cs,java,ts,kt}`、`zz_generated.*`），跳过并归入**独立的** `GENERATED` 分类——reason 指向"去改生成源"，而不是笼统的路径排除。**只看路径里有没有 "generated" 是错的**：那会把 `CodeGenerator.java`、`tools/generators/` 这些**生成器本身**排除掉（有反向断言）。初版 skip_reason 文案 "file type or generated path skipped" 与实现不符的问题随本轮一并修掉（现在文案按分类分别是 build output / vendored / generated） |
| B15 | vendored 第三方 | ✅ | **R11**：`isVendoredPath` 覆盖 `vendor/`、`third_party/`、`third-party/`、`node_modules/`、`bower_components/`，跳过并归入 `VENDORED` 分类。**用整段目录匹配而非子串**：`contains("vendor")` 会把 `src/main/java/**/vendored/License.java` 这类手写代码误判成第三方（有反向断言）。该误判的后果是静默漏审，所以必须收紧 |
| B16 | submodule 变更 | ⚠️ | submodule 指针变更的 patch 为空 → 恰好被 B4 规则跳过；但无显式识别，且 `.gitmodules` 会按普通文件处理 |
| B17 | CI / workflow 文件变更 | ✅ | **R13**：新增确定性规则 `CiWorkflowRiskTool`（只对 `.github/workflows/*.yml` / `*.yaml` 生效），覆盖 5 类可枚举高危模式：`pull_request_target` **配合** checkout PR head、`run:` 里直接插值用户可控输入、`permissions: write-all`、`pull_request` 触发的 workflow 引用 `secrets`、第三方 action 未固定到 40 位 SHA。`.github/workflows/` 仍被 `isConfigurationPath` 归为配置(+700)，规则是在此之上追加的安全检查。开关 `ci-workflow-risk-enabled` 默认 **true**。**两个降噪设计**：① 只判本 PR **新增行**——上下文行里的既有写法不报，否则等于让人为历史代码负责；② 只报高置信模式并附触发片段——`pull_request_target` **单独出现不报**，危险的是它又去 checkout 了 head |
| B18 | 锁文件 | ✅ | `shouldSkipPath` 含 `.lock`、`package-lock.json`、`yarn.lock`、`.min.js`、`.min.css`。**R11 修掉错位**：`pnpm-lock.yaml` 原先以 `.yaml` 结尾，既不被 `.lock` 命中、又被 `isDependencyManifestPath` 归为依赖清单，于是会被当普通文件送审；同类漏判的 `poetry.lock`、`go.sum` 一并补上 |
| B19 | 混合内容 | ✅ | 按 `reviewPriority` 降序贪心分配预算（`ReviewFilePlanner.applyReviewLimits`） |
| B20 | 空 diff | ✅ | `ReviewFileReviewer.review` 对空列表直接返回 `List.of()` |

---

## C. 评论落点与形态（18）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| C1 | 新增行（RIGHT）inline | ✅ | `GithubClient.createPullRequestInlineComment(owner, repo, pullNumber, commitId, path, line, side, body)`(:349-385) |
| C2 | 删除行（LEFT）inline | ✅ | 同上，`side` 参与请求体；有测试覆盖 |
| C3 | 上下文行 inline | ✅ | 测试 `shouldCommentOnContextLine` |
| C4 | 多行区间评论 | ✅ | **R4**：`DiffLineMapping` 增加 `startLine`/`isRange()`；`DiffLineMapper.mapRange` 要求区间整体落在**同一 hunk** 且是**同一侧连续段**；`GithubClient` 增加带 `start_line`/`start_side` 的重载；迁移 V21 存 `start_line`/`end_line`/`line_side`。**区间不合法（跨 hunk / 越界 / 异侧）一律降级为单行，绝不丢 finding**。开关 `multiline-comment-enabled` 默认 **true** |
| C5 | `suggestion` 一键应用块 | ✅ | **R5**：`AiReviewIssue.suggestedCode` + JSON schema 字段 + 迁移 V22 `review_issue.suggested_code`；`ReviewSuggestionValidator` 在**发布时**判定能否一键应用（非空 / 落点可评论 / 必须是 diff **新侧** RIGHT / 行数 ≤ `suggestion-max-lines`）。**不通过不丢弃**：代码块从 ` ```suggestion ` 降级为 ` ```diff ` 并附说明。开关 `suggestion-enabled` 默认 **false**（写侧交互）。**未做**：422 兜底重试；以及"发出的 suggestion 真的能被 GitHub 应用"的真机验证 |
| C6 | 非 diff 文件的问题（如"缺测试"） | ✅ | `ReviewIssueLocationGuard` 拦下后只能进 Summary（见 `ReviewCommentPublisher.publish`） |
| C7 | Summary marker 幂等更新 | ✅ | `GitHubCommentServiceImpl.findExistingCommentId` + `updateIssueComment`。**R1 之后**：标记来源统一到 `CommentMarkers`（四类互不相同），并增加**作者过滤**（`isOwnSummaryComment`：必须带摘要标记**且**是本机器人发的），两道过滤缺一不可。S1 覆盖缺陷已消除；实际能更新还依赖 F1 的 PATCH 修复 |
| C8 | 回复已有 review thread | ✅ | **R14**：`GithubClient.replyToReviewComment`（`POST /pulls/{n}/comments/{id}/replies`）。Agent 回复在 `replyToCommentId` 非空时投递进 thread，否则发顶层评论。**关键细节**：接口要求传**线程根** id，传一条回复自己的 id 会失败，因此解析阶段已把 `in_reply_to_id`（有）或 `comment.id`（顶层即根）归一 |
| C9 | resolve / unresolve thread | ❌ | 无 GraphQL `resolveReviewThread` 调用 |
| C10 | **正式 Review verdict** | ❌ | 全仓库无 `APPROVE`/`REQUEST_CHANGES` review event（搜到的 `approve*` 全部属于 Fix 审批链路 `FixApprovalService`） |
| C11 | inline 预算超限（10） | ⚠️ | `ReviewCommentBudgetAllocator` 存在并有测试；Summary 会追加「另外还有 N 条问题未展示」，且**R6 之后**额外给出「其中 M 条已被抑制」（`ReviewReportFormatter:123-130`）。**仍未解决的部分**：不列出被丢弃的**具体是哪几条、为什么**——R2 的「审查覆盖」说的是**文件**维度，不是 inline 评论预算维度（roadmap 把 C11 列在 R2 的覆盖场景里，实际只部分兑现） |
| C12 | location guard 拒绝 | ⚠️ | 被拒绝的问题不落 inline，仍进 Summary 列表；`ReviewIssue.commentChannel` 记录渠道。**R16 修掉了文案与实际判定范围不符**：旧 reason 写 `"line is not a commentable added diff line"`（只提新增行，读起来像"只有新增行可评论"），实际 `DiffLineMapper.map` 同时接受新增行（RIGHT）、删除行（LEFT）与上下文行（RIGHT）；现在改为 `"line is not part of the diff for this file (no matching added, deleted or context line)"`，并提为 `ReviewIssueLocationGuard.REASON_NOT_IN_DIFF` 常量。**仍未解决的另一半**：该 reason **只 `log.debug`，不进摘要**，所以用户仍不知道"这条本想在 X 行说但定位失败"——补它需要给 `ReviewIssue` 加字段并在 formatter 渲染，属独立改动。R2 的覆盖度区块是**文件**维度，不解决这条 |
| C13 | fingerprint 重复 | ✅ | `GitHubInlineCommentFingerprintBuilder`/`Extractor`；测试 `shouldSkipIssueWhenExistingInlineFingerprintWasAlreadyPosted` |
| C14 | 行已过期（outdated） | ❌ | **R15 评估后决定不实施**（理由见 roadmap）。核心原因不是"顾不上"：要 auto-resolve 就必须先解决一个跨项冲突——R7 的 `ReviewDismissalService` 只看"thread 已 resolved 且首条评论是本 bot 发的"就标 `DISMISSED`，**无法区分是谁 resolve 的**；于是 R15 的自动 resolve 会被 R7 读成"维护者判定误报"，一旦该问题日后回归，`suppressRepeated` 会**永久抑制**它并标注 `dismissed by a maintainer`。按方案实现等于开一条伪装成人工判定的漏报通道。要做必须先建按评论记账的表、并让 R7 能排除本系统 resolve 的 thread。**另**：roadmap 曾把 C14 列在 R6 的覆盖场景里，但 R6 解决的是"同一问题别重复评论"（指纹不含行号、抗漂移），并不是"检测锚点行消失" |
| C15 | 编辑/更新已发评论 | ⚠️ | Summary 走 update；inline 只新建，无编辑。**R15 评估后维持不做**：编辑旧 inline 评论会因 position 漂移而锚到错误行，比重复评论更糟——方案自己也已判定不选 |
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
| D4 | 配置项新增但未使用 / 使用但未定义 | ❌ | **R12 评估后明确未做**，理由是能力缺口而非疏漏：判定这条需要看**整个仓库**的配置与引用，而 `GithubClient` 只有 `getFileContent(owner, repo, path, ref)`——**必须先知道路径**，没有列目录/树的接口。只按 diff 判断必然假阳性（"新键没人用"其实被未改动的代码引用着），与本项目"漏报优于噪声"的取向冲突。`module/tool` 下仍只有 `SqlRiskTool`/`SecretScanTool`/`TestSuggestionTool` 三条规则 |
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

## E. 记忆与上下文（18）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| E1 | 任务内共享 Context | ✅ | `ReviewFileReviewer.review` 构建一次 `ReviewContext` 供所有文件共享 |
| E2 | 跨评审复用上次结论 | ✅ | **R6**：`review_finding_memory`（唯一键 `repo_owner + repo_name + pr_number + finding_fingerprint`）按 PR 维度累积 finding 状态。`ReviewFindingMemoryService.reconcile` 在保存 `review_issue` 的**同一事务**内对账。开关 `finding-memory-enabled` 默认 **false**（灰度） |
| E3 | 上次已提问题不重复提 | ✅ | **R6**：命中的历史 finding 标 `publishDecision=SUPPRESS` + 原因（`dismissed by a maintainer` / `already reported in a previous review` / `already reported for this commit`），`ReviewFindingRanker` 与 `ReviewCommentBudgetAllocator` 会过滤掉，不再发评论。**抑制必须可审计**：finding 仍落库、摘要仍计入"其中 N 条已被抑制"，不静默消失。**匹配用双指纹 OR**：`findingFingerprint = sha256(filePath\|issueType\|归一化标题)` 与 `codeFingerprint = sha256(filePath\|建议内容)`，**两者都不含行号**（抗 rebase 漂移）；识别信息不足时返回 `null` 并按"全新问题、永不抑制"处理（宁可不匹配也不过度匹配） |
| E4 | 跨评论 Agent 对话 | ✅ | `AgentConversationContextBuilder.build`：同 repo/pr、`commandType=CHAT`、`status=SUCCESS`、排除自身，取最近 N 条，`oneLine` 拍平后注入，标记为不可信 |
| E5 | Agent 异步恢复 continuation | ✅ | `AgentRunService.markWaiting`/`prepareResume` + `AgentWorkflowResumeJob`；`AgentChatTaskRunner:115-127` |
| E6 | 仓库级团队规范 | ✅ | 两条机制：① RAG 规则库（`rule_document`/`rule_chunk`，**服务端**维护）；② **R12 的路径级审查指令**（`E17`，**仓库内**随代码 review）。两者互补：前者是团队通用规范，后者是"这个目录要特别注意什么" |
| E7 | 仓库级历史误报抑制 | ✅ | **R7**：`ReviewDismissalService` 用 GraphQL 读 `reviewThreads` 的 `isResolved`，只对"首条评论由本 bot 发出"的线程把对应 finding 标 `DISMISSED`（比 `RESOLVED` 更强：后续被重新识别也直接抑制）。**prompt 注入（本轮补完）**：DISMISSED 列表经 `ReviewContext.dismissedFindings` → `AiReviewRequest` → `AiReviewContextFormatter` 注入每文件 prompt **顶部**，让模型根本不生成这些 finding。开关 `dismissal-sync-enabled` 默认 **false**。**两道刻意的约束**：① 注入额外挂在 `finding-memory-enabled` 上——注入让 finding 压根不落库，会破坏"可审计"，所以它只能是 R6 可审计抑制之上的省 token 优化；② 注入段放在 prompt 最前，因为 `ReviewLlmInputLimiter` 按**头部**截断，放末尾会第一个被丢 |
| E8 | LLM 审查缓存 | ✅ | `ai_review_cache`，TTL 7 天（`codepilot.llm.review-cache-ttl-days`） |
| E9 | RAG 召回缓存 | ✅ | `ReviewRagCache`，TTL 300s / LRU 256 |
| E10 | 👍/👎 反馈学习 | ❌ | 无 reaction 处理 |
| E11 | LLM 调用日志 | ✅ | `llm_call_log` + `LlmCallLogCleanupJob`（保留 30 天） |
| E12 | 评论 resolution 跟踪 | ✅ | **R7**：`GithubClient.listReviewThreads` 用 **GraphQL** 读 `reviewThreads { id isResolved comments(first:1) { author { login } body } }`（REST 不暴露 resolve 状态）。只认**首条**评论作者是本机，并通过行内评论正文里的 `<!-- codepilot-finding:<16hex> -->` 把线程关联回 finding。**任何失败都降级为"不抑制"**（GraphQL 权限不足/限流/拿不到 bot 登录名 → 一条都不抑制，只记 warning），绝不让审查失败。**注**：只是"读状态做抑制"，不做 `resolveReviewThread` 反向写（C9 仍 ❌） |
| E13 | 已发 inline 指纹去重 | ✅ | `GitHubInlineCommentFingerprintExtractor`（同 headSha 内有效） |
| E14 | 命令任务日志 | ✅ | `pr_command_task_log`；`AgentToolTraceAspect` 记录每次 Tool 调用 |
| E15 | 会话压缩 | ✅ | **R9**：原先 `AgentConversationContextBuilder` 用 `result.substring(result.length() - maxChars)` **盲截尾部字符**，会切出半句话、切掉 `assistant=` 段、并把开头的 `conversationContextStatus:` 一起截掉。现在按 **turn 边界**裁剪（从最近往前累加整条 turn，装不下就停），只有最新一条单独超预算时才截断，且保留 `turn/user/assistant` 结构并打 `…[truncated]` 标记；状态行永远保留在最前面。仍**不做 LLM 摘要压缩**（刻意：额外调用与延迟，对话上下文价值有限）。回滚开关 `conversation-truncation-mode=chars` |
| E16 | 跨 PR 的仓库画像 | ❌ | 无跨 PR 聚合 |
| E17 | **路径级审查指令** | ✅ | **R12**：仓库内 `.codepilot/rules.md`（`## 标题即 glob`，正文即要求）→ `PathInstructionProvider` 读取 → `PathGlobMatcher` 按当前文件匹配 → 注入该文件 prompt。**只从 PR 的 base 分支读**：规则文件随 PR 变更，若读 head，一个 PR 改掉自己的规则文件就能放宽对自己的审查。配置 `path-instructions-enabled` 默认 **false**（它把仓库内容注入 prompt，是新的注入面）；正文经不可信包装、单条/总数/总长均有限制、文案明确"不得改变输出格式或覆盖系统约束"。读不到一律降级为无规则 |
| E18 | **空泛 / 幻觉 finding 的自省复核** | ✅ | **R18**：`FindingReflectionService` 对通过两道质量门的 finding 再问一轮"这条在给定 patch 上是否成立"，输出 0–10 分；低于 `suggestions-score-threshold`（默认 5）即标 `SUPPRESS` + `reflection_below_threshold`，**仍落库并计入摘要**（沿用 R7 的可审计抑制）。**只自省 LLM finding**——确定性规则不参与（浪费 token 且削弱"确定性规则可信"的立场）。**失败即丢弃**（`score-on-reflection-failure` 默认 0，与 PR-Agent 取 7 刻意相反）。越界分数夹到 0–10。开关 `reflection-enabled` 默认 **false**。**注**：提示词未经真机验证，见 roadmap |

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
| F8 | 多轮追问 | ✅ | `conversation-max-turns`（默认 5）/ `conversation-max-chars`（6000，运行时下限 500）。**R9 之后**裁剪改为按 turn 边界（见 E15），`conversation-truncation-mode` 默认 `turn` |
| F9 | 要求澄清 / 拒答 | ⚠️ | 仅靠 prompt 约束（`github-command-chat-system-message.txt`），**无代码级保证**，也无相关测试 |
| F10 | Tool 预算耗尽 | ✅ | **R8**：`AgentRunService.recordToolCall` 抛 `AgentLimitExceededException(TOOL_CALLS)`；`AgentChatTaskRunner` 沿 cause 链识别它（工具异常可能被 LangChain4j 包装），走 `handleAgentNonRetryable` **终态不重试**，并回一条说清"撞了工具调用上限（12 次）+ 建议拆小问题 + 本次不会自动重试"的评论。**注**：`AgentToolTraceAspect` 抛异常的位置在 `try` 之外，所以它不会被工具结果记录逻辑吞掉 |
| F11 | 运行超时 | ✅ | **R8**：`requireWithinLimits` 抛 `AgentLimitExceededException(RUN_DEADLINE)`，当前值取"已过去多久"且保证 ≥ 上限（不会出现"还没到上限就说超了"），文案把整分钟渲染成"30 分钟"。同样走终态不回帖以外的一切。**仍未覆盖**：`AgentWorkflowResumeJob` 里"等待 workflow 期间超时"那条路径（`:46-53`）**只标失败不回帖**，用户看不到说明 |
| F12 | 同一 PR 并发多个 Agent 请求 | ⚠️ | 每个评论独立 `pr_command_task` + `agent_run`；`AgentConversationContextBuilder` 只注入 **已 SUCCESS** 的历史任务 → **两个同时在跑的 run 互相看不见** |
| F13 | issue_comment 非 PR | ✅ | `GitHubWebhookPayloadParser:82-84` 无 `/issue/pull_request` → `ignored("not pull request comment")` |
| F14 | **PR review comment 里的 @mention** | ✅ | **R14**：`parseReviewCommentEvent` 解析 `pull_request.number` / `comment.path` / `comment.line` / `comment.in_reply_to_id`，与 `issue_comment` 共用命令解析与作者判定。回答**回复进那条 thread**（`replyToReviewComment`），并把 `reviewCommentAnchor: path/line` 注入 Agent 上下文。开关 `review-comment-mention-enabled` 默认 **true**。**新增事件类型时同步扩了所有"是否评论事件"判定**：`GithubCommandRouter` 的作者白名单、`GitHubWebhookService` 的机器人自过滤与去重键——其中作者白名单原先只认 `issue_comment`，不改就会让 diff 行评论**完全绕过授权**（已加回归测试并验证过会失败）。**未做**：把该行附近的 diff 片段注入上下文 |
| F15 | PR 描述里的 @mention | ❌ | 解析器只读 `pull_request.title`(:55)，不读 body；`pull_request` 事件走审查链路而非 Agent 链路 |
| F16 | diff 里的 prompt injection | ✅ | 离线评估 `PromptRegressionEvalTest` + `AiReviewPipelineEvalTest` 有该场景；`PromptInputSanitizer.escapeUntrustedBlockDelimiters` |
| F17 | 网页内容的 prompt injection | ✅ | `AiReviewContextFormatter` 标记外部内容为不可信；`WebDocumentFetchService` 有防护 |
| F18 | 用户要求取消运行 | ❌ | 无取消机制。全仓库唯一的 `cancel` 是 `GithubRequestExecutor:53,56` 的 HTTP `future.cancel(true)` |

---

## G. 协作与反馈（8）— 由"整维度空白"变为部分覆盖

| ID | 场景 | 状态 | 证据 |
|---|---|---|---|
| G1 | 人回复 bot 评论 | ❌ | 仍无 `in_reply_to`、无 reaction 处理。**R7 靠近了一半**：`listReviewThreads` 会读到线程里的评论正文（用于解析 finding 标记），但不解析"人在回复里说了什么"，也不回帖 |
| G2 | 人 resolve / dismiss | ✅ | **R7**：`ReviewDismissalService.syncResolvedThreads` 把"已 resolved **且** 首条评论由本 bot 发出"的线程对应 finding 标 `DISMISSED`。只看发起者这一条是硬约束：任何人都能开个线程再 resolve，照单全收等于让人用 resolve 别人的讨论来关掉我们的 finding。开关 `dismissal-sync-enabled` 默认 **false**（需 GraphQL 权限） |
| G3 | 人 👍/👎 | ❌ | 仍无 reaction 处理。**这是 R7 的刻意选择**：对标里 Greptile 用 reaction，我们选 resolve thread——人在看代码时更自然的动作是 resolve，且 reaction 语义模糊（"这评论有用"≠"这是误报"） |
| G4 | 人手动编辑 bot 评论 | ❌ | Summary 仍会被下次审查**按标记命中并覆盖回去**，无"人改过"检测。**注意 R1 并没有改变这一点**：R1 修的是"别覆盖错**用途**的评论"（Agent 回复 vs 摘要），不是"别覆盖**人**的编辑"——摘要被更新本身是设计意图（幂等更新），问题在于不感知人工修改 |
| G5 | 人无视 bot 直接合并 | ❌ | 无 `closed`/`merged` 事件处理，无复盘数据 |
| G6 | 多 reviewer | ❌ | 不做 reviewer 维度区分 |
| G7 | 人反馈"这是误报" | ✅ | **R7**：唯一入口是"在 GitHub 上 resolve 掉 bot 开的那个 review thread"——用 GitHub 原生状态而非自然语言，确定且可查询。抑制是**持续有效**的（人的 resolve 不会过期释放）。**可审计**：finding 仍落库并计入摘要的"已被抑制"条数。默认 **false** |
| G8 | 人要求只重审某个文件 | ❌ | `/review` 无参数化能力 |

> **验收方式的更新**：初版这里写的是 `grep -r 'reaction|dismiss|resolveThread|unresolve|in_reply_to' src/main/java` → 0 命中。
> 该断言**现在不再成立**：R7 引入了 `ReviewDismissalService`、`GithubClient.listReviewThreads`、
> `ReviewFindingMemory.STATE_DISMISSED`。仍有命中缺口的只有 `reaction` / `in_reply_to` / `resolveReviewThread`（写方向）。

---

## H. 性能与成本（8）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| H1 | 首评延迟 | ⚠️ | 有 `codepilot.review.task.duration` Timer，但无 Prometheus registry，**无 SLO、无分位直出**。R10 新增 `docs/observability.md` 说明查询方式（只能取当前累计值，算速率要自己采样两次） |
| H2 | 单 PR token 成本 | ⚠️ | **R10 起生产有 token 计数**：`codepilot.review.llm.tokens{kind=input\|output}`，数据取自 LangChain4j `Result.tokenUsage()`。**但仍是全局按方向累加，没有 per-PR / per-task 分解**——回答不了"这个 PR 花了多少"。另：未配置 `codepilot.llm.pricing` 时**不产生任何金额指标**（`cost.status=unavailable`），这是刻意的，见 `observability.md` |
| H3 | 缓存命中率 | ✅ | **R10**：`codepilot.review.llm.cache{result=hit\|miss}`（应用级响应缓存）与 `codepilot.review.rag.cache{result=hit\|miss}`。**刻意分成两条**：只看 token 下降无法归因"省下的 token 来自响应缓存还是 RAG 少召回" |
| H4 | 并发背压 | ✅ | listener `concurrency=2`/`max=4`/`prefetch=1` + `max-parallel-files=2` |
| H5 | GitHub rate limit | ✅ | `GithubRequestExecutor` + `rate-limit-max-attempts=3` 指数退避 |
| H6 | 队列积压 | ❌ | 仍无队列深度指标。计划中的 `codepilot.review.queue.backlog`（RabbitMQ `QueueDeclarePassive` 读 message count）**本轮未做**——方案自己要求"失败时降级为不暴露而不是让启动失败"，该降级设计需单独验证。目前只能用 `task.queue.wait` 间接推断 |
| H7 | 大 PR 审查时长 | ⚠️ | 有 Timer，但无按文件数/跳过数的分解 |
| H8 | Embedding 成本 | ❌ | 仍不统计 |

> R10 之后，`docs/architecture.md` 的指标清单已补齐全部 `codepilot.*` 指标（含 `codepilot.agent.*`），
> 并新增 `docs/observability.md` 作为指标的唯一完整来源（含义 + 无 Prometheus 时的查询方式 + 已知缺口）。

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
| I9 | 毒消息 → DLQ | ✅ | **R3**：死信路由（`default-requeue-rejected=false`）+ `dead_letter_message` 表（`uq_dead_letter_digest_status` 去重）+ **手动 ack 的死信消费者** + `/api/admin/dead-letters`（查看 / 重放 / 丢弃）+ 维护任务，attempt 会累积。重放是写侧动作，受 `dlq.replay-enabled`（默认 **false**）门控 |
| I10 | Flyway 迁移失败 | ✅ | `validate-on-migrate: true` + `clean-disabled: true` |
| I11 | App token 过期 | ⚠️ | `app-token-cache-skew-seconds=60` 提前刷新；但长任务（`run-deadline=30m`）中途过期后的重取行为无专门测试 |
| I12 | 日志/磁盘 | ⚠️ | LLM 调用日志有 30 天清理；应用日志无轮转配置（`.gitignore` 忽略 `logs/`，但无 size/rotate 策略） |

---

## J. 安全（12）

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| J1 | diff 中的 secret | ✅ | `SecretScanTool`；且 `SensitiveDataSanitizer` 全局脱敏 |
| J2 | diff 中的 prompt injection | ✅ | 见 F16 |
| J3 | 恶意 PR URL / SSRF | ✅ | `GithubPrUrlParser` 用 `PR_URL_PATTERN` 强制 `https://github.com/{owner}/{repo}/pull/{number}`，出网目标由配置的 `api-base-url` 决定，**PR URL 无法把请求引到任意主机** |
| J4 | 不可信网页内容 | ✅ | `WebDocumentFetchService` + `AgentWebResourceService`：HTTPS、域名白名单、拒绝私网 DNS/重定向/非文本/超大响应 |
| J5 | 审批伪造 | ✅ | `FixApprovalService` 以持久化原始评论 + 用户 + repo/PR + head SHA + patch SHA-256 + 允许路径 + 开关共同校验 |
| J6 | allowlist 绕过 | ✅ | `GithubRepositoryPolicy` 在 webhook 与命令路由两处校验 |
| J7 | 非协作者通过 @bot 触发动作 | ✅ | **多道门，且默认 fail-closed**：① 命令只走 `issue_comment`（`GitHubWebhookService:111`），其他事件不经过 `route`；② `GithubCommandRouter.isAllowedCommentAuthor` 要求 `author_association` ∈ `OWNER,MEMBER,COLLABORATOR`（默认，可配），**允许列表为空时一律拒绝**；③ association 取自 `comment.author_association`（`GitHubWebhookPayloadParser:114`），由 GitHub 服务端签发；④ bot 自己的评论被 `isBotAuthoredIssueComment` 过滤，不会自激；⑤ 写侧动作另有四道门（见 J12）。**弱点**：`COLLABORATOR` 不区分读写权限，只读协作者也能发命令（写侧仍有额外门） |
| J8 | bot 自我循环 | ✅ | `GitHubWebhookService.isBotAuthoredIssueComment:157` + `GitHubWebhookPayloadParser:88` 的 marker 自过滤 |
| J9 | 日志泄露 | ✅ | `SensitiveDataSanitizer.redact` / `redactAndTruncate` |
| J10 | 评论里回显敏感信息 | ⚠️ | `SecretScanTool` 只输出行号与关键词上下文（`normalizeKeywordText`/`QUOTED_LITERAL` 均用于**匹配**，不写入消息体），构建 issue 文本时未发现直接拼接原始 secret；但缺少"禁止回显原文"的显式测试断言，属**未加固**而非已知漏洞 |
| J11 | 不同 GitHub 用户 / 组织的租户隔离 | ❌ | **刻意未做，不是疏漏。** 现状是**单租户自托管**设计：① 无任何 user/tenant 表（全部 16 张表都没有 owner 外键）；② API 侧只有**单个全局** `ApiKeyAuthFilter` key，无用户维度、无按 key 分租户；③ `GithubAuthTokenProvider` 在 `APP` 模式下**已按 `owner/repo` 解析 installation**（`installationIdByRepo`，每 installation 一个 token）——但若配置了 `appInstallationId` 则退化为单一安装，隔离消失。真正的准入控制是 `GithubRepositoryPolicy` 仓库白名单。**判断依据**：GitHub 的租户边界是 **App installation / 组织**，不是 user；且当前形态下不存在第二个租户，加隔离是纯负债。要做的话工作量在**数据层**（新增 installation 映射表 + 各表加租户列 + 所有查询加谓词 + webhook 入口绑定 installation），估 M–L。**附带的小问题**：`CommentMarkers.REVIEW_SUMMARY` 默认值硬编码了 `<!-- codepilot-ai-review:liche719/codeAireview -->`，功能上无害（查找是 PR 作用域），但换仓库部署时每条摘要评论里都会嵌着作者自己的仓库名 |
| J12 | **自动审查路径的资源滥用** | ❌ | `GitHubWebhookService:127` 的自动审查路径**不做任何作者校验**——`opened/synchronize/reopened` 直接建任务，唯一门槛是仓库白名单（默认为空 = 不限制）。**后果**：公开仓库上任何人都能 fork 后开 PR，触发完整 LLM 审查，烧 token 与 GitHub API 配额；`allowedCommentAuthorAssociations` **管不到这条路径**（它只管 `issue_comment` 命令，见 J7）。**也没有任何 per-user / per-repo 的审查配额或速率限制**——`ApiRateLimitFilter` 只保护 REST API，不覆盖 webhook 触发的审查。**这是与"别的用户通过 bot 做事"最相关的真实缺口**，但性质是**滥用防护**而非多租户数据隔离（见 J11）。缓解手段：配 `allowed-repositories` 白名单、或用 GitHub 侧的 fork PR workflow 审批 |

---

## K. 场景枚举的自我修正（Phase 1 对标后补）

对标调研完成后，发现原始 142 场景矩阵**遗漏了一个维度**，在此如实补充，不修改既有编号：

| ID | 场景 | 状态 | 证据与说明 |
|---|---|---|---|
| **C19** | **是否阻塞合并（门禁语义）** | ✅ | **R17**：`ReviewGateEvaluator` 三态判定 + `GithubClient.createCommitStatus`（context 默认 `CodePilot Review`），判定结果落库 `review_task.gate_status` / `gate_reason`。**默认 `gate-enabled=false`，即不写任何 status**——对标四家里三家默认不阻塞（reviewdog `-fail-level=none`、Danger JS `--failOnErrors` 默认 false、SonarQube 用独立 check），且都刻意把"产生评论"与"判定失败"解耦。开启后也只在**确定性规则**达到 `gate-min-severity` 时才 `failure`；`gate-include-llm-findings` 默认 **false**（拿不确定输出卡合并不负责任）。**一处 API 事实**：commit status **没有** `neutral`（那是 Checks API 的 conclusion），NEUTRAL 映射为 `success`。**刻意未做** `on-pass=silent`——它会回退 R2 的"覆盖不足必须明说" |

因此本矩阵的准确表述是 **143 个场景**（142 + 1 补漏），而非最初的 142。

> 保留这条自我修正记录是刻意的：它说明这份矩阵并非一次穷举就完备，**对标外部系统是发现自身枚举盲区的有效手段**。

---

## L. 与简历/文档口径的对齐（供后续引用）

> 初版这里误用了 `## K.`，与上一节编号撞车，现改为 `L`。

| 说法 | 核实结果 |
|---|---|
| "775 个自动化测试" | ✅ 正确（`mvn clean test`，R1–R18 之后）。初版写 540 是当时的真实值，现已过时 |
| "Precision 85.71% / Recall 100%" | ⚠️ **该口径已作废**（D2 之后改为）：**26 个样本，per-tag 全绿，aggregate precision/recall = 100%**。但要连同两点一起说：① 这是**回归基线**不是准确率——它用 mock 的 LLM 输出走真实流水线，测的是管线与闸门，不是模型判断力；② 26/26 的 **Wilson 95% 置信下界只有 87.1%**（旧的 6/7 更是只有 48.7%）。**引用时必须带上 n，不要单独报百分比** |
| "文件级并发 / 单文件失败隔离" | ✅ 正确 |
| "patch verification + location guard" | ✅ 正确 |
| "Summary marker 幂等 / inline fingerprint 去重" | ✅ 正确，且**S1 覆盖缺陷已由 R1 修复**（初版此处标注"但 marker 被 Agent 回复复用，存在 S1 缺陷"，已过时） |
| "支持多行区间评论 / 一键应用建议" | ✅ 正确（R4 / R5），但 `suggestion-enabled` **默认 false**，且"建议真能被应用"**未做真机验证** |
| "跨 push 记住已提过的问题 / 支持忽略误报" | ✅ 正确（R6 / R7），但 `finding-memory-enabled` 与 `dismissal-sync-enabled` **都默认 false**，属未灰度能力 |
| "p95 157ms" | ❌ **不属于本项目**，实际来自隔壁 `letchat` |
| "DLQ 便于排查" | ✅ 现在成立（R3 落地后可查看/重放/丢弃）。初版标注"⚠️ 无消费者与工具，实际不可运营"已过时 |
| "非协作者无法用 @bot 做事情" | ✅ 成立，且是**多层 fail-closed**（J7）。但**不要说成"完全不能滥用"**：自动审查路径不校验作者，公开仓库上外人 fork 开 PR 仍能触发审查烧 token（J12）。被问到时区分清楚——**命令授权做了，资源滥用防护没做** |
| "做了多租户隔离" | ❌ **不能这么说**。是单租户自托管设计，无 user/tenant 表（J11）。可以说的是"权限边界在 installation / 仓库白名单这一层" |

> 重新统计场景数的脚本：`scripts/count-scenarios.sh`（本轮新增，供后续改动后复核，避免再次出现表格与明细不一致）。
