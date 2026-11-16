#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

wait_mode=false
with_observability="${MARKETING_OBSERVABILITY_ENABLED:-false}"
for argument in "$@"; do
  case "${argument}" in
    --wait) wait_mode=true ;;
    --observability) with_observability=true ;;
    *) echo "Unknown argument: ${argument}" >&2; exit 2 ;;
  esac
done

if [[ "${wait_mode}" == true ]]; then
  wait_http gateway "${GATEWAY_URL}/actuator/health/readiness" 150
  wait_http console "${CONSOLE_URL}/healthz" 60
  wait_http flink "${FLINK_URL}/overview" 120
  # 首发之前 readiness 应保持 DOWN，只等待进程可接受签名预热请求。
  decision_live=false
  for ((attempt=0; attempt<120; attempt++)); do
    if compose exec -T offer-decision-service curl -fsS http://localhost:8084/actuator/health/liveness >/dev/null; then
      decision_live=true
      break
    fi
    sleep 2
  done
  [[ "${decision_live}" == true ]] || { echo "Decision liveness did not become available" >&2; exit 1; }
  # DEV 模式使用本地开发请求头，不依赖 Keycloak；此时强制等待 Keycloak 会让
  # smoke 在与其他项目共用 8180 端口时无意义地超时。
  if [[ "${MARKETING_SECURITY_MODE:-DEV}" != "DEV" ]]; then
    wait_http keycloak "${KEYCLOAK_URL}/realms/marketing/.well-known/openid-configuration" 120
  fi
  if [[ "${with_observability}" == true ]]; then
    wait_http prometheus "${PROMETHEUS_URL}/-/ready" 60
    wait_http grafana "${GRAFANA_URL}/api/health" 90
  fi
fi

header_args=(
  -H 'X-Dev-Tenant-Id: retail-cn'
  -H 'X-Dev-Organization-Ids: retail-business'
  -H 'X-Dev-Shop-Ids: all-shops'
  -H 'X-Dev-Actor-Id: r1-smoke'
  -H 'X-Dev-Permissions: *'
)

status="$(curl -sS -o /dev/null -w '%{http_code}' "${GATEWAY_URL}/api/v1/campaigns")"
[[ "${status}" == "401" ]] || { echo "Expected unauthenticated campaign request to return 401, got ${status}" >&2; exit 1; }

echo "checking authenticated campaign and funding reads"
# 启动时连接池/Redis 首次建连可能暂时 503；仅对这两个只读 GET 做有界重试。
read_api() {
  curl -fsS --connect-timeout 3 --max-time 10 --retry 3 --retry-delay 1 --retry-max-time 15 "$@"
}
campaigns="$(read_api "${header_args[@]}" "${GATEWAY_URL}/api/v1/campaigns")"
python3 -c 'import json,sys; value=json.load(sys.stdin); assert isinstance(value,list)' <<<"${campaigns}"

reconciliation="$(read_api "${header_args[@]}" "${GATEWAY_URL}/api/v1/funding/reconciliation")"
python3 -c 'import json,sys; value=json.load(sys.stdin); assert "balanced" in value' <<<"${reconciliation}"

python3 "${PROJECT_ROOT}/scripts/flink-state.py" check --url "${FLINK_URL}" --wait-seconds 120

infra_compose exec -T kafka38 /opt/kafka/bin/kafka-topics.sh --bootstrap-server infra-kafka38:9092 --list \
  | python3 -c 'import sys; topics=set(line.strip() for line in sys.stdin); required={"mk.profile.change.v1","mk.journey.signal.v1","mk.marketing.fact.v1","marketing.award-expected.v1"}; missing=required-topics; assert not missing, f"missing topics: {missing}"'

python3 "${PROJECT_ROOT}/scripts/acceptance-r1.py"

echo "full-stack smoke passed"
