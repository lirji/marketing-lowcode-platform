#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
module=jobs/journey-runtime-flink-job
./mvnw --batch-mode --no-transfer-progress -pl "$module" -am -DskipTests package
./mvnw --batch-mode --no-transfer-progress -pl services/journey-service -am \
  -Dtest=JourneyProbeCheckpointIT -Dsurefire.failIfNoSpecifiedTests=false \
  "-Djourney.probe.jar=$PWD/$module/target/journey-runtime-flink-job-1.0.0-SNAPSHOT.jar" test
