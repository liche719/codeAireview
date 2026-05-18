# 环境变量

## GitHub

- `CODEPILOT_GITHUB_TOKEN`
  - GitHub Token，用于拉取 PR 文件、查询/创建/更新 PR 评论。
- `CODEPILOT_GITHUB_WEBHOOK_SECRET`
  - GitHub Webhook Secret，用于 `X-Hub-Signature-256` 验签。

## LLM

- `CODEPILOT_LLM_API_KEY`
  - LLM API Key。
- `CODEPILOT_LLM_BASE_URL`
  - OpenAI-compatible Chat Completions 接口地址。
- `CODEPILOT_LLM_MODEL`
  - Chat Model 名称。

## Embedding

- `CODEPILOT_EMBEDDING_API_KEY`
  - Embedding API Key。
- `CODEPILOT_EMBEDDING_BASE_URL`
  - Embedding 接口地址。
- `CODEPILOT_EMBEDDING_MODEL`
  - Embedding 模型名。

## 说明

- 没有配置 `CODEPILOT_GITHUB_TOKEN` 时，GitHub 评论回写会跳过。
- 没有配置 `CODEPILOT_GITHUB_WEBHOOK_SECRET` 时，本地开发可以跳过验签，但会记录 warning。
- 没有配置 LLM 或 Embedding Key 时，应用仍可启动，相关能力会降级为跳过。
