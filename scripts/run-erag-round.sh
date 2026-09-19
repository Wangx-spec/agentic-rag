#!/usr/bin/env bash
# EnterpriseRAG 真实栈评测轮次启动器（M6 benchmark-optimization）
# 用一个脚本收敛所有环境变量，避免多行命令复制粘贴断行。
#
# 用法：
#   ./scripts/run-erag-round.sh r01 hybrid                 # 首轮（含全量入库，3-5h，主要耗在 embedding）
#   ./scripts/run-erag-round.sh r02 vector                 # 通道消融：纯向量
#   ./scripts/run-erag-round.sh r03 bm25                   # 通道消融：纯 BM25
#   ./scripts/run-erag-round.sh r04 hybrid 1 0             # +Query 改写
#   ./scripts/run-erag-round.sh r05 hybrid 0 1             # +HyDE
#   ./scripts/run-erag-round.sh r06 hybrid 1 1             # 全开
#
# 参数：ROUND_ID   轮次 id（answers 落到 $ROUNDS_DIR/<id>.jsonl）
#       MODE       hybrid | vector | bm25
#       REWRITER   0|1（默认 0）
#       HYDE       0|1（默认 0）
#
# 环境变量覆盖：
#       RAG_DATA_DIR   本地数据目录（默认 ./target/erag-real-data-v3，era-2 修复语料）
#       ROUNDS_DIR     answers 输出目录（默认 eval-answers/rounds）
#                      era-2 用法：ROUNDS_DIR=eval-answers/rounds-era2 ./scripts/run-erag-round.sh r01 hybrid
#                      （era-1 旧文件原地保留于 eval-answers/rounds，勿混写）
#
# ⚠️ era-2 前置条件：换数据目录前必须先跑 scripts/reset-erag-eval.sh
#    （清 Qdrant collection + 重建 PG eval 库），否则 PG 里的旧桩文档会因
#    幂等跳过而永远不重导，新语料形同虚设。
#
# 启动成功标志（JVM 日志里必须出现，否则说明环境没进来）：
#   "EnterpriseRAG-Bench 评测启动：bench=enterpriserag"
#   "开始引导 EnterpriseRAG 语料，待导入 N 篇"（首轮；已有语料则显示"跳过引导"）
# 完成标志："EnterpriseRAG 评测完成，answers=..."
set -euo pipefail

[[ $# -ge 1 ]] || { echo "用法: $0 <round-id 如 r01> <hybrid|vector|bm25> [rewriter 0|1] [hyde 0|1]" >&2; exit 1; }
ROUND_ID="$1"
MODE="${2:-hybrid}"
REWRITER="${3:-0}"
HYDE="${4:-0}"
case "$MODE" in
  hybrid|vector|bm25) ;;
  *) echo "错误 MODE 必须是 hybrid vector bm25 之一 收到 $MODE" >&2; exit 1 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "错误：未找到 $ROOT/.env（需要其中的 LLM_API_KEY）" >&2
  exit 1
fi
if [[ -z "${LLM_API_KEY:-}" ]]; then
  LLM_API_KEY="$(grep -E '^LLM_API_KEY=' .env | head -1 | cut -d= -f2- | tr -d '\r' \
    | sed -e 's/^\"//' -e 's/\"$//' -e "s/^'//" -e "s/'$//")"
fi
if [[ -z "$LLM_API_KEY" ]]; then
  echo "错误：.env 中未找到 LLM_API_KEY（且环境中也没有）" >&2
  exit 1
fi
export LLM_API_KEY

mkdir -p "${ROUNDS_DIR:-eval-answers/rounds}"

export EVAL_BENCH=enterpriserag
export LLM_BASE_URL="https://api.siliconflow.cn/v1"
export LLM_CHAT_MODEL="zai-org/GLM-5.3"
export RAG_EMBEDDING_MODEL="Qwen/Qwen3-Embedding-8B"
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/agentic_rag_eval"
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=postgres
export RAG_DATA_DIR="${RAG_DATA_DIR:-./target/erag-real-data-v3}"
export RAG_VECTOR_QDRANT_COLLECTION_PREFIX="erag_eval_chunks"
export RAG_MEMORY_TYPE=memory
export RAG_RETRIEVAL_MODE="$MODE"
export RAG_RETRIEVAL_REWRITER_ENABLED="$REWRITER"
export RAG_RETRIEVAL_HYDE_ENABLED="$HYDE"
export LLM_TIMEOUT_SECONDS="${LLM_TIMEOUT_SECONDS:-120}"

ROUNDS_DIR="${ROUNDS_DIR:-eval-answers/rounds}"

echo "▶ 轮次 $ROUND_ID | mode=$MODE | rewriter=$REWRITER | hyde=$HYDE"
echo "▶ data-dir=$RAG_DATA_DIR | answers → $ROUNDS_DIR/$ROUND_ID.jsonl"
echo "▶ 启动成功标志：JVM 日志出现 \"EnterpriseRAG-Bench 评测启动：bench=enterpriserag\""
echo

exec ./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none --eval.enterprise-rag.answers-path=$ROUNDS_DIR/$ROUND_ID.jsonl"
