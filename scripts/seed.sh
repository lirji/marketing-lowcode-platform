#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

header_args=(
  -H 'Content-Type: application/json'
  -H 'X-Dev-Tenant-Id: retail-cn'
  -H 'X-Dev-Organization-Ids: retail-business'
  -H 'X-Dev-Shop-Ids: all-shops'
  -H 'X-Dev-Actor-Id: r1-seed'
  -H 'X-Dev-Permissions: *'
)

existing="$(curl -fsS "${header_args[@]}" "${GATEWAY_URL}/api/v1/campaigns")"
if python3 -c 'import json,sys; raise SystemExit(0 if any(x.get("name")=="R1 全域家电演示" for x in json.load(sys.stdin)) else 1)' <<<"${existing}"; then
  echo "seed already present"
  exit 0
fi

payload='{"name":"R1 全域家电演示","objective":"验证从低代码定义到发布、决策、履约和衡量的完整闭环","organizationId":"retail-business","shopId":"all-shops"}'
created="$(curl -fsS "${header_args[@]}" -H 'Idempotency-Key: seed-campaign-r1' -d "${payload}" "${GATEWAY_URL}/api/v1/campaigns")"
python3 -c 'import json,sys; value=json.load(sys.stdin); assert value["name"]=="R1 全域家电演示"; print("seeded campaign", value["id"])' <<<"${created}"
