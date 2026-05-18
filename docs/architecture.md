# 架构说明

## 模块划分

### `review task`

负责 PR 审查任务的创建、状态流转、文件保存、问题聚合和结果查询。它是主业务入口，和手动接口、Webhook 入口最终都会汇合到这里。

### `github client`

负责和 GitHub REST API 交互，主要包括拉取 PR changed files、查询 PR comments、创建评论和更新已有评论。

### `webhook`

负责接收 GitHub Webhook 事件，完成验签、事件解析、Redis 去重，并把有效 PR 事件转换成审查任务。

### `rag`

负责编码规范文档的创建、切片、向量化、入库和检索。它为 AI Review 提供团队规则上下文。

### `agent`

负责 AI Review Prompt 构造、RAG 上下文注入、LLM 调用、JSON 解析和审查结果映射。

### `tool`

负责 SQL 风险、敏感信息、单测建议等确定性检查。通过 LangChain4j `@Tool` 暴露给模型自主调用。

### `comment`

负责把最终审查结果格式化成 Markdown，并回写到 GitHub PR 顶部评论，同时支持 marker 幂等更新。

### `mq`

负责 RabbitMQ 生产者和消费者，把审查任务异步化，避免 Webhook 和接口请求阻塞。

### `database`

负责持久化 `review_task`、`review_file`、`review_issue`、`rule_document`、`rule_chunk` 和调用日志等数据。

## 典型数据流

```text
Webhook / 手动请求
  → review task
  → mq
  → github client
  → rag
  → agent
  → tool
  → database
  → comment
```
