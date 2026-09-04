#!/usr/bin/env python3
"""Semantic deployment checks that Compose's schema validation cannot express."""

from __future__ import annotations

import json
import sys
from typing import Any


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


model: dict[str, Any] = json.load(sys.stdin)
services: dict[str, dict[str, Any]] = model["services"]

for service_name, service in services.items():
    for port in service.get("ports", []):
        require(
            port.get("host_ip") == "127.0.0.1",
            f"{service_name} publishes a port outside loopback",
        )

console = services["console"]
require(console.get("read_only") is True, "console root filesystem must be read-only")
for mount in console.get("tmpfs", []):
    require(mount.startswith("/"), f"console tmpfs target is not absolute: {mount}")
require(
    any(mount.startswith("/etc/marketing-console:") for mount in console["tmpfs"]),
    "console runtime config needs a dedicated writable tmpfs",
)

job_manager = services["flink-jobmanager"]
flink_properties = job_manager["environment"]["FLINK_PROPERTIES"]
require("ingress" in job_manager["networks"], "Flink UI host mapping needs a non-internal network")
require(
    "rest.address: flink-jobmanager" in flink_properties,
    "Flink clients need a routable REST address",
)
require(
    "metrics.reporter.prom.factory.class:" in flink_properties,
    "Flink Prometheus reporter is required",
)
require("dev-infra" in job_manager["networks"], "Flink must use shared dev-infra")

submit = services["flink-job-submit"]
require(
    submit.get("entrypoint") == ["/docker-entrypoint.sh"],
    "Flink submitter must preserve the upstream configuration entrypoint",
)
submit_command = "\n".join(submit["command"])
require(submit_command.count("flink run -d ") == 3, "exactly three R1 Flink jobs must be submitted")

java_services = {
    "edge-gateway",
    "marketing-control-service",
    "rule-compiler-worker",
    "audience-service",
    "offer-decision-service",
    "benefit-funding-service",
    "event-gateway-service",
    "journey-service",
    "engagement-service",
    "measurement-service",
}
for service_name in java_services:
    service = services[service_name]
    require(service.get("read_only") is True, f"{service_name} root filesystem must be read-only")
    require(service.get("user") == "10001:0", f"{service_name} must run as the application user")
    require("ALL" in service.get("cap_drop", []), f"{service_name} must drop Linux capabilities")
    require(
        "no-new-privileges:true" in service.get("security_opt", []),
        f"{service_name} must prohibit privilege escalation",
    )
    require(
        service.get("environment", {}).get("MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED") == "false",
        f"{service_name} must not duplicate Prometheus metrics through OTLP",
    )
    require("dev-infra" in service["networks"], f"{service_name} must use shared dev-infra")

require(
    not ({"mysql", "redis", "kafka", "minio", "clickhouse", "keycloak", "otel-collector",
          "tempo", "loki", "prometheus", "alertmanager", "grafana"} & services.keys()),
    "application Compose must not own databases or middleware",
)
infra_network = model["networks"]["dev-infra"]
require(infra_network.get("external") is True, "dev-infra network must be external")
require(infra_network.get("name") == "dev-infra", "dev-infra network name changed")

decision = services["offer-decision-service"]["environment"]
require("infra-redis7:6379" in decision["REDIS_URL"], "Decision must use shared Redis")
require(
    decision["KAFKA_BOOTSTRAP_SERVERS"] == "infra-kafka38:9092",
    "Decision must use shared Kafka",
)

mysql_databases = {
    "marketing-control-service": "marketing_control",
    "rule-compiler-worker": "marketing_compiler",
    "audience-service": "marketing_audience",
    "offer-decision-service": "marketing_decision",
    "benefit-funding-service": "marketing_benefit",
    "event-gateway-service": "marketing_events",
    "journey-service": "marketing_journey",
    "engagement-service": "marketing_engagement",
    "measurement-service": "marketing_measurement",
}
for service_name, database_name in mysql_databases.items():
    database_url = services[service_name]["environment"]["MARKETING_DB_URL"]
    require(
        f"infra-mysql84:3306/{database_name}?" in database_url,
        f"{service_name} must use its own database on shared MySQL",
    )

print("compose semantic model verified")
