# 可观测性：指标清单与查询方式

> 本文件的用途：把**代码里实际存在**的指标列全，说明每个指标的含义与告警价值，
> 并给出**没有 Prometheus 时**的查询方式。
>
> 背景：本项目只暴露 Actuator 的 `health,info,metrics` 三个端点
> （`application.yml` 的 `management.endpoints.web.exposure.include`），
> **没有引入 Prometheus 注册表**。所以下面所有指标都能查，但没有历史曲线、没有分位直出。
> 引入 Prometheus + Grafana 属部署栈变更，本文件不做这件事。

---

## 1. 查询方式（无 Prometheus）

Actuator 的 `metrics` 端点按名字查询，带 tag 的指标用 `tag=key:value` 过滤：

```bash
# 列出全部指标名
curl -s localhost:8080/actuator/metrics | jq -r '.names[]' | grep '^codepilot'

# 查某个指标的当前值（没有 tag 的指标）
curl -s localhost:8080/actuator/metrics/codepilot.review.task.creation | jq

# 查带 tag 的指标：必须先指定 tag，否则只会返回一个聚合值
curl -s 'localhost:8080/actuator/metrics/codepilot.review.llm.cache?tag=result:hit' | jq '.measurements'
curl -s 'localhost:8080/actuator/metrics/codepilot.review.llm.tokens?tag=kind:input' | jq '.measurements'
curl -s 'localhost:8080/actuator/metrics/codepilot.review.cost?tag=model:deepseek-chat' | jq '.measurements'
```

`/actuator/metrics/<name>` 不带 `tag` 时会返回该名字下**所有 tag 组合的聚合**，
这几乎总是你不想要的——查 tag 指标请始终带上 `tag=`。

**计数器是单调递增的**，Actuator 只给当前累计值。要算"最近一段时间的速率"，
得自己隔一段时间取两次差值。这也是没有 Prometheus 时最实质的缺口。

---

## 2. 指标清单

### 2.1 审查任务生命周期

| 指标 | 类型 | tag | 含义 / 用途 |
|---|---|---|---|
| `codepilot.review.task.creation` | Counter | `result=created\|reused` | 新建 vs 复用任务。`reused` 占比高说明幂等生效（同一 headSha 重复 webhook 没有重复建任务） |
| `codepilot.review.task.queue.wait` | Timer | — | 从创建到被消费者领取的排队时间。**积压的第一手信号**（在没有队列深度指标的情况下） |
| `codepilot.review.task.duration` | Timer | `result` | 任务执行耗时 |
| `codepilot.review.task.execution` | Counter | `result` | 执行结果；`duplicate_skipped` 表示重复投递被租约挡掉 |

### 2.2 文件级审查与发布

| 指标 | 类型 | tag | 含义 / 用途 |
|---|---|---|---|
| `codepilot.review.model.file` | Counter | `result=success\|failure` | 文件级模型审查成败，可算模型失败率 |
| `codepilot.review.outbox.publish` | Counter | `result` | 本地消息发布成功 / 重试 / 最终失败 |
| `codepilot.review.github.publish` | Counter | `result` | GitHub 评论发布成功 / 跳过 / 重试 / 最终失败 |

### 2.3 LLM 用量与成本（R10 新增）

| 指标 | 类型 | tag | 含义 / 用途 |
|---|---|---|---|
| `codepilot.review.llm.cache` | Counter | `result=hit\|miss` | **应用级**响应缓存命中情况 |
| `codepilot.review.rag.cache` | Counter | `result=hit\|miss` | RAG 召回缓存命中情况 |
| `codepilot.review.llm.tokens` | Counter | `kind=input\|output` | 真实模型调用的 token 数 |
| `codepilot.review.cost` | Counter | `model` | 估算金额（美元），**仅当配置了单价才出现** |
| `codepilot.review.cost.status` | Counter | `status=estimated\|unavailable` | 成本可算性 |

**为什么 LLM 缓存与 RAG 缓存分开**：它们是两件不同的事。只看 token 下降无法判断
"省下的 token 来自我们的响应缓存"还是"来自 RAG 少召回了内容"；两个命中率一起看才能归因。

**为什么 `cost` 可能完全不存在**：单价不内置（见下节）。没有 `codepilot.review.cost`
但有 `cost.status=unavailable` 是**正常状态**，表示"不知道花了多少钱"，而不是"没花钱"。

**刻意缺失**：`cache_read` / `cache_creation` token tag。PR-Agent 有它们是因为面向
Anthropic 的 provider 侧 prompt caching；我们的 provider 抽象是 LangChain4j 的 `TokenUsage`，
只有 input / output / total。**发一条恒为 0 的序列比不发更糟**——它看起来像"缓存一次都没命中"，
而真相是"我们不知道"。

### 2.4 Agent Runtime

| 指标 | 类型 | tag | 含义 / 用途 |
|---|---|---|---|
| `codepilot.agent.model.turns` | Counter | — | 模型轮次。与 `max-model-turns`（默认 8）对比可看是否长期贴上限 |
| `codepilot.agent.tool.calls` | Counter | `result=success\|failed` | Tool 调用成败。与 `max-tool-calls`（默认 12）对比有意义 |
| `codepilot.agent.run.transitions` | Counter | `state` | run 状态迁移，含 `completed` / `failed` |
| `codepilot.agent.workflow.wait` | Timer | — | 等待异步 Workflow 的时长 |

> R8 之后，撞上限会抛 `AgentLimitExceededException` 并走**终态不重试**。
> 因此"轮次 / Tool 调用长期贴着上限"值得配告警——那是用户在反复撞墙的信号。

---

## 3. 配置模型单价（可选）

`codepilot.review.cost` 只在显式配置了单价后才产生。**默认不配任何单价**：

```yaml
codepilot:
  llm:
    model: deepseek-chat
    pricing:
      deepseek-chat:
        input-per-million: 0.27
        output-per-million: 1.10
```

单位是**美元 / 百万 token**。

**为什么不内置一份价格表**：单价随厂商调价变化，内置的"当前价格"必然会在某个时间点
悄悄变成错的。而**错的钱数比没有钱数更危险**——它看起来可信，会被拿去做容量决策。
所以这里宁可让 `cost` 缺失，也不猜。

未配置单价时：`codepilot.review.cost.status{status="unavailable"}` 会增长，
`codepilot.review.cost` 不出现。**不要**把 unavailable 读成 0。

---

## 4. 已知缺口（如实记录）

| 缺口 | 说明 |
|---|---|
| **无历史曲线 / 无分位直出** | 只有 Actuator 的当前累计值。要看趋势必须外接采集器 |
| **无队列深度指标** | 计划中的 `codepilot.review.queue.backlog`（从 RabbitMQ `QueueDeclarePassive` 读 message count）**未实现**。目前只能用 `task.queue.wait` 间接推断积压 |
| **无 token 成本的 per-PR 分解** | `codepilot.review.llm.tokens` 是全局按方向计数，无法回答"这个 PR 花了多少" |
| **无 Embedding 成本** | 不做统计 |
| **无 `cache_read` / `cache_creation`** | 当前 provider 抽象拿不到，见 §2.3 |
| **无缓存命中率的历史** | 有 hit/miss 计数，但算比率需要两次采样 |

> 这些缺口不是"忘了做"，是有意识留下的——每一条都写明了是哪一层拿不到数据，
> 或为什么现在不做（例如队列深度指标需要一条管理连接，失败时**必须降级为不暴露
> 而不是让应用启动失败**，这个降级设计本身需要单独验证）。
