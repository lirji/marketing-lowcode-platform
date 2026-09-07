#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gateway_url="${GATEWAY_URL:-}"
decision_rate="${PEAK_DECISION_RATE:-}"
event_rate="${PEAK_EVENT_RATE:-}"
warmup_duration="${WARMUP_DURATION:-30m}"
soak_duration="${SOAK_DURATION:-4h}"
burst_duration="${BURST_DURATION:-5m}"
isolation_duration="${ISOLATION_DURATION:-10m}"
result_dir="${CAPACITY_RESULT_DIR:-${repo_dir}/capacity-results/$(date -u +%Y%m%dT%H%M%SZ)}"
k6_image="${K6_IMAGE:-grafana/k6:2.2.0}"

require_positive_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
    echo "$name must be a positive integer" >&2
    exit 2
  fi
}

if [[ -z "$gateway_url" ]]; then
  echo "GATEWAY_URL is required" >&2
  exit 2
fi
require_positive_integer "PEAK_DECISION_RATE" "$decision_rate"
require_positive_integer "PEAK_EVENT_RATE" "$event_rate"
mkdir -p "$result_dir"
phase_status_file="$result_dir/phase-status.tsv"
suite_status_file="$result_dir/suite-status.txt"
printf 'phase\tstatus\n' > "$phase_status_file"
printf 'RUNNING\n' > "$suite_status_file"
export GATEWAY_URL="$gateway_url"
export PEAK_DECISION_RATE="$decision_rate"
export PEAK_EVENT_RATE="$event_rate"
export WARMUP_DURATION="$warmup_duration"
export SOAK_DURATION="$soak_duration"
export BURST_DURATION="$burst_duration"
export ISOLATION_DURATION="$isolation_duration"

run_k6() {
  local script="$1"
  local summary="$2"
  shift 2
  if command -v k6 >/dev/null 2>&1; then
    env "$@" k6 run --summary-export "$result_dir/$summary" "$repo_dir/$script"
    return
  fi
  local docker_env=()
  local pair
  for pair in "$@"; do docker_env+=(--env "$pair"); done
  docker run --rm --add-host host.docker.internal:host-gateway \
    --volume "$repo_dir:/work:ro" --volume "$result_dir:/out" \
    "${docker_env[@]}" "$k6_image" run --summary-export "/out/$summary" "/work/$script"
}

metadata_status="not-run"
load_pid=""
cleanup() {
  local exit_code=$?
  if [[ -n "$load_pid" ]] && kill -0 "$load_pid" 2>/dev/null; then
    kill "$load_pid" 2>/dev/null || true
    wait "$load_pid" 2>/dev/null || true
  fi
  if [[ "$metadata_status" == "fault-injected" && -n "${FAULT_DRIVER:-}" ]]; then
    "$FAULT_DRIVER" recover kafka || true
  fi
  if (( exit_code != 0 )); then
    printf 'FAILED\n' > "$suite_status_file"
  fi
}
trap cleanup EXIT

python3 - "$result_dir/run-metadata.json" "$repo_dir" <<'PY'
import json
import os
import platform
import subprocess
import sys
from datetime import datetime, timezone

output, repository = sys.argv[1:]
def git(*args):
    return subprocess.run(["git", "-C", repository, *args], check=True, text=True,
                          stdout=subprocess.PIPE).stdout.strip()
metadata = {
    "startedAt": datetime.now(timezone.utc).isoformat(),
    "commit": git("rev-parse", "HEAD"),
    "worktreeDirty": bool(git("status", "--porcelain")),
    "host": platform.platform(),
    "gatewayUrl": os.environ["GATEWAY_URL"],
    "peakDecisionRate": int(os.environ["PEAK_DECISION_RATE"]),
    "peakEventRate": int(os.environ["PEAK_EVENT_RATE"]),
    "durations": {name: os.environ.get(name) for name in (
        "WARMUP_DURATION", "SOAK_DURATION", "BURST_DURATION", "ISOLATION_DURATION")},
    "imageDigests": os.environ.get("CAPACITY_IMAGE_DIGESTS", "UNRECORDED"),
    "dataset": os.environ.get("CAPACITY_DATASET", "UNRECORDED"),
    "resourceEnvelope": os.environ.get("CAPACITY_RESOURCE_ENVELOPE", "UNRECORDED"),
}
with open(output, "w", encoding="utf-8") as handle:
    json.dump(metadata, handle, ensure_ascii=False, indent=2, sort_keys=True)
PY

common=("GATEWAY_URL=$gateway_url" "DECISION_P99_MS=${DECISION_P99_MS:-30}" \
  "EVENT_P99_MS=${EVENT_P99_MS:-1000}" "RUN_ID=${RUN_ID:-$(date -u +%s)}" \
  "AUTHORIZATION=${AUTHORIZATION:-}")

echo "[capacity] warm-up: $warmup_duration"
run_k6 tests/performance/mixed-capacity.js warmup-summary.json "${common[@]}" \
  "DECISION_RATE=$decision_rate" "EVENT_RATE=$event_rate" "DURATION=$warmup_duration"
printf 'warm-up\tPASS\n' >> "$phase_status_file"

echo "[capacity] peak soak: $soak_duration"
run_k6 tests/performance/mixed-capacity.js peak-soak-summary.json "${common[@]}" \
  "DECISION_RATE=$decision_rate" "EVENT_RATE=$event_rate" "DURATION=$soak_duration"
printf 'peak-soak\tPASS\n' >> "$phase_status_file"

burst_decision_rate=$((decision_rate * 3))
burst_event_rate=$((event_rate * 3))
echo "[capacity] 3x burst: $burst_duration"
run_k6 tests/performance/mixed-capacity.js burst-3x-summary.json "${common[@]}" \
  "DECISION_RATE=$burst_decision_rate" "EVENT_RATE=$burst_event_rate" "DURATION=$burst_duration" \
  "ALLOW_PROTECTION=true"
printf 'burst-3x\tPASS\n' >> "$phase_status_file"

echo "[capacity] noisy-tenant isolation: $isolation_duration"
run_k6 tests/performance/event-tenant-isolation.js tenant-isolation-summary.json \
  "GATEWAY_URL=$gateway_url" "HOT_TENANT_RATE=${HOT_TENANT_RATE:-$burst_event_rate}" \
  "CONTROL_TENANT_RATE=${CONTROL_TENANT_RATE:-$event_rate}" \
  "CONTROL_P99_MS=${EVENT_P99_MS:-1000}" "DURATION=$isolation_duration" \
  "RUN_ID=${RUN_ID:-$(date -u +%s)}" "HOT_AUTHORIZATION=${HOT_AUTHORIZATION:-${AUTHORIZATION:-}}" \
  "CONTROL_AUTHORIZATION=${CONTROL_AUTHORIZATION:-${AUTHORIZATION:-}}"
printf 'tenant-isolation\tPASS\n' >> "$phase_status_file"

suite_complete=true
if [[ -n "${FAULT_DRIVER:-}" ]]; then
  if [[ ! -x "$FAULT_DRIVER" ]]; then
    echo "FAULT_DRIVER must be an executable implementing: inject kafka | recover kafka" >&2
    exit 2
  fi
  echo "[capacity] Kafka fault injection"
  run_k6 tests/performance/event-kafka-fault.js kafka-fault-summary.json \
    "GATEWAY_URL=$gateway_url" "RATE=$event_rate" "DURATION=${FAULT_DURATION:-10m}" \
    "P99_MS=${FAULT_P99_MS:-5000}" "RUN_ID=${RUN_ID:-$(date -u +%s)}-fault" \
    "AUTHORIZATION=${FAULT_AUTHORIZATION:-${AUTHORIZATION:-}}" &
  load_pid=$!
  sleep "${FAULT_AFTER_SECONDS:-60}"
  metadata_status="fault-injected"
  "$FAULT_DRIVER" inject kafka
  sleep "${FAULT_HOLD_SECONDS:-120}"
  "$FAULT_DRIVER" recover kafka
  metadata_status="recovered"
  wait "$load_pid"
  load_pid=""
  printf 'kafka-fault\tPASS\n' >> "$phase_status_file"
else
  echo "[capacity] Kafka fault phase skipped: set an audited FAULT_DRIVER to enable it"
  printf 'kafka-fault\tSKIPPED\n' >> "$phase_status_file"
  suite_complete=false
fi

if [[ -n "${BENEFIT_LOAD_DRIVER:-}" ]]; then
  if [[ ! -x "$BENEFIT_LOAD_DRIVER" ]]; then
    echo "BENEFIT_LOAD_DRIVER must be executable" >&2
    exit 2
  fi
  echo "[capacity] hotspot benefit conservation"
  "$BENEFIT_LOAD_DRIVER" "$result_dir"
  printf 'benefit-hotspot\tPASS\n' >> "$phase_status_file"
else
  echo "[capacity] benefit hotspot phase skipped: set BENEFIT_LOAD_DRIVER with signed-token fixtures"
  printf 'benefit-hotspot\tSKIPPED\n' >> "$phase_status_file"
  suite_complete=false
fi

if [[ "$suite_complete" == "true" ]]; then
  printf 'COMPLETE\n' > "$suite_status_file"
else
  printf 'INCOMPLETE\n' > "$suite_status_file"
fi
echo "[capacity] raw evidence written to $result_dir"
