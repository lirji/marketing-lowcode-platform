#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"
cd "${PROJECT_ROOT}"

mode="${1:---full}"
case "${mode}" in --fast|--full) ;; *) echo "Usage: $0 [--fast|--full]" >&2; exit 2 ;; esac

require_command java
require_command pnpm
require_command python3
require_command docker

./scripts/verify-contracts.sh
if [[ "${mode}" == "--full" ]]; then
  ./mvnw --batch-mode --no-transfer-progress clean verify
  ./scripts/verify-mysql-migrations.sh
else
  ./mvnw --batch-mode --no-transfer-progress test
fi

pnpm install --frozen-lockfile
pnpm frontend:lint
pnpm frontend:test
pnpm frontend:build
if [[ "${mode}" == "--full" ]]; then
  pnpm frontend:e2e
fi

docker compose --env-file .env.example -f deploy/compose.yaml config --quiet
[[ -f "${MARKETING_INFRA_COMPOSE_FILE}" ]] || {
  echo "Missing dev-infra Marketing definition under ${DEV_INFRA_ROOT}" >&2
  exit 1
}
docker compose --env-file "${DEV_INFRA_ROOT}/.env.example" --env-file .env.example \
  -f "${MARKETING_INFRA_COMPOSE_FILE}" config --quiet
python3 -m json.tool "${DEV_INFRA_ROOT}/marketing/keycloak/marketing-realm.json" >/dev/null
python3 -m json.tool "${DEV_INFRA_ROOT}/marketing/grafana/dashboards/marketing-platform.json" >/dev/null

while IFS= read -r script; do bash -n "${script}"; done < <(find scripts -type f -name '*.sh' -print | sort)
bash -n "${MARKETING_INFRA_TOOL}"
if command -v shellcheck >/dev/null 2>&1; then shellcheck scripts/*.sh; fi
if command -v helm >/dev/null 2>&1 && [[ -d deploy/helm/marketing-platform ]]; then
  ./scripts/verify-deployment.sh
fi

echo "verification passed (${mode})"
