# 对标差距分析（2026-10-29）

> 目的：用户要求「跟别人的同类项目比一比，看我们缺什么」，并明确**只做 PR Review 一个场景**，
> 但要把内部细分场景做扎实。
> 前置材料：`docs/benchmark-ai-review-tools.md`（源码级对标 PR-Agent / CodeRabbit / Greptile / Graphite /
> reviewdog / Danger / SonarQube / Semgrep / Copilot code review / Cursor Bugbot 等）。本文不重复那份内容，
> 只做两件事：**① 把"当时落后"的六条重新核对一遍现状；② 把 PR Review 拆成细分场景看覆盖度。**

---

## 一、上次（10 月中）列出的六条落后项，现在什么状态

| # | 上次结论 | 现在 | 证据 |
|---|---|---|---|
| 1 | 覆盖度上报缺失，静默截断 | **已补** | `ReviewReportFormatter` 输出覆盖度区块（跳过文件数、按组折叠）；`ReviewProperties` 里有折叠上限 |
| 2 | 跨 push 记忆与 finding 状态机为零 | **已补** | `ReviewFindingMemoryService`（日志可见 `Review finding memory reconciled, recorded/suppressed/resolved`）；`resolve` 相关代码 172 处 |
| 3 | 误报抑制为零 | **已补** | `ReviewDismissalService` 与 GitHub review thread 联动；表里 `publish_decision=SUPPRESS` 已累计 112 条 |
| 4 | 评论形态缺 `suggestion` 与多行区间 | **已补** | `startLine`/`endLine` 41/79 处；`suggestion` 相关 86 处 |
| 5 | 缺仓库内路径级配置 | **已补** | `PathInstruction` 46 处（glob 匹配 + 注入 prompt） |
| 6 | 缺增量审查（只审本次 push 的变更） | **仍然缺** | 全局搜索 `incremental` = 0；`lastReviewedSha`/`sinceCommit` 均无实现 |

**结论**：六条里五条已经补上。**唯一真正遗留的是"增量审查"**。

不过要跟上一次的判断保持一致：PR-Agent 默认也是全量审，CodeRabbit 默认增量。
我们的场景是个人/小团队仓库，全量审的代价主要是 token 与时间，**不是正确性问题**，
所以它排在"内部细分场景"之后，而不是第一优先。

---

## 二、把 PR Review 拆成细分场景（这是用户真正要的）

同一个"PR 审查"，内部至少有 14 个动作。逐条看我们有没有、做得够不够：

| # | 细分场景 | 我们有没有 | 说明 / 缺口 |
|---|---|---|---|
| 1 | **首次审查（opened）** | ✅ | 全量审 + 摘要 + 行内 |
| 2 | **复审（synchronize，PR 又推了）** | 🟡 | 会重跑全量；**没有对比上次 head，重复的问题会再报一遍**（靠 finding memory 抑制，属补救不是根治） |
| 3 | **行内评论定位** | ✅ | `DiffLineMapping` + location guard，行不在 diff 内直接丢 |
| 4 | **多行区间评论** | ✅ | `startLine`/`endLine` |
| 5 | **可应用 suggestion** | ✅ | 有；不可应用时降级为普通评论（R5） |
| 6 | **同一问题的生命周期**（提出 → 修复 → 标记 resolved） | 🟡 | 有 memory + resolve；但"什么时候算 resolved"依赖读 review thread，匿名/无权限时直接跳过 |
| 7 | **重复评论抑制**（同一 headSha 重跑不刷屏） | ✅ | marker 指纹 + 去重键 |
| 8 | **维护者 dismiss 后不再提** | 🟡 | 有 `ReviewDismissalService`，但依赖 GitHub thread 读取；**读不到时静默跳过**（日志是 WARN，用户感知不到"这次没做去重"） |
| 9 | **误报抑制：同一问题被忽略 N 次后不再提** | ❌ | PR-Agent 有（忽略 3 次）；我们只有 dismiss 同步，没有"累计忽略"门槛 |
| 10 | **大 PR 的预算截断与覆盖度披露** | ✅ | 覆盖度区块 + 跳过分组 |
| 11 | **按文件类型走不同策略**（测试文件、构建脚本、迁移脚本） | 🟡 | 有 `ReviewFileClassifier`（测试/数据库/安全敏感路径），策略粒度较粗 |
| 12 | **路径级指令**（某目录用某套规范） | ✅ | `PathInstruction` |
| 13 | **PR 描述/标题质量检查**（改了什么、有没有关联 issue） | ❌ | 完全没有。CodeRabbit 有 "PR summary 生成"，我们只审代码 |
| 14 | **能否被 /命令 触发子能力**（`/review`、`/describe`、`/improve`） | 🟡 | 有 `/review` 与 `@x-pilotx` Agent 入口；没有 `/describe`（生成 PR 描述）、`/improve`（给改进建议，不逐行评论） |

**图例**：✅ 有且够用 ／ 🟡 有但依赖外部条件或粒度粗 ／ ❌ 没有

---

## 三、按"对用户价值 ÷ 实现成本"排序的缺口

1. **#2 增量审查**（复审只审新 push 的部分）
   - 价值：真实团队里 PR 会被推很多次，全量重审既慢又吵；
   - 成本：中（要存上次 headSha 与已审文件集合，按 compare API 取 delta）；
   - 现在能做的近似：已有 finding memory 做抑制，所以"重复刷屏"被部分挡住。

2. **#9 误报抑制的"累计忽略"门槛**（同一问题被忽略 N 次后不再提）
   - 价值：直接把"吵"降下来，这是 CodeRabbit 类产品口碑的核心；
   - 成本：低（在 `ReviewFindingMemoryService` 里加一个计数器 + 阈值配置）。

3. **#13 PR 描述 / 标题检查**（`/describe`）
   - 价值：一个 PR 的"说明书"质量直接影响 review 效率；实现是一次 LLM 调用 + 一条评论；
   - 成本：低（复用现有评论通道与 marker 去重）。

4. **#14 子命令**（`/describe`、`/improve`）
   - 价值：让产品"有手感"，也让 Agent 入口更像真产品；
   - 成本：低-中（已有 `/review` 的命令解析与 outbox 链路）。

5. **#8 dismiss 读取失败要**显式**告诉用户**（现在只是 WARN 日志）
   - 价值：可信度——"这次没做去重"必须让人知道，而不是静默降级；
   - 成本：极低（把 WARN 提升为覆盖度区块里的一行说明）。

---

## 四、我的判断（不说漂亮话）

**这个项目在"机制覆盖"上已经不低于开源对标**：确定性规则、patch 证据校验、覆盖度披露、
finding 记忆、suggestion、路径级指令、评论去重——对标产品有的它基本都有。

**真正的差距在两处，而且都不是"缺功能"**：

1. **质量数字的可信度**：对标产品的宣传数字来自真实数据与人工标注，我们的 79.2% 来自 26 个手写场景。
   这条这轮已经改善（加了真实提交评测），但样本仍然小；
2. **产品完整度**：增量审查、忽略门槛、PR 描述检查——这些是"用起来顺不顺手"的东西，
   机制上不大，但缺了会显得"做了一半"。

所以下一步如果要继续投入，我建议 **#9 → #8 → #13 → #2**，前三个都是小改动、直接改善体验，
增量审查放最后（它最贵，而且当前规模下收益最低）。
