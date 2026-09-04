#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

./mvnw --batch-mode --no-transfer-progress -pl marketing-contracts -am test

python3 - <<'PY'
import json
from pathlib import Path

root = Path("marketing-contracts/src/main/resources")
files = sorted(root.rglob("*.json"))
if not files:
    raise SystemExit("no JSON contracts found")
for path in files:
    with path.open(encoding="utf-8") as stream:
        json.load(stream)
print(f"validated {len(files)} JSON contracts")
PY

for required in marketing-contracts/src/main/resources/openapi/*.yaml marketing-contracts/src/main/resources/asyncapi/*.yaml; do
  [[ -s "${required}" ]] || { echo "Missing or empty contract: ${required}" >&2; exit 1; }
done
