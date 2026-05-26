# AI Review E2E Eval Baseline

This document records the first offline baseline for the AI review pipeline eval added for issue #19.

## Goal

The benchmark is designed to catch review-quality regressions without calling a live LLM. It replays labeled PR-like diffs through the production review path:

```text
ReviewFile
  -> ReviewContextBuilder
  -> AiReviewServiceImpl
  -> deterministic rules
  -> offline LLM replay
  -> ReviewResultMerger
  -> ReviewIssueAssembler
  -> ReviewIssuePatchVerifier
  -> ReviewIssueLocationGuard
  -> ReviewFindingRanker
```

## Fixtures

Cases live in `src/test/resources/eval/ai-review-pipeline-cases.json`.

Current scenario coverage:

- SQL risk with low-quality LLM noise suppression.
- Hardcoded secret plus prompt-injection text inside diff.
- Business service change missing test coverage.
- API contract risk from a grounded LLM finding.
- Runtime configuration security regression.
- Invalid LLM response with partial PR-level degradation.

Each case can declare:

- `expectedFindings`: issue type, minimum severity, minimum score, source, channel, and optional rule reference evidence.
- `mustNotFindings`: findings that must not be published.
- `llmResponses`: strict-schema offline model output per file, or intentionally invalid text for parser-failure cases.
- `scenarioTags`: labels used to reason about coverage.

## Metrics

`AiReviewPipelineEvalTest` computes:

- precision
- recall
- must-not-comment violation rate
- parse failure rate
- average replay latency
- estimated prompt tokens

Current thresholds:

- precision >= 0.75
- recall >= 0.85
- must-not-comment violation rate <= 0.0
- parse failure rate <= 0.20

These are deliberately lightweight first-baseline thresholds. Raise them after the benchmark has more labeled cases and fewer intentionally failing replay scenarios.

## Run

```bash
mvn "-Dtest=AiReviewPipelineEvalTest" test
```

For normal CI confidence, run it together with the existing deterministic and prompt evals:

```bash
mvn "-Dtest=AiReviewPipelineEvalTest,DeterministicReviewEvalTest,PromptRegressionEvalTest" test
```

## Non-Goals

- It does not call a live model.
- It does not validate GitHub webhook delivery or actual PR comment posting.
- It does not measure real provider token usage or price.
- It does not replace sandbox E2E tests for webhook / summary / inline comments.

## Next Extensions

- Add real anonymized PR diffs as labeled replay cases.
- Add case-level baseline snapshots so prompt or planner changes can compare deltas.
- Track source-specific metrics for `TOOL`, `LLM`, and `SYSTEM` findings.
- Add false-positive fixtures for deterministic rules, especially configuration and framework placeholders.
- Add GitHub sandbox E2E coverage under issue #20.
