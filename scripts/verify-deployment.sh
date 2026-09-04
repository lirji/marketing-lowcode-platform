#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"
cd "${PROJECT_ROOT}"
DEV_INFRA_ROOT="${DEV_INFRA_ROOT:-$(cd "${PROJECT_ROOT}/.." && pwd)/dev-infra}"
[[ -f "${DEV_INFRA_ROOT}/marketing/compose.yaml" ]] || {
  echo "Missing dev-infra Marketing definition at ${DEV_INFRA_ROOT}/marketing/compose.yaml" >&2
  exit 1
}

require_command docker
require_command python3
require_command helm

docker compose --env-file .env.example -f deploy/compose.yaml config --quiet
docker compose --env-file .env.example -f deploy/compose.yaml config --format json \
  | python3 scripts/verify-compose-model.py
docker compose --env-file "${DEV_INFRA_ROOT}/.env.example" \
  -f "${DEV_INFRA_ROOT}/compose.yaml" config --quiet
docker compose --env-file "${DEV_INFRA_ROOT}/.env.example" --env-file .env.example \
  -f "${DEV_INFRA_ROOT}/marketing/compose.yaml" config --quiet

if grep -R -l --include='pom.xml' \
  '<artifactId>spring-kafka</artifactId>' services jobs platform-common platform-web runtime-spi \
  >/dev/null; then
  echo "Use spring-boot-starter-kafka so Spring Boot consumer auto-configuration is present" >&2
  exit 1
fi
if rg -l 'jdbc:h2|com\.h2database' services \
  --glob 'pom.xml' --glob '*.yml' --glob '*.yaml' --glob '*.properties' \
  >/dev/null; then
  echo "H2 is prohibited: service persistence and integration tests must use MySQL" >&2
  exit 1
fi
helm lint deploy/helm/marketing-platform
helm template marketing deploy/helm/marketing-platform >/dev/null
helm template marketing deploy/helm/marketing-platform \
  -f deploy/helm/marketing-platform/values-production.example.yaml >/dev/null
helm template marketing deploy/helm/marketing-platform \
  -f deploy/helm/marketing-platform/values-production.example.yaml \
  --set flink.enabled=true >/dev/null

docker run --rm --entrypoint=/bin/promtool \
  -v "${DEV_INFRA_ROOT}/marketing/prometheus/rules/marketing.yml:/etc/prometheus/marketing.yml:ro" \
  prom/prometheus:v3.13.1 check rules /etc/prometheus/marketing.yml

python3 -m json.tool "${DEV_INFRA_ROOT}/marketing/keycloak/marketing-realm.json" >/dev/null
python3 -m json.tool "${DEV_INFRA_ROOT}/marketing/grafana/dashboards/marketing-platform.json" >/dev/null
while IFS= read -r script; do bash -n "${script}"; done < <(find scripts -type f -name '*.sh' -print | sort)
bash -n "${DEV_INFRA_ROOT}/bin/marketing-infra"
bash -n "${DEV_INFRA_ROOT}/marketing/mysql/01-databases.sh"
bash -n "${DEV_INFRA_ROOT}/marketing/kafka/create-topics.sh"

echo "deployment manifests verified"
