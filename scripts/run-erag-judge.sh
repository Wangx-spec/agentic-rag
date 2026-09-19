#!/usr/bin/env bash
# EnterpriseRAG 官方判分启动器（M6 benchmark-optimization）
# 与 run-erag-round.sh 配套：作答轮次跑完后，用本脚本对该轮 answers 判分。
#
# 用法：
#   ./scripts/run-erag-judge.sh r01                # 判分 r01 轮（并发 4）
#   ./scripts/run-erag-judge.sh r01 8              # 判分 r01 轮，并发 8
#   ./scripts/run-erag-judge.sh r01 4 qst_0011     # 只判单题（调试用）
#
# 输入：$ROUNDS_DIR/<round-id>.jsonl（run-erag-round.sh 产出，默认 eval-answers/rounds）
# 输出：EnterpriseRAG-Bench-main/answer_evaluation/results-erag[-<era>]-<round-id>.json
#       （ROUNDS_DIR 目录名不是 rounds 时，自动在结果名中带时代后缀，
#        如 rounds-era2 → results-erag-era2-r01.json，与 era-1 结果互不覆盖）
# 断点续判：results 文件已存在时自动追加 --resume（跳过已判题目，中断重跑不浪费）
#
# 说明：判分项目与本项目是两套独立配置。.env 只提供 LLM_API_KEY；
#       LLM_PROVIDER 等变量是判分项目（src/llm/factory.py）自定义的，
#       其中 LLM_PROVIDER 必须显式设为 openai_compatible（默认 openai 会走
#       官方 Responses API，硅基流动不支持）。其余三个与
#       openai_compatible_llm.py 内置默认值一致，显式写出防漂移。
set -euo pipefail

[[ $# -ge 1 ]] || { echo "用法: $0 <round-id 如 r01> [parallelism] [question-id]" >&2; exit 1; }
ROUND_ID="$1"
PARALLELISM="${2:-4}"
QUESTION_ID="${3:-}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROUNDS_DIR="${ROUNDS_DIR:-eval-answers/rounds}"
case "$ROUNDS_DIR" in
  /*) ANSWERS="$ROUNDS_DIR/$ROUND_ID.jsonl" ;;
  *)  ANSWERS="$ROOT/$ROUNDS_DIR/$ROUND_ID.jsonl" ;;
esac
BENCH_DIR="$ROOT/../EnterpriseRAG-Bench-main"
ROUNDS_BASE="$(basename "$ROUNDS_DIR")"
if [[ "$ROUNDS_BASE" == "rounds" ]]; then
  RESULTS="$BENCH_DIR/answer_evaluation/results-erag-$ROUND_ID.json"
else
  RESULTS="$BENCH_DIR/answer_evaluation/results-erag-$ROUNDS_BASE-$ROUND_ID.json"
fi
PY="$BENCH_DIR/.venv/bin/python"

# --- 前置校验 ---
if [[ ! -f "$ANSWERS" ]]; then
  echo "错误：未找到 $ANSWERS" >&2
  echo "当前 $ROUNDS_DIR 下已有轮次：" >&2
  ls "$ROOT/$ROUNDS_DIR/" 2>/dev/null | sed 's/^/  /' >&2 || true
  exit 1
fi
if [[ ! -d "$BENCH_DIR" ]]; then
  echo "错误：未找到判分项目 $BENCH_DIR" >&2
  exit 1
fi
if [[ ! -x "$PY" ]]; then
  echo "错误：未找到 $PY（需先在判分项目目录建好 venv）" >&2
  exit 1
fi

# --- 从 .env 提取 key（与 run-erag-round.sh 同一套逻辑） ---
if [[ -z "${LLM_API_KEY:-}" ]]; then
  LLM_API_KEY="$(grep -E '^LLM_API_KEY=' "$ROOT/.env" | head -1 | cut -d= -f2- | tr -d '\r' \
    | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")"
fi
if [[ -z "$LLM_API_KEY" ]]; then
  echo "错误：$ROOT/.env 中未找到 LLM_API_KEY" >&2
  exit 1
fi
export LLM_API_KEY

# --- 判分项目环境变量 ---
export LLM_PROVIDER=openai_compatible
export LLM_BASE_URL="https://api.siliconflow.cn/v1"
export LLM_MODEL_NAME="zai-org/GLM-5.3"
export CHEAP_LLM_MODEL_NAME="zai-org/GLM-5.3"

# --- 断点续判与单题模式 ---
RESUME_ARGS=""
if [[ -f "$RESULTS" ]]; then
  RESUME_ARGS="--resume"
  echo "▶ 检测到已有 results，自动 --resume（跳过已判题目）"
fi
QID_ARGS=""
if [[ -n "$QUESTION_ID" ]]; then
  QID_ARGS="--question-id $QUESTION_ID"
fi

echo "▶ 判分轮次 $ROUND_ID | answers=$ROUNDS_DIR/$ROUND_ID.jsonl | 并发=$PARALLELISM${QUESTION_ID:+ | 单题=$QUESTION_ID}"
echo "▶ results → $RESULTS"
echo "▶ 完成后看四指标：Document Recall / Completeness / Correctness / Invalid Extra"
echo

cd "$BENCH_DIR"
exec "$PY" -m src.scripts.answer_evaluation.metrics_based_eval \
  --answers-file "$ANSWERS" \
  --questions-file questions.jsonl \
  --results-file "$RESULTS" \
  --no-correction --parallelism "$PARALLELISM" $RESUME_ARGS $QID_ARGS
