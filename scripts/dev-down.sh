#!/usr/bin/env bash
set -Eeuo pipefail
# shellcheck source=scripts/_lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

if [[ "${1:-}" == "--purge" ]]; then
  if [[ "${CONFIRM_PURGE:-}" != "marketing-r1" ]]; then
    echo "Refusing to delete volumes. Set CONFIRM_PURGE=marketing-r1 and retry." >&2
    exit 2
  fi
  compose down --volumes --remove-orphans
  echo "Local Compose containers and named volumes were deleted; source files and backups were retained."
else
  compose down --remove-orphans
fi
marketing_infra down
