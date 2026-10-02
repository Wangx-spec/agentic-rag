#!/usr/bin/env bash
# S3.1 电商数据分析 demo 库灌库脚本（幂等，可重复执行）
#
# 职责：建库 agentic_rag_demo → 建只读账号 demo_readonly → 导 schema → 导 CSV
# 幂等策略：schema.sql 内置 DROP ... CASCADE + CREATE，CSV 导入前 TRUNCATE，重跑不产生重复数据
# 主库 agentic_rag 与此脚本零接触

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_CONTAINER="${PG_CONTAINER:-agentic-rag-postgres}"
DB_NAME="agentic_rag_demo"
DB_ADMIN="postgres"
RO_USER="demo_readonly"
RO_PASSWORD="${DEMO_READONLY_PASSWORD:-demo_readonly}"
# 需物理隔离的库（demo_readonly 不得连接）：主库 + 评测库
PROTECTED_DBS=(agentic_rag agentic_rag_eval)
SCHEMA_FILE="$ROOT_DIR/scripts/demo-data/schema.sql"
DATA_DIR="$ROOT_DIR/data/demo-data"
CONTAINER_DATA_DIR="/tmp/demo-data"

# 灌库顺序：先主数据（users/products）与字典表，再业务明细（orders/order_items/user_actions），最后独立评论表
TABLES=(
  platforms order_statuses payment_methods shipping_methods
  users products
  orders order_items user_actions
  comments
)

psql_admin() {
  docker exec -i "$PG_CONTAINER" psql -U "$DB_ADMIN" -v ON_ERROR_STOP=1 "$@"
}

echo "=== [1/4] 建库（幂等）==="
if psql_admin -tc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -q 1; then
  echo "  数据库 ${DB_NAME} 已存在，跳过创建"
else
  psql_admin -c "CREATE DATABASE ${DB_NAME}"
  echo "  数据库 ${DB_NAME} 已创建"
fi

echo "=== [2/4] 只读账号 ${RO_USER}（幂等）==="
psql_admin <<EOF
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${RO_USER}') THEN
    CREATE ROLE ${RO_USER} LOGIN PASSWORD '${RO_PASSWORD}';
  END IF;
END
\$\$;
EOF
psql_admin -d "$DB_NAME" <<EOF
REVOKE ALL ON SCHEMA public FROM ${RO_USER};
GRANT CONNECT ON DATABASE ${DB_NAME} TO ${RO_USER};
GRANT USAGE ON SCHEMA public TO ${RO_USER};
GRANT SELECT ON ALL TABLES IN SCHEMA public TO ${RO_USER};
ALTER DEFAULT PRIVILEGES FOR ROLE ${DB_ADMIN} IN SCHEMA public GRANT SELECT ON TABLES TO ${RO_USER};
EOF
# 物理隔离：撤销 PUBLIC 对主库/评测库的 CONNECT（否则 demo_readonly 经 PUBLIC 继承可连主库看表结构）
echo "  物理隔离：撤销 PUBLIC 对主库/评测库的 CONNECT（${RO_USER} 无法连主库，postgres 超级用户不受影响）"
for db in "${PROTECTED_DBS[@]}"; do
  psql_admin -c "REVOKE CONNECT ON DATABASE ${db} FROM PUBLIC;"
done
echo "  只读账号已就绪（仅能连 ${DB_NAME}，主库/评测库已物理隔离）"

echo "=== [3/4] 导 schema（${SCHEMA_FILE}）==="
psql_admin -d "$DB_NAME" < "$SCHEMA_FILE"
echo "  schema 导入完成（DROP CASCADE + CREATE，幂等）"

echo "=== [4/4] 导 CSV（${DATA_DIR} → ${PG_CONTAINER}:${CONTAINER_DATA_DIR}）==="
docker exec "$PG_CONTAINER" rm -rf "$CONTAINER_DATA_DIR"
docker cp "$DATA_DIR/." "$PG_CONTAINER:$CONTAINER_DATA_DIR/"
for table in "${TABLES[@]}"; do
  echo "  导入 ${table}.csv ..."
  psql_admin -d "$DB_NAME" -c "TRUNCATE TABLE ${table} RESTART IDENTITY CASCADE;"
  if [ "$table" = "comments" ]; then
    # comments 表含 serial 自增主键 id（4 列），CSV 仅 3 列（cat/label/review），须显式指定列，否则数据错位
    psql_admin -d "$DB_NAME" -c "\\copy comments (cat, label, review) FROM '${CONTAINER_DATA_DIR}/comments.csv' CSV HEADER"
  else
    psql_admin -d "$DB_NAME" -c "\\copy ${table} FROM '${CONTAINER_DATA_DIR}/${table}.csv' CSV HEADER"
  fi
done
# 只读授权刷新（schema 重建后新表的 SELECT 需要重新授予）
psql_admin -d "$DB_NAME" -c "GRANT SELECT ON ALL TABLES IN SCHEMA public TO ${RO_USER};"

echo ""
echo "灌库完成。行数核验："
for table in "${TABLES[@]}"; do
  count=$(psql_admin -d "$DB_NAME" -tAc "SELECT count(*) FROM ${table}")
  printf "  %-18s %s 行\n" "$table" "$count"
done
docker exec "$PG_CONTAINER" rm -rf "$CONTAINER_DATA_DIR"