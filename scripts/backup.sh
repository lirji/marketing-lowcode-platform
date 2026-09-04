#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="${PROJECT_ROOT}/backups/${timestamp}"
mkdir -p "${backup_dir}/clickhouse"

infra_compose exec -T mysql84 mysqldump -uroot -p"${DEV_INFRA_MYSQL_ROOT_PASSWORD:-}" \
  --single-transaction --routines --events --triggers --set-gtid-purged=OFF \
  --databases marketing_control marketing_compiler marketing_audience marketing_decision \
  marketing_benefit marketing_events marketing_journey marketing_engagement \
  marketing_measurement marketing_keycloak > "${backup_dir}/mysql.sql"

for table in marketing_facts campaign_daily decision_traces; do
  marketing_infra_compose exec -T marketing-clickhouse clickhouse-client --user marketing --password "${MARKETING_DB_PASSWORD:-}" \
    --query "SELECT * FROM marketing.${table} FORMAT Native" > "${backup_dir}/clickhouse/${table}.native"
done

infra_compose exec -T kafka38 /opt/kafka/bin/kafka-topics.sh --bootstrap-server infra-kafka38:9092 --describe > "${backup_dir}/kafka-topics.txt"
(
  cd "${backup_dir}"
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 shasum -a 256 > SHA256SUMS
)
echo "backup created: ${backup_dir}"
echo "Object storage replication and managed-database PITR remain infrastructure responsibilities in production."
