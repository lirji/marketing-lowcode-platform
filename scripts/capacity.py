#!/usr/bin/env python3
"""Deterministic reference-envelope capacity calculator; replace inputs with measured production values."""
from __future__ import annotations

import argparse
import json
import math


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--forecast-qps", type=float, required=True)
    parser.add_argument("--contracted-qps", type=float, required=True)
    parser.add_argument("--retry-factor", type=float, default=1.10)
    parser.add_argument("--burst-factor", type=float, default=3.0)
    parser.add_argument("--cpu-ms", type=float, required=True)
    parser.add_argument("--target-utilization", type=float, default=0.60)
    parser.add_argument("--event-bytes", type=int, default=1200)
    parser.add_argument("--retention-hours", type=int, default=168)
    parser.add_argument("--replication", type=int, default=3)
    args = parser.parse_args()
    if min(args.forecast_qps, args.contracted_qps, args.retry_factor, args.burst_factor,
           args.cpu_ms, args.target_utilization, args.event_bytes, args.retention_hours,
           args.replication) <= 0:
        parser.error("all capacity inputs must be positive")
    if args.target_utilization >= 1:
        parser.error("target utilization must be below 1")

    design_qps = max(args.contracted_qps, args.forecast_qps * args.retry_factor * args.burst_factor)
    cores = design_qps * (args.cpu_ms / 1000.0) / args.target_utilization
    kafka_bytes = design_qps * args.event_bytes * args.retention_hours * 3600 * args.replication
    result = {
        "designQps": math.ceil(design_qps),
        "minimumCpuCores": math.ceil(cores),
        "kafkaRetainedBytes": math.ceil(kafka_bytes),
        "kafkaRetainedTiB": round(kafka_bytes / (1024 ** 4), 2),
        "assumptions": vars(args),
        "warning": "Reference estimate only; admission requires measured CPU, skew, telemetry and failure overhead.",
    }
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
