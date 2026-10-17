# AI Review E2E Eval Baseline

This document records the offline baseline for the AI review pipeline eval.

## Goal

The benchmark catches review-pipeline regressions without calling a live LLM. It replays labeled
PR-like diffs through the production review path:

```text
ReviewFile
  -> ReviewContextBuilder
  -> AiReviewServiceImpl
  -> deterministic rules (secret / sql / test-suggestion / ci-workflow)
  -> offline LLM replay
  -> ReviewResultMerger
  -> ReviewIssueAssembler
  -> ReviewIssuePatchVerifier
  -> ReviewIssueLocationGuard
  -> ReviewFindingRanker
```

## Fixtures

Cases live in `src/test/resources/eval/ai-review-pipeline-cases.json` (**26 cases**).

Coverage by `scenarioTags`:

| Tag | What it covers |
|---|---|
| `sql-risk` | `select *`, string concatenation, and a parameterized query that must stay clean |
| `security` / `secret` | hardcoded GitHub token, private-key block, and a `System.getenv` lookup that must stay clean |
| `missing-test` | core business file without tests; and the same file **with** a test change, which must stay clean |
| `ci-workflow` | `pull_request_target` + head checkout, `write-all`, untrusted interpolation in `run:`, and a safe pinned workflow |
| `llm` / `semantic` | grounded findings across BUG_RISK, SECURITY, EXCEPTION_HANDLING, PERFORMANCE, LOGGING |
| `quality-gate` | LLM finding on a line outside the patch (dropped) and on a deleted line (kept, LEFT side commentable) |
| `dedupe` | LLM restating a deterministic SQL finding; only one survives |
| `parse-failure` | invalid model output degrading to `AI_REVIEW_FAILED` |
| `must-not-comment` / `clean` | negative cases: nothing may be published |

Each case can declare:

- `expectedFindings`: issue type, minimum severity, minimum score, source, channel, optional rule reference.
- `mustNotFindings`: findings that must not be published.
- `llmResponses`: strict-schema offline model output per file, or intentionally invalid text.
- `scenarioTags`: labels used for per-tag metrics and coverage assertions.

## Metrics

`AiReviewPipelineEvalTest` computes aggregate precision / recall / must-not-comment violation rate /
parse-failure rate / replay latency / estimated prompt tokens, **and per-tag precision and recall**.

- Every tag that has at least one expected finding must meet the precision and recall floors.
- `REQUIRED_TAGS` asserts that a fixed set of dimensions is still represented, so deleting a whole
  category of cases fails the build instead of quietly shrinking coverage.

Current thresholds (floors, not targets):

- precision >= 0.75
- recall >= 0.85
- must-not-comment violation rate <= 0.0
- parse failure rate <= 0.20

## What the numbers actually mean — read this before quoting them

**`precision = 1.0` here does not mean "the reviewer is 100% accurate".**

This test replays **mocked** LLM output through the real pipeline. It therefore measures the
pipeline's plumbing and gates — parser, assembler, patch verifier, location guard, dedupe, ranking,
publish decisions — **not the model's judgment quality**. A hallucinated finding supplied by the mock
would be faithfully published if it were grounded and commentable; what the mock cannot express is
whether a real model would have produced it.

Consequently the fixture is a **regression baseline**, not an accuracy measurement: labels describe
what the pipeline is *supposed* to do, and the test fails when behavior drifts away from that.

### Sample size and confidence

The baseline previously had **6 cases** (6/7 matches → 85.71%). With that few samples the point
estimate was close to uninformative, and quoting it invited exactly the wrong kind of confidence.

Now: **26 cases, 26/26 matched**. The Wilson 95% lower bound for 26/26 is **87.1%** (the previous
6/7 result had a lower bound of about 48.7%). Even now the interval is wide — **quote precision only
together with `n`**, never on its own.

### Label corrections made during the expansion

While expanding, three expectations turned out to be wrong. They were corrected against *correct
behavior*, not against whatever the pipeline happened to emit:

- `select *` is reported at **LOW**, not HIGH — it is a soft suggestion, and the fixture claimed
  otherwise.
- `AI_REVIEW_FAILED` is produced by the failure factory at **MEDIUM**, not HIGH.
- The `api-contract` case omitted `TEST_MISSING`, even though the changed file is a controller and
  the test-suggestion rule firing there is *intended* behavior. That missing label was the entire
  previous false positive. It was fixed rather than kept as a convenient FP.

Recording this explicitly because "we edited the fixture and precision went up" is otherwise
indistinguishable from fitting the benchmark to the code.

## What this test does NOT cover

These dimensions need the pipeline stages *before or after* the replay harness, so they are covered
by dedicated tests instead of forced into this fixture:

| Dimension | Covered by |
|---|---|
| File budget truncation / path skipping (i18n, vendored, generated) | `ReviewFilePlannerTest`, `ReviewFileClassifierR11Test` |
| Cross-headSha memory and duplicate suppression | `ReviewFindingMemoryServiceTest` |
| Dismissal / false-positive suppression | `ReviewDismissalServiceTest` |
| Suggestion applicability | `ReviewSuggestionValidatorTest`, `GitHubInlineCommentServiceImplTest` |
| Build gate decisions | `ReviewGateEvaluatorTest` |
| Self-reflection gate | `FindingReflectionServiceTest` |
| Multiline range comments | `GitHubInlineCommentServiceImplTest`, `DiffLineMapper` tests |

Other non-goals: no live model call; no GitHub webhook or real comment posting; no real provider
token or price; not a substitute for the sandbox E2E flow in `docs/github-sandbox-e2e.md`.

## Run

```bash
mvn -o "-Dtest=AiReviewPipelineEvalTest" test
```

With the other offline evals:

```bash
mvn -o "-Dtest=AiReviewPipelineEvalTest,DeterministicReviewEvalTest,PromptRegressionEvalTest" test
```

## Next Extensions

- Add real anonymized PR diffs (the current cases are hand-written, which limits how representative
  they are).
- Case-level snapshots so prompt or planner changes can be diffed.
- Separate `TOOL` / `LLM` / `SYSTEM` precision, which the per-tag split only approximates.
- Run the sandbox E2E flow before release-level GitHub integration changes.
