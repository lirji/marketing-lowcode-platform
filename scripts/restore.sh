#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

backup_name="${1:-}"
[[ "${backup_name}" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || { echo "Usage: $0 YYYYMMDDTHHMMSSZ" >&2; exit 2; }
backup_dir="${PROJECT_ROOT}/backups/${backup_name}"
[[ -f "${backup_dir}/mysql.sql" && -f "${backup_dir}/SHA256SUMS" ]] || { echo "Incomplete backup: ${backup_dir}" >&2; exit 2; }
if [[ "${CONFIRM_RESTORE:-}" != "${backup_name}" ]]; then
  echo "Restore overwrites local database contents. Set CONFIRM_RESTORE=${backup_name} to proceed." >&2
  exit 2
fi

(cd "${backup_dir}" && shasum -a 256 -c SHA256SUMS)
compose stop edge-gateway marketing-control-service rule-compiler-worker audience-service offer-decision-service benefit-funding-service event-gateway-service journey-service engagement-service measurement-service
trap 'compose start marketing-control-service rule-compiler-worker audience-service offer-decision-service benefit-funding-service event-gateway-service journey-service engagement-service measurement-service edge-gateway >/dev/null 2>&1 || true' EXIT

infra_compose exec -T mysql84 mysql -uroot -p"${DEV_INFRA_MYSQL_ROOT_PASSWORD:-}" < "${backup_dir}/mysql.sql"
for table in marketing_facts campaign_daily decision_traces; do
  marketing_infra_compose exec -T marketing-clickhouse clickhouse-client --user marketing --password "${MARKETING_DB_PASSWORD:-}" \
    --query "TRUNCATE TABLE marketing.${table}"
  marketing_infra_compose exec -T marketing-clickhouse clickhouse-client --user marketing --password "${MARKETING_DB_PASSWORD:-}" \
    --query "INSERT INTO marketing.${table} FORMAT Native" < "${backup_dir}/clickhouse/${table}.native"
done

compose start marketing-control-service rule-compiler-worker audience-service offer-decision-service benefit-funding-service event-gateway-service journey-service engagement-service measurement-service edge-gateway
trap - EXIT
"${PROJECT_ROOT}/scripts/smoke.sh" --wait
echo "restore verified: ${backup_name}"
