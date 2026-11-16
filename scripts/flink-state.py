#!/usr/bin/env python3
"""校验实际运行作业与恢复输入；不把作业数量或注册成功当作恢复成功。"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.request
from collections import Counter
from enum import Enum
from pathlib import Path, PurePosixPath

EXPECTED_JOBS = frozenset({"audience-membership-projection", "journey-runtime", "measurement-fact-projection"})
TERMINAL_STATES = frozenset({"FINISHED", "CANCELED", "FAILED", "SUSPENDED"})
RESTORE_VARIABLES = {"audience": "AUDIENCE_RESTORE_PATH", "journey": "JOURNEY_RESTORE_PATH", "measurement": "MEASUREMENT_RESTORE_PATH"}
EMPTY_EXIT = 10
MAX_CHECKPOINT_AGE_MS = 180_000


class HealthState(str, Enum):
    """检查结果稳定编码；空集群与有冲突的集群不能走同一提交分支。"""
    HEALTHY = "healthy"
    EMPTY = "empty"
    CONFLICT = "conflict"
    WAITING = "waiting"


def fetch(base: str, path: str) -> dict:
    """每次请求有超时，避免健康检查永久挂起。"""
    with urllib.request.urlopen(base.rstrip("/") + path, timeout=5) as response:
        return json.load(response)


def assess(overview: dict, checkpoint_reader, now_ms: int) -> tuple[HealthState, str]:
    """只接受三个指定作业全部运行且有近期完成检查点，终态历史不计入运行数量。"""
    jobs = [job for job in overview.get("jobs", []) if job.get("state") not in TERMINAL_STATES]
    if not jobs:
        return HealthState.EMPTY, "no active jobs; inspect retained state before submitting"
    names = Counter(job.get("name", "") for job in jobs)
    if set(names) != EXPECTED_JOBS or any(count != 1 for count in names.values()):
        return HealthState.CONFLICT, "expected exactly one of each R1 job; found " + json.dumps(dict(names), sort_keys=True)
    for job in jobs:
        if job.get("state") != "RUNNING":
            return HealthState.WAITING, f'{job["name"]} is {job.get("state")}'
        tasks = job.get("tasks", {})
        if tasks.get("total", 0) < 1 or tasks.get("running", 0) != tasks["total"]:
            return HealthState.WAITING, f'{job["name"]} has subtasks not running'
        checkpoint = checkpoint_reader(job["jid"]).get("latest", {}).get("completed")
        if not checkpoint or checkpoint.get("status") != "COMPLETED":
            return HealthState.WAITING, f'{job["name"]} has no completed checkpoint'
        timestamp = checkpoint.get("latest_ack_timestamp", checkpoint.get("trigger_timestamp", 0))
        if timestamp <= 0 or now_ms - timestamp > MAX_CHECKPOINT_AGE_MS or timestamp > now_ms + 30_000:
            return HealthState.WAITING, f'{job["name"]} checkpoint is stale or has an invalid timestamp'
    return HealthState.HEALTHY, "all three named R1 jobs and subtasks RUNNING with recent completed checkpoints"


def restore_environment(plan: dict) -> list[tuple[str, str]]:
    """恢复路径限定于共享 Flink 状态卷，拒绝缺失作业、路径穿越和命令注入字符。"""
    if set(plan) != set(RESTORE_VARIABLES):
        raise ValueError("restore plan must contain exactly audience, journey and measurement")
    result = []
    for job, variable in RESTORE_VARIABLES.items():
        value = plan[job]
        if not isinstance(value, str) or not re.fullmatch(r"file:///opt/flink/(checkpoints|savepoints)/[A-Za-z0-9._/-]+/_metadata", value):
            raise ValueError(f"invalid local checkpoint metadata path for {job}")
        path = value.removeprefix("file://")
        if ".." in PurePosixPath(path).parts or str(PurePosixPath(path)) != path:
            raise ValueError(f"non-canonical checkpoint path for {job}")
        result.append((variable, value))
    if len({path for _, path in result}) != len(result):
        raise ValueError("each job must have its own checkpoint metadata")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("check")
    check.add_argument("--url", required=True)
    check.add_argument("--wait-seconds", type=float, default=0)
    check.add_argument("--allow-empty", action="store_true")
    check.add_argument("--allow-stopped", action="store_true")
    plan = commands.add_parser("restore-plan")
    plan.add_argument("path", type=Path)
    args = parser.parse_args()
    if args.command == "restore-plan":
        for variable, value in restore_environment(json.loads(args.path.read_text())):
            print(variable + "\t" + value)
        return 0
    deadline = time.monotonic() + max(0, args.wait_seconds)
    while True:
        overview = fetch(args.url, "/jobs/overview")
        state, message = assess(overview,
                                lambda job: fetch(args.url, f"/jobs/{job}/checkpoints"), int(time.time() * 1000))
        if state == HealthState.HEALTHY:
            print(message)
            return 0
        if state == HealthState.EMPTY and args.allow_empty:
            if overview.get("jobs") and not args.allow_stopped:
                print("Stopped jobs exist; an explicit recovery plan is required", file=sys.stderr)
                return 2
            print(message)
            return EMPTY_EXIT
        if state == HealthState.CONFLICT or time.monotonic() >= deadline:
            print(message, file=sys.stderr)
            return 2
        time.sleep(min(2, max(0, deadline - time.monotonic())))


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError, TypeError) as failure:
        print(f"Flink state validation failed: {failure}", file=sys.stderr)
        sys.exit(2)
