#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
jar="${DECISION_SERVICE_JAR:-$root/services/offer-decision-service/target/offer-decision-service-1.0.0-SNAPSHOT-exec.jar}"
exec java -Dloader.main=com.acme.marketing.decision.deployment.DecisionInventoryTool \
  -cp "$jar" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
