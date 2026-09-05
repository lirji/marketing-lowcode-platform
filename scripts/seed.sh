#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_command docker

seed_file="${PROJECT_ROOT}/scripts/seed-data/system-mock.sql"
[[ -r "${seed_file}" ]] || { echo "Missing seed file: ${seed_file}" >&2; exit 1; }

mysql_container="$(COMPOSE_PROJECT_NAME=dev-infra infra_compose ps -q mysql84)"
if [[ -z "${mysql_container}" ]]; then
  mysql_container="$(docker ps --quiet \
    --filter 'label=com.docker.compose.project=dev-infra' \
    --filter 'label=com.docker.compose.service=mysql84' | head -n 1)"
fi
[[ -n "${mysql_container}" ]] || {
  echo "Local mysql84 is not running. Start dev-infra before seeding." >&2
  exit 1
}

if [[ "$(docker inspect --format '{{.State.Running}}' "${mysql_container}")" != "true" ]]; then
  echo "Local mysql84 container is not running: ${mysql_container}" >&2
  exit 1
fi

echo "seeding system mock data into local MySQL..."
docker exec --interactive --env MYSQL_PWD="${DEV_INFRA_MYSQL_ROOT_PASSWORD:-}" "${mysql_container}" \
  mysql --batch --raw --default-character-set=utf8mb4 --host=127.0.0.1 --user=root < "${seed_file}"
echo "system mock data ready (tenant=marketing-platform, campaign=mock-cmp-ha-2026)"
