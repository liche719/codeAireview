# 环境变量

## GitHub

- `CODEPILOT_GITHUB_TOKEN`
  - GitHub Token，用于拉取 PR 文件、查询/创建/更新 PR 评论。
- `CODEPILOT_GITHUB_COMMENT_ENABLED`
  - 是否开启 GitHub PR 评论回写，默认 `false`。
- `CODEPILOT_GITHUB_WEBHOOK_ENABLED`
  - 是否开启 GitHub Webhook 入口，默认 `false`。
- `CODEPILOT_GITHUB_WEBHOOK_SECRET`
  - GitHub Webhook Secret，用于 `X-Hub-Signature-256` 验签。
- `CODEPILOT_GITHUB_WEBHOOK_SKIP_SIGNATURE_WHEN_SECRET_EMPTY`
  - 本地调试用开关。默认 `false`，仅在确认接口不会暴露给不可信来源时才可设为 `true`。

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

## Review Limits

- `CODEPILOT_REVIEW_MAX_FILES_PER_TASK`
  - 单个任务最多进入 AI Review 的文件数，默认 `30`。
- `CODEPILOT_REVIEW_MAX_PATCH_CHARS_PER_FILE`
  - 单个文件 patch 最大字符数，默认 `12000`。
- `CODEPILOT_REVIEW_MAX_TOTAL_PATCH_CHARS`
  - 单个任务累计进入 AI Review 的 patch 最大字符数，默认 `80000`。

## 说明

- 没有配置 `CODEPILOT_GITHUB_TOKEN` 时，GitHub 评论回写会跳过。
- Webhook 默认关闭，开启时建议同时配置 `CODEPILOT_GITHUB_WEBHOOK_SECRET`。
- 没有配置 `CODEPILOT_GITHUB_WEBHOOK_SECRET` 且未显式开启跳过验签时，Webhook 验签会失败。
- 没有配置 LLM 或 Embedding Key 时，应用仍可启动，相关能力会降级为跳过。
