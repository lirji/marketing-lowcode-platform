#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

missing=0
if command -v gitleaks >/dev/null 2>&1; then gitleaks detect --source . --no-banner --redact; else echo "gitleaks not installed"; missing=1; fi
if command -v trivy >/dev/null 2>&1; then trivy fs --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --exit-code 1 .; else echo "trivy not installed"; missing=1; fi
if command -v syft >/dev/null 2>&1; then syft dir:. -o cyclonedx-json=artifacts/sbom-source.cdx.json; else echo "syft not installed"; missing=1; fi

if [[ "${CI:-false}" == "true" && "${missing}" == "1" ]]; then
  echo "CI security tooling is incomplete" >&2
  exit 1
fi
