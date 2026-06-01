# GitHub Auth Modes

CodePilot supports two GitHub authentication modes.

```text
auto -> use GitHub App when app config is present, otherwise use PAT
pat  -> always use CODEPILOT_GITHUB_TOKEN
app  -> always use GitHub App installation tokens
```

## PAT Mode

PAT mode is the simplest self-hosted setup.

```bash
CODEPILOT_GITHUB_AUTH_MODE=pat
CODEPILOT_GITHUB_TOKEN=<fine-grained token>
```

Recommended fine-grained token permissions:

- `Metadata: Read`
- `Contents: Read`
- `Pull requests: Read and write`
- `Issues: Read and write`
- `Contents: Read and write` only if `@x-pilotx fix` is enabled and allowed to push commits

PAT mode is acceptable for local development and small self-hosted instances. It is not a good enterprise or SaaS story because one long-lived token can cross repository boundaries if it is over-scoped.

## GitHub App Mode

GitHub App mode is the preferred production direction. CodePilot creates a short-lived app JWT, resolves the repository installation, mints an installation access token, and caches it until shortly before expiration.

```bash
CODEPILOT_GITHUB_AUTH_MODE=app
CODEPILOT_GITHUB_APP_ID=<app id>
CODEPILOT_GITHUB_APP_PRIVATE_KEY_BASE64=<base64 encoded private key>
CODEPILOT_GITHUB_APP_INSTALLATION_ID=
CODEPILOT_GITHUB_APP_TOKEN_CACHE_SKEW_SECONDS=60
```

`CODEPILOT_GITHUB_APP_INSTALLATION_ID` is optional. When it is empty, CodePilot looks up the installation by repository with `GET /repos/{owner}/{repo}/installation`. Set it only for a single-installation self-hosted deployment where you want to avoid that lookup.

Recommended GitHub App permissions:

- `Metadata: Read`
- `Contents: Read`
- `Pull requests: Read and write`
- `Issues: Read and write`
- `Contents: Read and write` only if fix mode is enabled

Recommended GitHub App webhook events:

- `Pull request`
- `Issue comment`

## Private Key Encoding

Prefer `CODEPILOT_GITHUB_APP_PRIVATE_KEY_BASE64` for Docker, CI, and server `.env` files.

```bash
# PowerShell
[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((Get-Content .\codepilot-app.private-key.pem -Raw)))
```

`CODEPILOT_GITHUB_APP_PRIVATE_KEY` also works when the runtime supports multi-line values or escaped `\n` newlines.

## Secret Handling

- Store PATs, app private keys, webhook secrets, and internal API keys in `.env` files, server secret stores, or GitHub Actions secrets.
- Do not commit real secrets to the repository.
- Do not paste real secrets into PR comments, issue bodies, shell history, or CI logs.
- Use least-privilege GitHub permissions and install the GitHub App only on repositories CodePilot should review.
- Set `CODEPILOT_GITHUB_ALLOWED_REPOSITORIES=owner/repo,org/service` in production to keep webhook-triggered work inside the expected repository boundary.

## Current Scope

This implementation removes the hard dependency on a single global PAT for review, linked issue lookup, summary comments, inline comments, file reads, and fix-mode clone tokens.

It does not implement a multi-tenant SaaS installation database. A self-hosted instance still has one configured GitHub App identity. Multi-tenant SaaS would need persistent tenant/install mapping, per-tenant policy, audit logs, and admin UI.
