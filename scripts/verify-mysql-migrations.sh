#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"
cd "${PROJECT_ROOT}"

require_command docker
[[ -f "${DEV_INFRA_ROOT}/marketing/mysql/01-databases.sh" ]] || {
  echo "Missing dev-infra MySQL initializer under ${DEV_INFRA_ROOT}" >&2
  exit 1
}

container_name="marketing-r1-mysql-migrations-$$"
root_password="MigrationGateRoot_2026"
control_password="Gate_Control_App_2026"
control_migration_password="Gate_Control_Migration_2026"
compiler_password="Gate_Compiler_App_2026"
compiler_migration_password="Gate_Compiler_Migration_2026"
audience_password="Gate_Audience_App_2026"
audience_migration_password="Gate_Audience_Migration_2026"
decision_password="Gate_Decision_App_2026"
decision_migration_password="Gate_Decision_Migration_2026"
benefit_password="Gate_Benefit_App_2026"
benefit_migration_password="Gate_Benefit_Migration_2026"
events_password="Gate_Events_App_2026"
events_migration_password="Gate_Events_Migration_2026"
journey_password="Gate_Journey_App_2026"
journey_migration_password="Gate_Journey_Migration_2026"
engagement_password="Gate_Engagement_App_2026"
engagement_migration_password="Gate_Engagement_Migration_2026"
measurement_password="Gate_Measurement_App_2026"
measurement_migration_password="Gate_Measurement_Migration_2026"

cleanup() {
  if docker inspect "${container_name}" >/dev/null 2>&1; then
    docker stop --time 5 "${container_name}" >/dev/null
  fi
}
trap cleanup EXIT INT TERM

docker run --detach --rm \
  --name "${container_name}" \
  --tmpfs /var/lib/mysql:rw \
  --env MYSQL_ROOT_PASSWORD="${root_password}" \
  --env CONTROL_DB_PASSWORD="${control_password}" \
  --env CONTROL_MIGRATION_DB_PASSWORD="${control_migration_password}" \
  --env COMPILER_DB_PASSWORD="${compiler_password}" \
  --env COMPILER_MIGRATION_DB_PASSWORD="${compiler_migration_password}" \
  --env AUDIENCE_DB_PASSWORD="${audience_password}" \
  --env AUDIENCE_MIGRATION_DB_PASSWORD="${audience_migration_password}" \
  --env DECISION_DB_PASSWORD="${decision_password}" \
  --env DECISION_MIGRATION_DB_PASSWORD="${decision_migration_password}" \
  --env BENEFIT_DB_PASSWORD="${benefit_password}" \
  --env BENEFIT_MIGRATION_DB_PASSWORD="${benefit_migration_password}" \
  --env EVENTS_DB_PASSWORD="${events_password}" \
  --env EVENTS_MIGRATION_DB_PASSWORD="${events_migration_password}" \
  --env JOURNEY_DB_PASSWORD="${journey_password}" \
  --env JOURNEY_MIGRATION_DB_PASSWORD="${journey_migration_password}" \
  --env ENGAGEMENT_DB_PASSWORD="${engagement_password}" \
  --env ENGAGEMENT_MIGRATION_DB_PASSWORD="${engagement_migration_password}" \
  --env MEASUREMENT_DB_PASSWORD="${measurement_password}" \
  --env MEASUREMENT_MIGRATION_DB_PASSWORD="${measurement_migration_password}" \
  --env KEYCLOAK_DB_PASSWORD="Gate_Keycloak_2026" \
  --env TZ=UTC \
  --volume "${DEV_INFRA_ROOT}/marketing/mysql/01-databases.sh:/docker-entrypoint-initdb.d/01-databases.sh:ro" \
  mysql:8.4.11 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_0900_ai_ci \
  --sql-mode=STRICT_TRANS_TABLES,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION \
  >/dev/null

ready=false
for attempt in $(seq 1 600); do
  if users_created="$(docker exec --env MYSQL_PWD="${root_password}" "${container_name}" \
      mysql --batch --skip-column-names --host=127.0.0.1 --user=root \
      --execute="SELECT COUNT(*) FROM mysql.user WHERE User='measurement_migrator'" 2>/dev/null)"; then
    if [[ "${users_created}" == "1" ]]; then
      ready=true
      break
    fi
  fi
  sleep 1
done
if [[ "${ready}" != "true" ]]; then
  docker logs "${container_name}" >&2
  echo "MySQL migration gate did not become ready" >&2
  exit 1
fi

service_databases=(
  "services/marketing-control-service:marketing_control:control_app:control_migrator:${control_password}:${control_migration_password}"
  "services/rule-compiler-worker:marketing_compiler:compiler_app:compiler_migrator:${compiler_password}:${compiler_migration_password}"
  "services/audience-service:marketing_audience:audience_app:audience_migrator:${audience_password}:${audience_migration_password}"
  "services/offer-decision-service:marketing_decision:decision_app:decision_migrator:${decision_password}:${decision_migration_password}"
  "services/benefit-funding-service:marketing_benefit:benefit_app:benefit_migrator:${benefit_password}:${benefit_migration_password}"
  "services/event-gateway-service:marketing_events:events_app:events_migrator:${events_password}:${events_migration_password}"
  "services/journey-service:marketing_journey:journey_app:journey_migrator:${journey_password}:${journey_migration_password}"
  "services/engagement-service:marketing_engagement:engagement_app:engagement_migrator:${engagement_password}:${engagement_migration_password}"
  "services/measurement-service:marketing_measurement:measurement_app:measurement_migrator:${measurement_password}:${measurement_migration_password}"
)

for mapping in "${service_databases[@]}"; do
  IFS=: read -r service_path database_name app_user migrator_user app_password migration_password <<< "${mapping}"

  migration_count=0
  while IFS= read -r migration; do
    docker exec --interactive --env MYSQL_PWD="${migration_password}" "${container_name}" \
      mysql --host=127.0.0.1 --user="${migrator_user}" --database="${database_name}" < "${migration}"
    migration_count=$((migration_count + 1))
  done < <(find "${service_path}/src/main/resources/db/migration" -maxdepth 1 -type f -name 'V*.sql' -print | sort -V)

  if (( migration_count == 0 )); then
    echo "No migration found for ${service_path}" >&2
    exit 1
  fi
  table_count="$(docker exec --env MYSQL_PWD="${app_password}" "${container_name}" \
    mysql --batch --skip-column-names --host=127.0.0.1 --user="${app_user}" --database="${database_name}" \
    --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")"
  if (( table_count == 0 )); then
    echo "Migrations created no tables in ${database_name}" >&2
    exit 1
  fi
  echo "mysql migration verified: ${database_name} (${migration_count} files, ${table_count} tables)"
done

for mapping in "${service_databases[@]}"; do
  IFS=: read -r _ database_name app_user migrator_user app_password migration_password <<< "${mapping}"
  docker exec --env MYSQL_PWD="${migration_password}" "${container_name}" \
    mysql --host=127.0.0.1 --user="${migrator_user}" --database="${database_name}" \
    --execute="CREATE TABLE gate_privilege_probe(id BIGINT NOT NULL PRIMARY KEY, value_text VARCHAR(32) NOT NULL)" >/dev/null
done

for mapping in "${service_databases[@]}"; do
  IFS=: read -r _ database_name app_user migrator_user app_password migration_password <<< "${mapping}"
  docker exec --env MYSQL_PWD="${app_password}" "${container_name}" \
    mysql --host=127.0.0.1 --user="${app_user}" --database="${database_name}" \
    --execute="INSERT INTO gate_privilege_probe VALUES (1,'created'); UPDATE gate_privilege_probe SET value_text='updated' WHERE id=1; DELETE FROM gate_privilege_probe WHERE id=1" >/dev/null

  if docker exec --env MYSQL_PWD="${app_password}" "${container_name}" \
      mysql --host=127.0.0.1 --user="${app_user}" --database="${database_name}" \
      --execute="CREATE TABLE gate_runtime_ddl_must_fail(id BIGINT)" >/dev/null 2>&1; then
    echo "Runtime user ${app_user} unexpectedly has DDL privilege" >&2
    exit 1
  fi
  cross_database="marketing_control"
  [[ "${database_name}" == "marketing_control" ]] && cross_database="marketing_compiler"
  if docker exec --env MYSQL_PWD="${app_password}" "${container_name}" \
      mysql --host=127.0.0.1 --user="${app_user}" \
      --execute="SELECT * FROM ${cross_database}.gate_privilege_probe" >/dev/null 2>&1; then
    echo "Runtime user ${app_user} unexpectedly accessed ${cross_database}" >&2
    exit 1
  fi
  if docker exec --env MYSQL_PWD="${migration_password}" "${container_name}" \
      mysql --host=127.0.0.1 --user="${migrator_user}" \
      --execute="SELECT * FROM ${cross_database}.gate_privilege_probe" >/dev/null 2>&1; then
    echo "Migration user ${migrator_user} unexpectedly accessed ${cross_database}" >&2
    exit 1
  fi
  docker exec --env MYSQL_PWD="${migration_password}" "${container_name}" \
    mysql --host=127.0.0.1 --user="${migrator_user}" --database="${database_name}" \
    --execute="DROP TABLE gate_privilege_probe" >/dev/null
  echo "mysql least-privilege verified: ${app_user} / ${migrator_user}"
done

echo "all MySQL 8.4 migrations and database privilege boundaries verified"
