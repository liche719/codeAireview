# 演示流程

1. 启动依赖服务。

```bash
docker compose up -d
```

2. 启动应用。

```bash
mvn spring-boot:run
```

如果要演示 GitHub 评论回写和 Webhook 自动触发，请先配置：

```bash
set CODEPILOT_GITHUB_COMMENT_ENABLED=true
set CODEPILOT_GITHUB_INLINE_COMMENT_ENABLED=true
set CODEPILOT_GITHUB_INLINE_COMMENT_MAX_PER_TASK=10
set CODEPILOT_GITHUB_WEBHOOK_ENABLED=true
set CODEPILOT_GITHUB_TOKEN=你的 GitHub Token
set CODEPILOT_GITHUB_WEBHOOK_SECRET=你的 Webhook Secret
```

3. 创建一份 SQL 规范。

```bash
curl -X POST http://localhost:8080/api/rules ^
  -H "Content-Type: application/json" ^
  -d "{\"title\":\"MySQL SQL 编写规范\",\"type\":\"SQL_RULE\",\"source\":\"manual\",\"content\":\"禁止使用 SELECT *。禁止字符串拼接 SQL。UPDATE 和 DELETE 必须带 WHERE。\"}"
```

4. 对规范执行 index。

```bash
curl -X POST http://localhost:8080/api/rules/1/index
```

5. 用 ngrok 暴露本地端口。

```bash
ngrok http 8080
```

6. 在 GitHub 仓库配置 Webhook。

```text
Payload URL: https://xxx.ngrok-free.app/api/github/webhook
Content type: application/json
Secret: 与 CODEPILOT_GITHUB_WEBHOOK_SECRET 保持一致
Events: Pull requests, Issue comments
```

`Pull requests` 用于 PR `opened` / `synchronize` / `reopened` 自动审查；`Issue comments` 用于在 PR Conversation 中输入 `/review` 手动触发审查。

PR 评论命令还支持：

```text
/review
@x-pilotx review
@x-pilotx fix dry-run
@x-pilotx fix
```

`@x-pilotx fix dry-run` previews the generated patch without pushing a commit. `@x-pilotx fix` uses the latest successful review issues, generates a small unified diff, validates it, and only then pushes a new commit to the current PR branch. Automatic fix is only supported for PR branches in the same repository.

7. 提交一个包含 SQL 拼接和敏感信息的 PR。

```java
String sql = "select * from user where name = '" + name + "'";
String token = "abc123";
```

8. 查看结果。

```text
review_task 创建成功
RabbitMQ 正常消费
review_issue 入库
PR 顶部出现 CodePilot AI 审查报告
再次触发时更新原评论，不新增第二条
如果 issue 的 lineNumber 对应 diff 新增行，代码行上出现 CodePilot inline comment
在 PR Conversation 评论 /review，会再次创建 ReviewTask 并触发审查
```
