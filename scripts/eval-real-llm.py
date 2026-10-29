#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真实模型评测（2026-10-29）

为什么需要它：现有的 `AiReviewPipelineEvalTest` 用 `FakeReviewLlmClient` 回放**预设的模型输出**，
所以 precision=1.0 证明的是「流水线与闸门正确」，不是「模型审得好」。这个脚本用**真实模型**跑同一批用例，
把两件事分开量：

  1. 召回（recall）：标注的缺陷有没有被模型找出来；
  2. 误报（false positive）：只在两种情况算误报——
     a) 用例明确写了 `mustNotFindings`（这类不许报）；
     b) 用例是 clean 场景（期望 0 条且标了 clean）。
     其余「模型多报的」不算误报，而是进入**人工复核清单**——真实 PR 里没标注的问题不等于错。

用法（在项目根目录）：

    python scripts/eval-real-llm.py --limit 8          # 先跑 8 个用例看效果
    python scripts/eval-real-llm.py --out report.json  # 全量 + 落报告
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CASES = os.path.join(ROOT, "src", "test", "resources", "eval", "ai-review-pipeline-cases.json")
SYSTEM_PROMPT = os.path.join(ROOT, "src", "main", "resources", "prompts", "ai-review-system-message.txt")
USER_PROMPT = os.path.join(ROOT, "src", "main", "resources", "prompts", "ai-review-user-message.txt")

DEFAULT_RULES = """- Mapper / DAO 层禁止字符串拼接 SQL，必须用参数化查询。
- 代码里不能出现明文口令、私钥、Token。
- 新增 Service / Controller 的公开方法应当有对应测试。
- 日志中不得打印敏感字段（手机号、身份证、密码）。"""


def load_env():
    env = {}
    path = os.path.join(ROOT, ".env")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8", errors="ignore"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                env[key.strip()] = value.strip()
    return env


def fill(template, values):
    for key, value in values.items():
        template = template.replace("{{" + key + "}}", value)
    return template


def call_model(base_url, api_key, model, system, user, timeout=180):
    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "temperature": 0,
        "stream": False,
    }, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + api_key},
    )
    with urllib.request.urlopen(req, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return payload["choices"][0]["message"]["content"]



HUNK = re.compile(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


def commentable_lines(patch):
    """把 patch 里「可以被评论」的行号算出来（2026-10-29 接回生产闸门）。

    生产的 location guard 只允许评论出现在 diff 覆盖的行上：新增/上下文行按新文件行号，
    删除行按旧文件行号（可评论"这一行被删了"）。脚本里不模拟这层，就会把"模型报了
    patch 之外的行"算成模型的问题——其实那是闸门该拦的。
    """
    allowed = set()
    if not patch:
        return allowed
    for match in HUNK.finditer(patch):
        old_start, old_len = int(match.group(1)), int(match.group(2) or 1)
        new_start, new_len = int(match.group(3)), int(match.group(4) or 1)
        allowed.update(range(new_start, new_start + max(new_len, 1)))
        allowed.update(range(old_start, old_start + max(old_len, 1)))
    return allowed


def parse_issues(text):
    """模型偶尔会包一层 markdown 代码块，这里宽松地取出第一个 JSON 对象"""
    raw = text or ""
    fence = re.search(r"```(?:json)?\s*(.*?)```", raw, re.S)
    if fence:
        raw = fence.group(1)
    start = raw.find("{")
    end = raw.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        data = json.loads(raw[start:end + 1])
    except json.JSONDecodeError:
        return None
    issues = data.get("issues")
    return issues if isinstance(issues, list) else []


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 个用例（0=全部）")
    parser.add_argument("--out", default="", help="把完整结果写到这个 JSON 文件")
    parser.add_argument("--sleep", type=float, default=0.5, help="每次调用间隔，避免被限流")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    env = load_env()
    base_url = env.get("CODEPILOT_LLM_BASE_URL") or os.environ.get("CODEPILOT_LLM_BASE_URL")
    api_key = env.get("CODEPILOT_LLM_API_KEY") or os.environ.get("CODEPILOT_LLM_API_KEY")
    model = env.get("CODEPILOT_LLM_MODEL") or os.environ.get("CODEPILOT_LLM_MODEL")
    if not (base_url and api_key and model):
        print("缺 CODEPILOT_LLM_BASE_URL / CODEPILOT_LLM_API_KEY / CODEPILOT_LLM_MODEL")
        sys.exit(2)

    system_template = open(SYSTEM_PROMPT, encoding="utf-8").read()
    user_template = open(USER_PROMPT, encoding="utf-8").read()
    cases = json.load(open(CASES, encoding="utf-8"))
    if args.limit:
        cases = cases[: args.limit]

    print("模型 %s | 用例 %d 个 | 每个文件一次调用" % (model, len(cases)))
    report = []
    true_positive = 0
    expected_total = 0
    must_not_violations = 0
    clean_violations = 0
    clean_cases = 0
    unlabeled = 0

    for case in cases:
        name = case.get("name")
        files = case.get("files") or []
        changed_text = "\n".join("- " + f.get("filePath", "") for f in files)
        expected = case.get("expectedFindings") or []
        must_not = case.get("mustNotFindings") or []
        tags = case.get("scenarioTags") or []
        is_clean = "clean" in tags or not expected
        if is_clean:
            clean_cases += 1

        collected = []
        parse_failures = 0
        gate_filtered = []
        for file in files:
            user = fill(user_template, {
                "rules": DEFAULT_RULES,
                "allChangedFilesText": changed_text,
                "filePath": file.get("filePath", ""),
                "patch": file.get("patch", ""),
            })
            try:
                answer = call_model(base_url, api_key, model, system_template, user)
            except Exception as error:  # noqa: BLE001
                print("  调用失败 %s/%s: %s" % (name, file.get("filePath"), error))
                parse_failures += 1
                continue
            issues = parse_issues(answer)
            if issues is None:
                parse_failures += 1
                continue
            allowed = commentable_lines(file.get("patch", ""))
            for issue in issues:
                line = issue.get("lineNumber")
                # 闸门：行号必须落在 diff 覆盖范围内，否则生产上会被 location guard 丢掉
                if isinstance(line, int) and allowed and line not in allowed:
                    gate_filtered.append({**issue, "_gate": "line-not-in-patch"})
                    continue
                collected.append(issue)
            time.sleep(args.sleep)

        expected_total += max(1, len(expected))
        hits = 0
        for want in expected:
            issue_type = want.get("issueType")
            path = want.get("filePath")
            found = any(
                (not issue_type or issue.get("issueType") == issue_type)
                and (not path or issue.get("filePath") in (None, "", path))
                for issue in collected
            )
            if found:
                hits += 1
        true_positive += hits

        violations = [issue for issue in collected
                      if any(m.get("issueType") == issue.get("issueType")
                             and (not m.get("titleContains") or m.get("titleContains") in (issue.get("title") or ""))
                             for m in must_not)]
        must_not_violations += len(violations)
        if is_clean and not expected:
            clean_violations += len(collected)

        unlabeled += len(collected) - hits

        report.append({
            "case": name,
            "tags": tags,
            "expected": len(expected),
            "hit": hits,
            "modelIssues": collected,
            "mustNotViolations": violations,
            "parseFailures": parse_failures,
            "gateFiltered": gate_filtered,
        })
        print("  %-42s 期望 %d 命中 %d | 模型报 %2d 条%s"
              % (name, len(expected), hits, len(collected),
                 "  ← 违禁 %d" % len(violations) if violations else ""))

    recall = true_positive / expected_total if expected_total else 0.0
    print("\n== 汇总（真实模型） ==")
    print("召回率 recall = %d/%d = %.1f%%" % (true_positive, expected_total, recall * 100))
    print("must-not 违规 = %d（要求 0）" % must_not_violations)
    print("clean 场景误报 = %d 条（%d 个 clean 用例）" % (clean_violations, clean_cases))
    print("未标注的新发现 = %d 条（需人工复核，不能直接算误报）" % unlabeled)

    if args.out:
        json.dump(report, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        print("报告已写：%s" % args.out)


if __name__ == "__main__":
    main()
