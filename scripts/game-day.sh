#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

scenario="${1:-}"
[[ "${CONFIRM_GAME_DAY:-}" == "marketing-r1-local" ]] || { echo "Set CONFIRM_GAME_DAY=marketing-r1-local; this script intentionally interrupts local services." >&2; exit 2; }
case "${scenario}" in
  decision-restart)
    compose kill offer-decision-service
    compose up -d offer-decision-service
    ;;
  telemetry-loss)
    marketing_infra_compose stop marketing-otel-collector
    curl -fsS -H 'X-Dev-Tenant-Id: retail-cn' -H 'X-Dev-Actor-Id: game-day' -H 'X-Dev-Permissions: *' "${GATEWAY_URL}/api/v1/campaigns" >/dev/null
    marketing_infra_compose start marketing-otel-collector
    ;;
  kafka-restart)
    echo "Warning: restarting shared Kafka affects every project attached to dev-infra." >&2
    infra_compose restart kafka38
    marketing_infra provision
    ;;
  *) echo "Usage: $0 {decision-restart|telemetry-loss|kafka-restart}" >&2; exit 2 ;;
esac
"${PROJECT_ROOT}/scripts/smoke.sh" --wait
echo "game-day scenario recovered: ${scenario}"
