# Contributing

Thanks for helping improve CodePilot AI. This project is a GitHub PR review backend, so reliability and security matter more than adding more prompt text.

## Development Setup

1. Install JDK 21, Maven, Docker and Docker Compose.
2. Copy `.env.example` to `.env` and fill local credentials.
3. Start local dependencies with `docker compose up -d`.
4. Run the test suite before opening a PR.

```bash
mvn test
```

## Pull Request Checklist

- Keep changes small enough to review.
- Add or update tests for behavior changes.
- Run `mvn test`.
- Do not commit `.env`, tokens, private repository URLs, model responses containing private code or generated local data.
- Do not stage local audit reports unless the PR is explicitly about documentation.

## AI Review Pipeline Changes

For prompt, parser, review pipeline, tool, RAG or GitHub comment changes, include tests that cover the contract you changed.

Useful test categories:

- Parser/schema tests for JSON fields and enums.
- Prompt regression tests for untrusted input isolation and output contract drift.
- Deterministic tool eval cases for SQL risk, secret scanning and test suggestions.
- GitHub integration unit tests for idempotency, rate-limit handling and comment formatting.
- Security tests for prompt injection, token redaction and unsafe fix validation commands.

Avoid solving architecture problems by only appending more instructions to prompts. Prefer adding deterministic preprocessing, context builders, validators, evaluators or bounded configuration.

## Security-Sensitive Changes

Changes touching API auth, webhook verification, GitHub tokens, auto-fix, command execution, prompt injection defenses, markdown rendering or logging need extra care.

- Keep `CODEPILOT_GITHUB_FIX_ENABLED=false` by default.
- Keep validation commands allowlisted and shell-free.
- Keep secret/token redaction in logs and error messages.
- Keep untrusted PR content isolated before it reaches prompts.
- Update `SECURITY.md` when deployment or disclosure guidance changes.

## Documentation

Update `README.md` or `docs/` when a change affects setup, environment variables, deployment, GitHub permissions, PR commands or security posture.

## License

Do not add or change the project license without maintainer approval. A license choice is a project-level legal decision, not a drive-by cleanup.
