#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_command docker

seed_file="${PROJECT_ROOT}/scripts/seed-data/system-mock.sql"
extra_file="${PROJECT_ROOT}/scripts/seed-data/retail-cn-extra.sql"
# 在第一次写库前检查所有输入，避免补充文件缺失时只灌入一半演示场景。
for file in "${seed_file}" "${extra_file}"; do
  [[ -r "${file}" ]] || { echo "Missing seed file: ${file}" >&2; exit 1; }
done
for assignment in "SET @tenant = 'marketing-platform';" "SET @organization = 'marketing-platform';"; do
  [[ "$(grep -Fxc "${assignment}" "${seed_file}")" == "1" ]] || {
    echo "Unexpected tenant template in ${seed_file}" >&2
    exit 1
  }
done

# 控制台 DEV 默认货主是 retail-cn / retail-business；只替换明确的变量赋值。
retail_seed="$(mktemp)"
trap 'rm -f "${retail_seed}"' EXIT
sed -e "s/^SET @tenant = 'marketing-platform';$/SET @tenant = 'retail-cn';/" \
    -e "s/^SET @organization = 'marketing-platform';$/SET @organization = 'retail-business';/" \
    "${seed_file}" > "${retail_seed}"

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

apply_seed() {
  local file="$1"
  docker exec --interactive --env MYSQL_PWD="${DEV_INFRA_MYSQL_ROOT_PASSWORD:-}" "${mysql_container}" \
    mysql --batch --raw --default-character-set=utf8mb4 --host=127.0.0.1 --user=root < "${file}"
}

echo "seeding system mock data into local MySQL..."
apply_seed "${seed_file}"
echo "system mock data ready (tenant=marketing-platform, campaign=mock-cmp-ha-2026)"

echo "seeding closed-loop mock onto tenant=retail-cn..."
apply_seed "${retail_seed}"
echo "closed-loop mock ready (tenant=retail-cn, organization=retail-business)"

echo "seeding extra retail-cn coverage..."
apply_seed "${extra_file}"
echo "retail-cn extra mock ready"
