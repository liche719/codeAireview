# Security Policy

CodePilot AI processes GitHub PR diffs, repository metadata, prompts, model responses and optional Git write-back commands. Treat every deployment as a system that can access private source code and paid LLM/GitHub quotas.

## Supported Versions

This repository currently tracks security fixes on the `main` branch only. If you run a fork or private deployment, keep it rebased on `main` and review configuration changes before exposing the service to the internet.

## Reporting a Vulnerability

Please do not open a public issue with exploit details, secrets, tokens, private repository URLs or private code snippets.

Use GitHub private vulnerability reporting when it is enabled for the repository. If private reporting is not available, contact the maintainer privately and include only the minimum reproduction details needed to triage the issue.

Useful report details:

- Affected component, for example API auth, webhook signature verification, GitHub token handling, prompt injection, auto-fix command execution or markdown rendering.
- Impact and attack path.
- Minimal reproduction steps using synthetic data.
- Whether the issue requires `CODEPILOT_GITHUB_FIX_ENABLED=true`, public network exposure or a privileged GitHub token.

## High-Risk Areas

- Keep `CODEPILOT_API_AUTH_ENABLED=true` for any shared or public deployment.
- Set `CODEPILOT_GITHUB_WEBHOOK_SECRET` when webhooks are enabled.
- Configure `CODEPILOT_GITHUB_ALLOWED_REPOSITORIES` in production so arbitrary repositories cannot spend your LLM/GitHub quota.
- Keep `CODEPILOT_GITHUB_FIX_ENABLED=false` unless the deployment has an isolated execution environment and a least-privilege GitHub token.
- Do not allow Maven, Gradle, npm or other build/test commands as fix validation commands in the production app container unless they run in a separate sandbox.
- Never paste real secrets into issues, PRs, prompt fixtures, test cases or logs.

## Disclosure Expectations

The maintainer will try to acknowledge valid private reports promptly, reproduce the issue, and publish a fix or mitigation guidance before public disclosure. Exact timelines depend on severity and maintainer availability.
