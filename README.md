# CodePilot AI Review

CodePilot AI Review is a Spring Boot based GitHub PR code review backend. The first milestone focuses on the task pipeline:

1. Submit a GitHub PR URL.
2. Parse owner, repo, and pull request number.
3. Create a `review_task` record.
4. Send the task id to RabbitMQ.
5. Consume the message and log it.

## Tech Stack

- Spring Boot 3.x
- MyBatis Plus
- PostgreSQL
- pgvector
- Redis
- RabbitMQ
- Knife4j / OpenAPI
- Lombok

## Quick Start

Make sure Docker Desktop is running before starting the middleware stack.

```bash
docker compose up -d
mvn spring-boot:run
```

Swagger UI:

```text
http://localhost:8080/doc.html
```

Create a review task:

```bash
curl -X POST http://localhost:8080/api/reviews \
  -H "Content-Type: application/json" \
  -d "{\"prUrl\":\"https://github.com/owner/repo/pull/123\"}"
```

The consumer will fetch PR changed files through the GitHub REST API and save them into `review_file`.

For private repositories or higher rate limits, configure:

```bash
# PowerShell
$env:CODEPILOT_GITHUB_TOKEN="your_github_token"

# cmd
set CODEPILOT_GITHUB_TOKEN=your_github_token
```

Useful APIs:

```text
POST /api/reviews
GET  /api/reviews/{taskId}
GET  /api/reviews/{taskId}/files
GET  /api/reviews/{taskId}/issues
GET  /api/github/pulls/files?owner=owner&repo=repo&pullNumber=123
```
