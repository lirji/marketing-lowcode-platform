#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

overview="$(curl -fsS "${FLINK_URL}/jobs/overview")"
running="$(python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("jobs", [])))' <<<"${overview}")"
if [[ "${running}" == "3" ]]; then
  echo "all three Flink jobs are already registered"
  exit 0
fi
if [[ "${running}" != "0" ]]; then
  echo "Refusing blind resubmission: expected zero or three jobs, found ${running}. Inspect Flink first." >&2
  exit 2
fi
compose up --no-deps --force-recreate flink-job-submit
