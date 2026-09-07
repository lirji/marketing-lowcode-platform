#!/usr/bin/env python3
"""Local-only CENTER smoke: bind an ACTIVE SKU, sign an OfferToken, then create one AwardIntent."""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_CAMPAIGN = "1c467788-e9c6-46f3-abd7-1ca4e55b8264"


def local_env() -> dict[str, str]:
    values: dict[str, str] = {}
    path = ROOT / ".env"
    if not path.exists():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


ENV = local_env()


def setting(name: str, default: str = "") -> str:
    return os.environ.get(name) or ENV.get(name) or default


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def canonical(values: dict[str, str]) -> bytes:
    parts: list[str] = []
    for key in sorted(values):
        value = values[key] or ""
        parts.append(f"{len(key)}:{key}{len(value.encode('utf-8'))}:{value}")
    return "".join(parts).encode("utf-8")


def encode_strings(values: list[str]) -> str:
    return ".".join(b64url(value.encode("utf-8")) for value in values)


def openssl_binary() -> str:
    candidates = [
        setting("OPENSSL_BIN"),
        "/opt/homebrew/opt/openssl@3/bin/openssl",
        "/usr/local/opt/openssl@3/bin/openssl",
        "/opt/homebrew/bin/openssl",
        shutil.which("openssl") or "",
    ]
    for candidate in candidates:
        if not candidate or not pathlib.Path(candidate).is_file():
            continue
        inspected = subprocess.run([candidate, "version"], check=False,
                                   capture_output=True, text=True)
        if inspected.returncode == 0 and inspected.stdout.startswith("OpenSSL "):
            return candidate
    raise SystemExit(
        "OpenSSL with Ed25519 support is required; set OPENSSL_BIN to an OpenSSL 3 binary")


def offer_token(key_id: str, private_key: str, tenant: str, benefit_version: str,
                subject: str, cart_digest: str, source_request_id: str) -> str:
    offer = canonical({
        "offerId": "local-award-smoke",
        "benefitVersion": benefit_version,
        "currency": "CNY",
        "minorUnits": "1",
        "quantity": "1",
        "funding": "PLATFORM~local-smoke~CNY~1",
    })
    now = dt.datetime.now(dt.timezone.utc)
    instant = lambda value: value.isoformat(timespec="milliseconds").replace("+00:00", "Z")
    claims = canonical({
        "issuer": "local-award-intent-smoke",
        "tenant": tenant,
        "organization": "local-federation",
        "subject": subject,
        "order": f"order-{source_request_id}",
        "shops": encode_strings(["local-smoke-shop"]),
        "cartDigest": cart_digest,
        "quote": f"quote-{source_request_id}",
        "request": source_request_id,
        "generation": "1",
        "artifacts": encode_strings(["local-smoke-artifact"]),
        "offers": b64url(offer),
        "terms": "local-smoke-v1",
        "issuedAt": instant(now - dt.timedelta(seconds=1)),
        "expiresAt": instant(now + dt.timedelta(minutes=5)),
        "nonce": source_request_id,
        "audiences": "",
    })
    header = b64url(key_id.encode("utf-8"))
    body = b64url(claims)
    signing_input = f"{header}.{body}".encode("ascii")
    openssl = openssl_binary()
    with tempfile.TemporaryDirectory(prefix="marketing-award-") as directory:
        key_der = pathlib.Path(directory) / "offer-key.der"
        key_pem = pathlib.Path(directory) / "offer-key.pem"
        message = pathlib.Path(directory) / "message.bin"
        key_der.write_bytes(base64.b64decode(private_key))
        message.write_bytes(signing_input)
        subprocess.run([openssl, "pkey", "-inform", "DER", "-in", str(key_der),
                        "-out", str(key_pem)], check=True, capture_output=True)
        signed = subprocess.run([openssl, "pkeyutl", "-sign", "-rawin", "-inkey",
                                 str(key_pem), "-in", str(message)], check=True,
                                capture_output=True).stdout
    return f"{header}.{body}.{b64url(signed)}"


class ApiProblem(RuntimeError):
    def __init__(self, status: int, body: object):
        self.status = status
        self.body = body
        super().__init__(f"HTTP {status}: {json.dumps(body, ensure_ascii=False)}")


def call(base_url: str, tenant: str, method: str, path: str,
         body: object | None = None, idempotency_key: str | None = None) -> object:
    headers = {"Accept": "application/json"}
    token = setting("MARKETING_ACCESS_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    else:
        headers.update({
            "X-Dev-Tenant-Id": tenant,
            "X-Dev-Actor-Id": "local-award-smoke",
            "X-Dev-Organization-Ids": "*",
            "X-Dev-Shop-Ids": "*",
            "X-Dev-Permissions": "*",
        })
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body, separators=(",", ":")).encode("utf-8")
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(base_url + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            payload = response.read()
            return json.loads(payload) if payload else {}
    except urllib.error.HTTPError as failure:
        payload = failure.read().decode("utf-8", errors="replace")
        try:
            problem: object = json.loads(payload)
        except json.JSONDecodeError:
            problem = payload
        raise ApiProblem(failure.code, problem) from failure


def funding_container() -> str:
    configured = setting("MARKETING_BENEFIT_FUNDING_CONTAINER")
    if configured:
        return configured
    discovered = subprocess.run([
        "docker", "ps",
        "--filter", "label=com.docker.compose.service=benefit-funding-service",
        "--format", "{{.Names}}",
    ], check=True, capture_output=True, text=True).stdout.splitlines()
    if len(discovered) != 1:
        raise SystemExit(
            "expected one running benefit-funding-service container; "
            "set MARKETING_BENEFIT_FUNDING_CONTAINER explicitly")
    return discovered[0]


def call_funding_internal(container: str, tenant: str, path: str,
                          body: object, idempotency_key: str) -> object:
    encoded = json.dumps(body, separators=(",", ":")).encode("utf-8")
    completed = subprocess.run([
        "docker", "exec", "-i", container,
        "curl", "-sS", "-X", "POST",
        "-H", "Accept: application/json",
        "-H", "Content-Type: application/json",
        "-H", f"Idempotency-Key: {idempotency_key}",
        "-H", f"X-Dev-Tenant-Id: {tenant}",
        "-H", "X-Dev-Actor-Id: local-award-smoke",
        "-H", "X-Dev-Organization-Ids: *",
        "-H", "X-Dev-Shop-Ids: *",
        "-H", "X-Dev-Permissions: *",
        "--data-binary", "@-",
        "-w", "\n__STATUS__:%{http_code}",
        f"http://127.0.0.1:8085{path}",
    ], input=encoded, check=True, capture_output=True)
    payload, marker, raw_status = completed.stdout.rpartition(b"\n__STATUS__:")
    if not marker:
        raise SystemExit("benefit-funding-service response did not include an HTTP status")
    parsed: object
    try:
        parsed = json.loads(payload) if payload else {}
    except json.JSONDecodeError:
        parsed = payload.decode("utf-8", errors="replace")
    status = int(raw_status)
    if status >= 400:
        raise ApiProblem(status, parsed)
    return parsed


def ensure_active_benefit(base_url: str, tenant: str, benefit_id: str,
                          sku_id: str, benefit_type: str) -> int:
    try:
        current = call(base_url, tenant, "GET", f"/api/v1/benefits/{benefit_id}")
        assert isinstance(current, dict)
        if (current.get("status") == "ACTIVE" and current.get("benefitSkuId") == sku_id
                and (current.get("policy") or {}).get("type") == benefit_type):
            return int(current["version"])
    except ApiProblem as problem:
        if problem.status != 404:
            raise
    stored = call(base_url, tenant, "PUT", f"/api/v1/benefits/{benefit_id}", {
        "name": "Local federation AwardIntent smoke",
        "status": "ACTIVE",
        "resourceKey": "",
        "benefitSkuId": sku_id,
        "policy": {"type": benefit_type, "source": "local-smoke"},
    }, f"local-bind-{tenant}-{benefit_id}-{sku_id}-{benefit_type}")
    assert isinstance(stored, dict)
    return int(stored["version"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=setting("MARKETING_API_BASE_URL", "http://localhost:18080"))
    parser.add_argument("--tenant", default="benefit-center")
    parser.add_argument("--campaign-id", default=DEFAULT_CAMPAIGN)
    parser.add_argument("--benefit-id", default="local-cash-1001")
    parser.add_argument("--sku-id", default="1001")
    parser.add_argument("--benefit-type", default="CASH")
    parser.add_argument("--subject", default="local-federation-subject")
    parser.add_argument("--source-request-id", default=f"local-award-{int(time.time())}")
    args = parser.parse_args()

    key_id = setting("OFFER_SIGNING_KEY_ID")
    private_key = setting("OFFER_SIGNING_PRIVATE_KEY_BASE64")
    if not key_id or not private_key:
        raise SystemExit("OFFER_SIGNING_KEY_ID/OFFER_SIGNING_PRIVATE_KEY_BASE64 are required in local .env")
    version = ensure_active_benefit(args.base_url, args.tenant, args.benefit_id,
                                    args.sku_id, args.benefit_type)
    reference = f"{args.benefit_id}@{version}"
    cart_digest = "sha256:" + hashlib.sha256(b"local-award-intent-smoke-cart").hexdigest()
    token = offer_token(key_id, private_key, args.tenant, reference, args.subject,
                        cart_digest, args.source_request_id)
    created = call_funding_internal(funding_container(), args.tenant,
                                    "/internal/v1/award-intents", {
        "sourceRequestId": args.source_request_id,
        "campaignId": args.campaign_id,
        "definitionVersion": 1,
        "subjectRef": args.subject,
        "offerToken": token,
        "cartDigest": cart_digest,
    }, args.source_request_id)
    assert isinstance(created, dict)
    if created.get("status") == "RISK_BLOCKED":
        raise SystemExit(f"risk blocked smoke intent: {created.get('riskAction')} {created.get('riskReason')}")
    listed = call(args.base_url, args.tenant, "GET",
                  f"/api/v1/award-intents?campaignId={args.campaign_id}")
    if not isinstance(listed, list) or not any(
            row.get("sourceRequestId") == args.source_request_id for row in listed):
        raise SystemExit("AwardIntent was accepted but is absent from the tenant-scoped read model")
    print(json.dumps({
        "tenantId": args.tenant,
        "campaignId": args.campaign_id,
        "sourceRequestId": args.source_request_id,
        "benefitDefinitionVersion": reference,
        "status": created.get("status"),
        "deliveryResult": created.get("deliveryResult"),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
