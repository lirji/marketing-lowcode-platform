#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

restore_plan=""
if [[ $# -gt 0 ]]; then
  [[ $# -eq 2 && "$1" == "--restore-plan" ]] || {
    echo "Usage: $0 [--restore-plan /path/to/plan.json]" >&2; exit 2;
  }
  restore_plan="$2"
fi
require_command python3
require_command docker
require_command curl

# 本机入口互斥；崩溃遗留锁必须人工核对作业后清理，不自动夺取。
lock_directory="${PROJECT_ROOT}/.flink-submit.lock"
mkdir "${lock_directory}" 2>/dev/null || {
  echo "Another submission may be active: ${lock_directory}. Inspect the process and Flink before removing a stale lock." >&2
  exit 2
}
trap 'rmdir "${lock_directory}"' EXIT
wait_http flink "${FLINK_URL}/overview" 120
check_args=(--allow-empty)
[[ -z "${restore_plan}" ]] || check_args+=(--allow-stopped)
if python3 "${PROJECT_ROOT}/scripts/flink-state.py" check --url "${FLINK_URL}" --wait-seconds 180 "${check_args[@]}"; then
  exit 0
else
  status=$?
fi
[[ "${status}" == 10 ]] || exit "${status}"

export AUDIENCE_RESTORE_PATH="" JOURNEY_RESTORE_PATH="" MEASUREMENT_RESTORE_PATH=""
if [[ -n "${restore_plan}" ]]; then
  restored_environment="$(python3 "${PROJECT_ROOT}/scripts/flink-state.py" restore-plan "${restore_plan}")"
  while IFS=$'\t' read -r variable value; do
    # 校验器只输出固定变量名；参数独立传递，不执行恢复计划中的文本。
    export "${variable}=${value}"
    compose exec -T flink-jobmanager test -f "${value#file://}" || {
      echo "Restore metadata is missing for ${variable}" >&2; exit 2;
    }
  done <<<"${restored_environment}"
else
  retained="$(compose exec -T flink-jobmanager find /opt/flink/checkpoints /opt/flink/savepoints -name _metadata -print -quit)"
  [[ -z "${retained}" ]] || {
    echo "Retained Flink state exists. Refusing an empty-state restart; supply --restore-plan with all three metadata paths." >&2
    exit 2
  }
fi

compose run --rm --no-deps -e AUDIENCE_RESTORE_PATH -e JOURNEY_RESTORE_PATH -e MEASUREMENT_RESTORE_PATH flink-job-submit
python3 "${PROJECT_ROOT}/scripts/flink-state.py" check --url "${FLINK_URL}" --wait-seconds 180
