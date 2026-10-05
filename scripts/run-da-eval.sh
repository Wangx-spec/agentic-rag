#!/usr/bin/env bash
# S3.3 数据分析评测跑集脚本。
set -euo pipefail

[[ $# -ge 1 ]] || { echo "用法: $0 <run-id 如 da-r1> [limit]" >&2; exit 1; }
RUN_ID="$1"
LIMIT="${2:-0}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -f .env && -z "${LLM_API_KEY:-}" ]]; then
  LLM_API_KEY="$(grep -E '^LLM_API_KEY=' .env | head -1 | cut -d= -f2- | tr -d '\r' \
    | sed -e 's/^\"//' -e 's/\"$//' -e "s/^'//" -e "s/'$//")"
  export LLM_API_KEY
fi

export EVAL_BENCH=dataanalysis
export EVAL_TRACE_ENABLED=true
export EVAL_DA_ACTION=run
export EVAL_DA_RUN_ID="$RUN_ID"
export EVAL_DA_LIMIT="$LIMIT"
export EVAL_DA_ROUNDS_DIR="${ROUNDS_DIR:-eval-answers/rounds-da}"
export RAG_MEMORY_TYPE="${RAG_MEMORY_TYPE:-memory}"

mkdir -p "$EVAL_DA_ROUNDS_DIR"

echo "▶ 数据分析评测跑集 run_id=$RUN_ID limit=$LIMIT"
echo "▶ answers → $EVAL_DA_ROUNDS_DIR/$RUN_ID.jsonl"

exec ./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=eval \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none"
