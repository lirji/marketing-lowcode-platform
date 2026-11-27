#!/usr/bin/env python3
"""创建独占的版本化对象存储及受限角色，仅用于 CI 的归档验收。"""

import json
import os
import secrets
import subprocess
import time
from urllib.request import urlopen

PROJECT = "marketing"
# 使用本地归档演练同一多架构镜像清单的公开镜像，校验固定摘要。
IMAGE = "docker.io/jcometki/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e"

if os.getenv("GITHUB_ACTIONS") != "true":
    raise SystemExit("该脚本仅用于隔离的 GitHub CI 运行器。")
run_id, attempt = os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"]
if not run_id.isdigit() or not attempt.isdigit():
    raise SystemExit("无效的隔离 CI 运行编号。")
container = f"{PROJECT}-ci-audit-{run_id}-{attempt}"


def request(args, data=None, env=None, timeout=30):
    try:
        result = subprocess.run(args, input=data, env=env, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise SystemExit("CI 归档夹具等待超时，敏感参数已隐藏。") from None
    if result.returncode != 0:
        raise SystemExit("CI 归档夹具初始化失败，凭据和命令参数已隐藏。")
    return result.stdout.strip()


def mc(*args):
    return request(["docker", "exec", container, "mc", *args])


def grant(role, statements):
    key, secret = role.lower().replace("_", "-") + "-" + secrets.token_hex(4), secrets.token_hex(24)
    path = "/tmp/audit-policy-" + role.lower() + ".json"
    document = json.dumps({"Version": "2012-10-17", "Statement": statements})
    request(["docker", "exec", "-i", container, "sh", "-c", "cat > " + path], data=document)
    mc("admin", "policy", "create", "fixture", role, path)
    mc("admin", "user", "add", "fixture", key, secret)
    mc("admin", "policy", "attach", "fixture", role, "--user", key)
    return key, secret


def statement(actions, resource, condition=None):
    value = {"Effect": "Allow", "Action": actions, "Resource": [resource]}
    if condition:
        value["Condition"] = condition
    return value


root_user = "audit-fixture-" + secrets.token_hex(4)
root_password = secrets.token_hex(24)
with open(os.environ["GITHUB_ENV"], "a") as output:
    output.write("AUDIT_ARCHIVE_CI_CONTAINER=" + container + "\n")
request(["docker", "run", "--detach", "--name", container, "--publish", "127.0.0.1::9000",
         "--env", "MINIO_ROOT_USER", "--env", "MINIO_ROOT_PASSWORD", IMAGE, "server", "/data"],
        env=dict(os.environ, MINIO_ROOT_USER=root_user, MINIO_ROOT_PASSWORD=root_password), timeout=180)
endpoint = "http://" + request(["docker", "port", container, "9000"])
for _ in range(40):
    try:
        with urlopen(endpoint + "/minio/health/ready", timeout=2):
            break
    except OSError:
        time.sleep(1)
else:
    raise SystemExit("CI 版本化对象存储没有就绪。")
mc("alias", "set", "fixture", "http://127.0.0.1:9000", root_user, root_password)

values = {"MKT_AUDIT_ARCHIVE_TEST_ENDPOINT": endpoint}
for source in ("funding", "release"):
    bucket = f"mkt-{source}-audit-verification"
    mc("mb", "fixture/" + bucket)
    mc("version", "enable", "fixture/" + bucket)
    arn = "arn:aws:s3:::" + bucket
    base = f"marketing/{source}/operation-audit/v1/"
    write_key, write_secret = grant(f"mkt_{source}_archive_write", [
        statement(["s3:GetBucketVersioning"], arn),
        statement(["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject"], f"{arn}/{base}*"),
    ])
    restore = [statement(["s3:GetObjectVersion"], f"{arn}/{base}*")]
    if source == "funding":
        recovery = base + "tenant-checkout-funding/recovery/"
        restore.extend([
            statement(["s3:GetObject"], f"{arn}/{recovery}committed/*"),
            statement(["s3:ListBucket"], arn, {"StringLike": {"s3:prefix": [f"{recovery}prepared/*"]}}),
        ])
    restore_key, restore_secret = grant(f"mkt_{source}_archive_restore", restore)
    prefix = f"MKT_{source.upper()}_ARCHIVE_"
    values.update({prefix + "WRITE_KEY": write_key, prefix + "WRITE_SECRET": write_secret,
                   prefix + "RESTORE_KEY": restore_key, prefix + "RESTORE_SECRET": restore_secret})

with open(os.environ["GITHUB_ENV"], "a") as output:
    for name, value in values.items():
        if name.endswith(("_KEY", "_SECRET", "_PASSWORD", "_USER")):
            print(f"::add-mask::{value}", flush=True)
        output.write(f"{name}={value}\n")
print("CI 版本化归档夹具和独立角色已就绪。")
