#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -B -ntp -pl jobs/journey-runtime-flink-job,services/marketing-control-service,services/offer-decision-service,services/benefit-funding-service -am -Dmaven.test.skip=true package
./mvnw -B -ntp -pl services/journey-service -am \
  -Dtest=JourneyProbeCheckpointIT#appliesAndRollsBackCampaignAsOneVersion -Dsurefire.failIfNoSpecifiedTests=false \
  -Dbundle.acceptance=true "-Dbundle.browser=${BUNDLE_ACCEPTANCE_BROWSER:-true}" "-Dbundle.repository=$PWD" \
  "-Djourney.probe.jar=$PWD/jobs/journey-runtime-flink-job/target/journey-runtime-flink-job-1.0.0-SNAPSHOT.jar" test
python3 - <<'PYTHON'
from pathlib import Path
from xml.etree import ElementTree
report = Path("services/journey-service/target/surefire-reports/TEST-com.acme.marketing.journeyservice.JourneyProbeCheckpointIT.xml")
case = ElementTree.parse(report).find("./testcase[@name='appliesAndRollsBackCampaignAsOneVersion']")
if case is None or any(case.find(state) is not None for state in ("skipped", "failure", "error")):
    raise SystemExit("campaign bundle acceptance did not execute successfully")
PYTHON
