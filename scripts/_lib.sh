#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/deploy/compose.yaml"
ENV_FILE="${PROJECT_ROOT}/.env"
DEV_INFRA_ROOT="${DEV_INFRA_ROOT:-$(cd "${PROJECT_ROOT}/.." && pwd)/dev-infra}"
DEV_INFRA_ENV_FILE="${DEV_INFRA_ENV_FILE:-${DEV_INFRA_ROOT}/.env}"
DEV_INFRA_COMPOSE_FILE="${DEV_INFRA_ROOT}/compose.yaml"
MARKETING_INFRA_COMPOSE_FILE="${DEV_INFRA_ROOT}/marketing/compose.yaml"
MARKETING_INFRA_TOOL="${DEV_INFRA_ROOT}/bin/marketing-infra"

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

PLATFORM_PORTS_LOADER="${PLATFORM_PORTS_LOADER:-${PROJECT_ROOT}/../auth-platform/deploy/load-platform-ports.sh}"
if [[ -r "${PLATFORM_PORTS_LOADER}" ]]; then
  # shellcheck source=/dev/null
  . "${PLATFORM_PORTS_LOADER}"
fi

if [[ -f "${DEV_INFRA_ENV_FILE}" ]]; then
  DEV_INFRA_MYSQL_ROOT_PASSWORD="$(
    set -a
    # shellcheck disable=SC1090
    source "${DEV_INFRA_ENV_FILE}"
    printf '%s' "${MYSQL84_ROOT_PASSWORD:-}"
  )"
  dev_infra_redis_password="$(
    set -a
    # shellcheck disable=SC1090
    source "${DEV_INFRA_ENV_FILE}"
    printf '%s' "${REDIS7_PASSWORD:-}"
  )"
  if [[ -n "${dev_infra_redis_password}" ]]; then
    export DEV_INFRA_REDIS_URL="${DEV_INFRA_REDIS_URL:-redis://:${dev_infra_redis_password}@infra-redis7:6379}"
  fi
  export DEV_INFRA_MYSQL_ROOT_PASSWORD
fi

export GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:${GATEWAY_PORT:-8080}}"
export CONSOLE_URL="${CONSOLE_URL:-http://127.0.0.1:${MARKETING_UI_PORT:-${CONSOLE_PORT:-3000}}}"
export FLINK_URL="${FLINK_URL:-http://127.0.0.1:${FLINK_UI_PORT:-8081}}"
export KEYCLOAK_URL="${KEYCLOAK_URL:-http://127.0.0.1:${KEYCLOAK_PORT:-8180}}"
export PROMETHEUS_URL="${PROMETHEUS_URL:-http://127.0.0.1:${PROMETHEUS_PORT:-9090}}"
export GRAFANA_URL="${GRAFANA_URL:-http://127.0.0.1:${GRAFANA_PORT:-3001}}"

compose() {
  local args=(--env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
  if [[ -n "${PLATFORM_PORTS_FILE:-}" && -r "${PLATFORM_PORTS_FILE}" ]]; then
    args+=(--env-file "${PLATFORM_PORTS_FILE}")
  fi
  docker compose "${args[@]}" "$@"
}

infra_compose() {
  [[ -f "${DEV_INFRA_COMPOSE_FILE}" && -f "${DEV_INFRA_ENV_FILE}" ]] || {
    echo "Missing dev-infra checkout or .env under ${DEV_INFRA_ROOT}" >&2
    return 1
  }
  docker compose --env-file "${DEV_INFRA_ENV_FILE}" -f "${DEV_INFRA_COMPOSE_FILE}" "$@"
}

marketing_infra_compose() {
  [[ -f "${MARKETING_INFRA_COMPOSE_FILE}" && -f "${DEV_INFRA_ENV_FILE}" ]] || {
    echo "Missing Marketing infrastructure under ${DEV_INFRA_ROOT}" >&2
    return 1
  }
  docker compose --env-file "${DEV_INFRA_ENV_FILE}" --env-file "${ENV_FILE}" \
    -f "${MARKETING_INFRA_COMPOSE_FILE}" "$@"
}

marketing_infra() {
  [[ -x "${MARKETING_INFRA_TOOL}" ]] || {
    echo "Missing executable: ${MARKETING_INFRA_TOOL}" >&2
    return 1
  }
  "${MARKETING_INFRA_TOOL}" "$1" "${ENV_FILE}" "${@:2}"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "Required command not found: $1" >&2; return 1; }
}

wait_http() {
  local name="$1" url="$2" attempts="${3:-90}"
  local count=1
  until curl --fail --silent --show-error --max-time 3 "${url}" >/dev/null 2>&1; do
    if (( count >= attempts )); then
      echo "Timed out waiting for ${name}: ${url}" >&2
      return 1
    fi
    sleep 2
    ((count += 1))
  done
  echo "ready: ${name}"
}

dev_headers() {
  printf '%s\n' \
    '-H' 'X-Dev-Tenant-Id: retail-cn' \
    '-H' 'X-Dev-Organization-Ids: retail-business' \
    '-H' 'X-Dev-Shop-Ids: all-shops' \
    '-H' 'X-Dev-Actor-Id: r1-smoke' \
    '-H' 'X-Dev-Permissions: *'
}
