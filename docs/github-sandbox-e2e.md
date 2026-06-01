# GitHub Sandbox E2E

This guide describes the opt-in sandbox test added for issue #20.

The test validates the real GitHub integration path against a running CodePilot instance:

```text
signed GitHub webhook
  -> /api/github/webhook
  -> review task
  -> GitHub inline comment
  -> linked issue lookup
  -> /review issue_comment webhook
  -> GitHub summary comment
  -> target/github-sandbox-e2e/result-*.json
```

## Sandbox PR Requirements

Use a disposable GitHub repository or a disposable PR in this repository.

The PR should contain at least one deterministic review finding so inline comments are created. A simple SQL string concatenation, hardcoded secret, or missing-test service change is enough.

For strict linked issue verification, link an issue to the PR and set `CODEPILOT_GITHUB_SANDBOX_EXPECTED_LINKED_ISSUE_NUMBER`.

For repeat runs, push a fresh commit to the sandbox PR before running the test. The E2E checks comments updated or created after the test starts, so stale comments do not mask a broken publish path.

## Required CodePilot Runtime Config

The CodePilot instance under test must be running and reachable from the test process.

Required app-side runtime settings:

```bash
CODEPILOT_GITHUB_WEBHOOK_ENABLED=true
CODEPILOT_GITHUB_WEBHOOK_SECRET=<same secret used by the test>
CODEPILOT_GITHUB_COMMENT_ENABLED=true
CODEPILOT_GITHUB_INLINE_COMMENT_ENABLED=true
CODEPILOT_GITHUB_TOKEN=<token with repo pull request comment permissions>
CODEPILOT_API_AUTH_API_KEY=<internal api key>
```

Use least-privilege tokens, app private keys, webhook secrets, and API keys. Keep them in `.env`, server secret stores, or GitHub Actions secrets; do not paste real values into PR comments, shell history, CI logs, or committed files.

If repository allow-listing is enabled, include the sandbox repository.

## Test Environment

Set these variables before running the test:

```bash
CODEPILOT_GITHUB_SANDBOX_E2E_ENABLED=true
CODEPILOT_GITHUB_SANDBOX_APP_BASE_URL=https://your-codepilot.example.com
CODEPILOT_GITHUB_SANDBOX_OWNER=liche719
CODEPILOT_GITHUB_SANDBOX_REPO=codeAireview
CODEPILOT_GITHUB_SANDBOX_PULL_NUMBER=123
CODEPILOT_GITHUB_SANDBOX_HEAD_SHA=<current PR head sha>
CODEPILOT_GITHUB_SANDBOX_PULL_TITLE="Sandbox review trigger"
CODEPILOT_GITHUB_SANDBOX_TOKEN=<token used by the verifier>
CODEPILOT_GITHUB_SANDBOX_WEBHOOK_SECRET=<same secret configured in CodePilot>
CODEPILOT_API_AUTH_API_KEY=<internal api key configured in CodePilot>
```

Optional strict assertions:

```bash
CODEPILOT_GITHUB_SANDBOX_EXPECTED_INLINE_PATH=src/main/java/com/example/DemoService.java
CODEPILOT_GITHUB_SANDBOX_EXPECTED_INLINE_LINE=42
CODEPILOT_GITHUB_SANDBOX_EXPECTED_LINKED_ISSUE_NUMBER=456
CODEPILOT_GITHUB_SANDBOX_TIMEOUT_SECONDS=300
CODEPILOT_GITHUB_SANDBOX_POLL_INTERVAL_SECONDS=5
```

The test also accepts equivalent Maven system properties, for example:

```bash
mvn "-Dtest=GitHubSandboxE2ETest" "-Dcodepilot.github.sandbox.e2e.enabled=true" test
```

## Run

```bash
mvn "-Dtest=GitHubSandboxE2ETest" test
```

Default local and CI runs skip this test unless `CODEPILOT_GITHUB_SANDBOX_E2E_ENABLED=true`.

## Output

On success, the test writes a report like:

```text
target/github-sandbox-e2e/result-2026-06-01T01-23-45Z.json
```

The report includes:

- sandbox repo and PR number
- PR head SHA
- inline review task id
- summary review task id
- inline comment URL, path, and line
- summary comment URL
- linked issue count

## What This Test Proves

- GitHub webhook signature and routing work against a real CodePilot instance.
- The review task reaches `SUCCESS`.
- GitHub inline comments can be posted on real PR diff lines.
- Linked issue lookup works against the real GitHub API.
- `/review` issue comments trigger summary comment publishing.

## What It Does Not Prove

- It does not create the sandbox repository or PR automatically.
- It does not replace unit tests for parser, ranking, or line mapping edge cases.
- It does not validate GitHub App installation tokens; issue #18 covers that auth model.
