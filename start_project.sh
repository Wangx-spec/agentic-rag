#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$ROOT_DIR/.env"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

if [ -z "${LLM_API_KEY:-}" ]; then
  echo "缺少环境变量 LLM_API_KEY，请先在 .env 或当前 shell 中配置。"
  exit 1
fi

exec "$ROOT_DIR/mvnw" spring-boot:run "$@"
