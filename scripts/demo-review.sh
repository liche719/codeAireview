#!/usr/bin/env bash
# 一键演示：触发一次真实 PR 审查，并打印结论 / 门禁 / 审计。
#
# 用法：
#   BASE_URL=https://你的域名 API_KEY=xxx ./scripts/demo-review.sh https://github.com/owner/repo/pull/1
#
# 依赖：curl、python3（只用来格式化 JSON）

set -euo pipefail

PR_URL="${1:-}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8990}"
API_KEY="${API_KEY:-}"
# 自签/内网 IP 访问时可传 CURL_OPTS="-k"
CURL_OPTS="${CURL_OPTS:-}"

if [ -z "$PR_URL" ]; then
  echo "用法：BASE_URL=... API_KEY=... $0 <PR 链接>" >&2
  exit 1
fi

api() {
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -sS $CURL_OPTS -X "$method" "$BASE_URL$path" \
      -H "X-CodePilot-Api-Key: $API_KEY" -H "Content-Type: application/json" -d "$body"
  else
    curl -sS $CURL_OPTS -X "$method" "$BASE_URL$path" -H "X-CodePilot-Api-Key: $API_KEY"
  fi
}

echo "== 1/4 服务健康"
api GET /api/stats/review?days=1 | python3 -c '
import json,sys
d=json.load(sys.stdin)["data"]
tasks=d.get("tasks") or {}
print("  任务=%s 成功率=%s SLO=%s" % (tasks.get("total"), tasks.get("successRate"), (d.get("slo") or {}).get("breach")))
'

echo "== 2/4 触发审查"
TASK_ID=$(api POST /api/reviews "{\"prUrl\":\"$PR_URL\",\"commentMode\":\"BOTH\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["taskId"])')
echo "  taskId=$TASK_ID"

echo "== 3/4 等结果（最多 180 秒）"
for _ in $(seq 1 60); do
  STATUS=$(api GET "/api/reviews/$TASK_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"].get("status",""))')
  case "$STATUS" in
    SUCCESS|FAILED) break ;;
  esac
  sleep 3
done
api GET "/api/reviews/$TASK_ID" | python3 -c '
import json,sys
d=json.load(sys.stdin)["data"]
print("  状态=%s 文件=%s 问题=%s 风险=%s" % (d.get("status"), d.get("totalFiles"), d.get("totalIssues"), d.get("riskLevel")))
if d.get("errorMessage"):
    print("  失败原因:", str(d.get("errorMessage"))[:80])
'

echo "== 4/4 审计与成本"
api GET /api/audit | python3 -c '
import json,sys
rows=json.load(sys.stdin).get("data") or []
for row in rows[:3]:
    print("  ", row.get("action"), "|", str(row.get("actor"))[:20], "|", str(row.get("detail"))[:60])
'
api GET /api/stats/cost?days=1 | python3 -c '
import json,sys
d=json.load(sys.stdin).get("data") or {}
days=d.get("byDay") or []
if days:
    today=days[-1]
    print("  今日调用=%s 成本=%s" % (today.get("calls"), today.get("cost")))
else:
    print("  今日无调用记录")
if d.get("note"):
    print("  说明:", str(d.get("note"))[:80])
'

echo
echo "完成。上面四步就是面试时的 3 分钟演示路径：健康 → 触发 → 结论 → 审计。"
