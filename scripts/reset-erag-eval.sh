#!/usr/bin/env bash
# EnterpriseRAG 评测数据空间归零脚本（era 切换用）
#
# 作用：清空三个数据空间，让下一轮 run-erag-round.sh 走全新入库：
#   1. Qdrant 的 erag_eval_chunks_* collection
#   2. PG 的 agentic_rag_eval 库（DROP + CREATE + init.sql）
#   3. （本地数据目录不删——由 run-erag-round.sh 的 RAG_DATA_DIR 决定用哪个）
#
# 何时需要跑：
#   - era-2（修复语料后重跑）：必须先跑本脚本，否则 PG 里的旧桩文档
#     会因幂等跳过而永远不重导（新语料形同虚设）
#   - era-1 数据不会被删除：eval-answers/rounds/ 旧 answers 原地保留，
#     target/erag-real-data-v2 旧本地库原地保留
#
# 保护：
#   - 检测到评测 JVM 仍在连 eval 库时拒绝执行（避免跑一半被清库）
#   - 删除 Qdrant 前二次确认
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PG_CONTAINER="agentic-rag-postgres"
PG_EVAL_DB="agentic_rag_eval"
QDRANT_URL="http://localhost:6333"
QDRANT_COLLECTION="erag_eval_chunks_qwen_qwen3_embedding_8b_4096"

# --- 保护1：eval 库上还有活动连接（评测在跑）则拒绝 ---
ACTIVITY="$(docker exec "$PG_CONTAINER" psql -U postgres -t -A -c \
  "SELECT count(*) FROM pg_stat_activity WHERE datname='$PG_EVAL_DB'")"
if [[ "$ACTIVITY" -gt 1 ]]; then
  echo "错误：$PG_EVAL_DB 上有 $ACTIVITY 个活动连接——评测可能还在运行。" >&2
  echo "       先等当前轮次跑完（或手动停掉），再执行归零。" >&2
  exit 1
fi

# --- 保护2：二次确认 ---
echo "即将清空评测数据空间："
echo "  1. Qdrant: $QDRANT_COLLECTION"
echo "  2. PG: DROP + CREATE $PG_EVAL_DB 并执行 init.sql"
echo "（era-1 的 answers 与 target/erag-real-data-v2 不受影响）"
echo
read -r -p "确认归零？输入 yes 继续: " CONFIRM
[[ "$CONFIRM" == "yes" ]] || { echo "已取消"; exit 0; }

# --- Step 1: 删 Qdrant collection ---
echo "▶ 删除 Qdrant collection..."
HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$QDRANT_URL/collections/$QDRANT_COLLECTION" || true)"
if [[ "$HTTP_CODE" == "200" ]]; then
  echo "  已删除（200）"
elif [[ "$HTTP_CODE" == "404" ]]; then
  echo "  本就不存在（404），跳过"
else
  echo "  ⚠️ Qdrant 返回 $HTTP_CODE（检查 Qdrant 是否在跑：curl $QDRANT_URL/collections）"
fi

# --- Step 2: 重建 PG eval 库 ---
echo "▶ 重建 PG $PG_EVAL_DB..."
docker exec "$PG_CONTAINER" psql -U postgres -c "DROP DATABASE IF EXISTS $PG_EVAL_DB;"
docker exec "$PG_CONTAINER" psql -U postgres -c "CREATE DATABASE $PG_EVAL_DB;"
docker exec -i "$PG_CONTAINER" psql -U postgres -d "$PG_EVAL_DB" -q < src/main/resources/db/init.sql
echo "  完成（documents 表已重建）"

# --- Step 3: 验证 ---
DOCS="$(docker exec "$PG_CONTAINER" psql -U postgres -d "$PG_EVAL_DB" -t -A -c "SELECT count(*) FROM documents")"
COLS="$(curl -s "$QDRANT_URL/collections" | grep -c "$QDRANT_COLLECTION" || true)"
echo
echo "✅ 归零完成：PG documents=$DOCS（应为 0），Qdrant erag collection 数=$COLS（应为 0）"
echo
echo "下一步启动 era-2 首轮（全量入库 + 作答，约 3-5 小时）："
echo "  ROUNDS_DIR=eval-answers/rounds-era2 ./scripts/run-erag-round.sh r01 hybrid"
echo "（RAG_DATA_DIR 默认已是 ./target/erag-real-data-v3）"
