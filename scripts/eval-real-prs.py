#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在**真实提交**上跑审查（2026-10-29）

前面的 `eval-real-llm.py` 用的是手写合成 diff；这个脚本换成本仓库真实历史里的提交，
回答一个合成用例回答不了的问题：**在真实代码上，模型报出来的东西有多少是真问题？**

做法：
  1. `git show` 取最近 N 个提交的 diff；
  2. 按文件切分，只保留 `.java`；
  3. 用项目自己的 prompt 逐文件调真实模型；
  4. 把结果落成 JSON，由人来判定每条是真问题还是误报（**脚本不做这个判断**）。

用法：

    python scripts/eval-real-prs.py --commits 8 --out real-prs.json

判读口径（重要）：这些提交是**已经合并的历史**，不等于"代码完美"——里面可能真有问题，
也可能只是风格差异。所以结论只能写"人工复核后 X/Y 条成立"，不能写"准确率 = X%"。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
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


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True,
                          encoding="utf-8", errors="ignore").stdout


def split_patch(diff_text):
    """把 `git show` 的输出按文件切开，返回 [(filePath, patch), ...]"""
    files = []
    current_path = None
    current_lines = []
    for line in diff_text.splitlines():
        if line.startswith("diff --git "):
            if current_path and current_lines:
                files.append((current_path, "\n".join(current_lines)))
            match = re.match(r"diff --git a/(.*?) b/(.*)", line)
            current_path = match.group(2) if match else None
            current_lines = []
        elif current_path is not None:
            current_lines.append(line)
    if current_path and current_lines:
        files.append((current_path, "\n".join(current_lines)))
    return [(path, patch) for path, patch in files if path.endswith(".java")]


def fill(template, values):
    for key, value in values.items():
        template = template.replace("{{" + key + "}}", value)
    return template


def call_model(base_url, api_key, model, system, user, timeout=180):
    body = json.dumps({
        "model": model,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": 0,
        "stream": False,
    }, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(base_url.rstrip("/") + "/chat/completions", data=body,
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Bearer " + api_key})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return payload["choices"][0]["message"]["content"]


def parse_issues(text):
    raw = text or ""
    fence = re.search(r"```(?:json)?\s*(.*?)```", raw, re.S)
    if fence:
        raw = fence.group(1)
    start, end = raw.find("{"), raw.rfind("}")
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
    parser.add_argument("--commits", type=int, default=8)
    parser.add_argument("--limit-files", type=int, default=3, help="每个提交最多取几个文件")
    parser.add_argument("--min-lines", type=int, default=4, help="diff 少于这么多行就跳过")
    parser.add_argument("--out", default="real-prs.json")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    env = load_env()
    base_url = env.get("CODEPILOT_LLM_BASE_URL")
    api_key = env.get("CODEPILOT_LLM_API_KEY")
    model = env.get("CODEPILOT_LLM_MODEL")
    if not (base_url and api_key and model):
        print("缺 LLM 配置")
        sys.exit(2)

    system_template = open(SYSTEM_PROMPT, encoding="utf-8").read()
    user_template = open(USER_PROMPT, encoding="utf-8").read()

    commits = [line.split("\t") for line in git("log", "--format=%H\t%h\t%s", "-%d" % args.commits).splitlines() if line]
    report = []
    total_issues = 0
    total_files = 0

    for full_sha, short_sha, subject in commits:
        diff_text = git("show", "--format=", "--unified=3", full_sha)
        files = [item for item in split_patch(diff_text)
                 if len(item[1].splitlines()) >= args.min_lines][: args.limit_files]
        if not files:
            continue
        changed_text = "\n".join("- " + path for path, _ in files)
        entry = {"commit": short_sha, "subject": subject, "files": []}
        for path, patch in files:
            user = fill(user_template, {"rules": DEFAULT_RULES, "allChangedFilesText": changed_text,
                                        "filePath": path, "patch": patch})
            try:
                answer = call_model(base_url, api_key, model, system_template, user)
            except Exception as error:  # noqa: BLE001
                entry["files"].append({"file": path, "error": str(error), "issues": []})
                continue
            issues = parse_issues(answer) or []
            total_files += 1
            total_issues += len(issues)
            entry["files"].append({"file": path, "issues": issues})
            print("  %s %-55s 报 %d 条" % (short_sha, path[-55:], len(issues)))
            time.sleep(0.4)
        report.append(entry)

    json.dump(report, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("\n== 真实提交审查 ==")
    print("提交 %d 个 | 文件 %d 个 | 模型共报 %d 条" % (len(report), total_files, total_issues))
    print("报告：%s" % args.out)
    print("下一步：逐条人工判定真/假，再算「成立的条数 / 总条数」——脚本不替你判。")


if __name__ == "__main__":
    main()
