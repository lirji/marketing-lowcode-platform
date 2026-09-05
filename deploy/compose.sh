#!/usr/bin/env bash
# Marketing Compose 统一入口：加载项目私有配置，并兼容 auth-platform 中央门户端口。
# 加 --secure 叠加 compose.secure.yml（Casdoor JWT + 控制台 OIDC）。

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PLATFORM_PORTS_LOADER="${PLATFORM_PORTS_LOADER:-${SCRIPT_DIR}/../../auth-platform/deploy/load-platform-ports.sh}"
ENV_ARGS=()
if [[ -r "${PROJECT_ROOT}/.env" ]]; then
  ENV_ARGS+=(--env-file "${PROJECT_ROOT}/.env")
fi
if [[ -r "${PLATFORM_PORTS_LOADER}" ]]; then
  # shellcheck source=/dev/null
  . "${PLATFORM_PORTS_LOADER}"
  ENV_ARGS+=(--env-file "${PLATFORM_PORTS_FILE}")
fi
export MARKETING_UI_PORT="${MARKETING_UI_PORT:-${CONSOLE_PORT:-3000}}"
DEV_INFRA_ENV="${DEV_INFRA_ENV_FILE:-${PROJECT_ROOT}/../dev-infra/.env}"
if [[ -z "${DEV_INFRA_REDIS_URL:-}" && -r "${DEV_INFRA_ENV}" ]]; then
  DEV_INFRA_REDIS_URL="redis://:$(
    set -a
    # shellcheck disable=SC1090
    source "${DEV_INFRA_ENV}"
    printf '%s' "${REDIS7_PASSWORD:-}"
  )@infra-redis7:6379"
  export DEV_INFRA_REDIS_URL
fi
cd "${SCRIPT_DIR}"

SECURE=0
COMPOSE_ARGS=()
for arg in "$@"; do
  if [[ "${arg}" == "--secure" ]]; then
    SECURE=1
  else
    COMPOSE_ARGS+=("${arg}")
  fi
done
if [[ "${SECURE}" -eq 1 ]]; then
  exec docker compose "${ENV_ARGS[@]}" -f compose.yaml -f compose.secure.yml "${COMPOSE_ARGS[@]}"
fi
exec docker compose "${ENV_ARGS[@]}" -f compose.yaml "${COMPOSE_ARGS[@]}"
