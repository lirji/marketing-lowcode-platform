#!/usr/bin/env python3
"""Black-box R1 acceptance across the gateway and all three Flink pipelines."""

from __future__ import annotations

import hashlib
import json
import os
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Callable


GATEWAY = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8080").rstrip("/")
TENANT = os.environ.get("ACCEPTANCE_TENANT_ID", "retail-cn")
ORGANIZATION = os.environ.get("ACCEPTANCE_ORGANIZATION_ID", "retail-business")
SHOP = os.environ.get("ACCEPTANCE_SHOP_ID", "all-shops")
RELEASE_KEY_ID = os.environ.get("RELEASE_SIGNING_KEY_ID", "release-local-1")
AUDIENCE_ID = os.environ.get("ACCEPTANCE_AUDIENCE_ID", "r1-qualified-audience")
SUFFIX = uuid.uuid4().hex[:12]


class ApiError(RuntimeError):
    def __init__(self, status: int, method: str, path: str, body: str):
        super().__init__(f"{method} {path} returned HTTP {status}: {body[:2000]}")
        self.status = status
        self.body = body


def instant(seconds: int = 0) -> str:
    value = datetime.now(timezone.utc) + timedelta(seconds=seconds)
    return value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def call(
    method: str,
    path: str,
    body: Any | None = None,
    *,
    actor: str = "r1-acceptance",
    idempotency_key: str | None = None,
) -> Any:
    encoded = None if body is None else json.dumps(
        body, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    headers = {
        "Accept": "application/json",
        "X-Dev-Tenant-Id": TENANT,
        "X-Dev-Organization-Ids": ORGANIZATION,
        "X-Dev-Shop-Ids": SHOP,
        "X-Dev-Actor-Id": actor,
        "X-Dev-Permissions": "*",
    }
    if encoded is not None:
        headers["Content-Type"] = "application/json"
    if idempotency_key is not None:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(
        GATEWAY + path, data=encoded, headers=headers, method=method
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as failure:
        payload = failure.read().decode("utf-8", errors="replace")
        raise ApiError(failure.code, method, path, payload) from failure
    if not payload:
        return None
    try:
        return json.loads(payload)
    except json.JSONDecodeError as failure:
        raise RuntimeError(f"{method} {path} returned non-JSON: {payload[:2000]}") from failure


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def retry(description: str, operation: Callable[[], Any], timeout: float = 90.0) -> Any:
    deadline = time.monotonic() + timeout
    last_error: BaseException | None = None
    while time.monotonic() < deadline:
        try:
            value = operation()
            if value is not None:
                return value
        except (ApiError, AssertionError) as failure:
            last_error = failure
        time.sleep(0.5)
    detail = f": {last_error}" if last_error is not None else ""
    raise AssertionError(f"timed out waiting for {description}{detail}")


def step(message: str) -> None:
    print(f"acceptance: {message}", flush=True)


def campaign(name: str) -> dict[str, Any]:
    command_scope = hashlib.sha256(name.encode("utf-8")).hexdigest()[:12]
    return call(
        "POST",
        "/api/v1/campaigns",
        {
            "name": name,
            "objective": "R1 production acceptance",
            "organizationId": ORGANIZATION,
            "shopId": SHOP,
        },
        actor=f"r1-author-{SUFFIX}",
        idempotency_key=f"campaign-{SUFFIX}-{command_scope}",
    )


def approve_graph(campaign_id: str, graph: dict[str, Any], simulate: bool) -> tuple[int, str, str]:
    definition = call(
        "POST",
        "/api/v1/definitions",
        {"campaignId": campaign_id, "graph": graph},
        actor=f"r1-author-{SUFFIX}",
        idempotency_key=f"definition-{SUFFIX}-{graph['definitionId']}",
    )
    version = int(definition["version"])
    definition_id = graph["definitionId"]
    validation = call(
        "POST",
        f"/api/v1/definitions/{definition_id}/versions/{version}:validate",
        {},
        actor=f"r1-author-{SUFFIX}",
        idempotency_key=f"validate-{SUFFIX}-{definition_id}",
    )
    require(validation["valid"], f"definition validation failed: {validation['issues']}")
    require(
        validation["semanticHash"] == definition["semanticHash"],
        "control-plane semantic hash changed during validation",
    )
    if simulate:
        simulation = call(
            "POST",
            f"/api/v1/definitions/{definition_id}/versions/{version}:simulate",
            {"amountMinor": "10000"},
            actor=f"r1-author-{SUFFIX}",
        )
        require(simulation["discountMinor"] == 1000, "offer simulation is not deterministic")
    submitted = call(
        "POST",
        f"/api/v1/definitions/{definition_id}/versions/{version}:submit",
        {},
        actor=f"r1-submitter-{SUFFIX}",
        idempotency_key=f"submit-{SUFFIX}-{definition_id}",
    )
    case_id = submitted["caseId"]
    approved = submitted
    for role in sorted(submitted["requiredRoles"]):
        approved = call(
            "POST",
            f"/api/v1/approvals/{case_id}/decisions",
            {"role": role},
            actor=f"r1-approver-{role.lower()}-{SUFFIX}",
            idempotency_key=f"approve-{SUFFIX}-{definition_id}-{role.lower()}",
        )
    require(approved["status"] == "APPROVED", "multi-role approval did not close")
    terms = call(
        "GET", f"/api/v1/definitions/{definition_id}/versions/{version}/terms"
    )
    require(terms["contentHash"].startswith("sha256:"), "terms were not frozen")
    return version, case_id, definition["semanticHash"]


def compile_graph(
    graph: dict[str, Any], version: int, format_name: str, semantic_hash: str
) -> tuple[dict[str, Any], dict[str, Any]]:
    report = call(
        "POST",
        "/api/v1/compile",
        {
            "definitionId": graph["definitionId"],
            "definitionVersion": version,
            "format": format_name,
            "namespace": "main",
            "modelName": graph["definitionId"],
            "source": None,
            "graph": graph,
        },
    )
    require(report["valid"], f"compiler rejected graph: {report['messages']}")
    artifact = call("GET", f"/api/v1/artifacts/{report['artifactId']}")
    require(artifact["sourceDigest"] == semantic_hash, "artifact provenance is not graph-bound")
    reference = {
        "artifactId": artifact["artifactId"],
        "type": artifact["type"],
        "uri": f"compiler://{artifact['artifactId']}",
        "checksum": artifact["checksum"],
        "sourceDigest": artifact["sourceDigest"],
        "signatureKeyId": artifact["signatureKeyId"],
        "signature": artifact["signature"],
        "abi": artifact["abi"],
        "definitionId": artifact["definitionId"],
        "definitionVersion": artifact["definitionVersion"],
    }
    return artifact, reference


def release(
    definition_id: str,
    version: int,
    case_id: str,
    artifact: dict[str, Any],
    reference: dict[str, Any],
    runtime: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    staged = call(
        "POST",
        "/api/v1/releases",
        {
            "definitionId": definition_id,
            "definitionVersion": version,
            "environment": "local",
            "cell": "cell-a",
            "runtime": runtime,
            "namespace": "main",
            "artifacts": [reference],
            "schemaVersions": {"terms": "terms-v1"},
            "canaryBasisPoints": 0,
            "activationAt": instant(-1),
            "approvalCaseIds": [case_id],
        },
        actor=f"r1-release-{SUFFIX}",
        idempotency_key=f"stage-{SUFFIX}-{runtime}",
    )
    require(staged["state"] == "STAGED", f"{runtime} release was not staged")
    manifest = staged["manifest"]
    if runtime == "decision":
        warmed = call(
            "PUT",
            "/api/v1/decisions/runtime/manifest",
            {
                "keyId": RELEASE_KEY_ID,
                "manifest": manifest,
                "policyArtifactBase64": artifact["payload"],
            },
            actor=f"r1-runtime-{SUFFIX}",
        )
    else:
        warmed = call(
            "PUT",
            "/api/v1/journey-runtime/manifest",
            {
                "releaseKeyId": RELEASE_KEY_ID,
                "manifest": manifest,
                "artifactBase64": artifact["payload"],
            },
            actor=f"r1-runtime-{SUFFIX}",
        )
    acknowledged = call(
        "POST",
        f"/api/v1/releases/{manifest['manifestId']}:ack",
        warmed["ack"],
        actor=f"r1-runtime-{SUFFIX}",
        idempotency_key=f"ack-{SUFFIX}-{runtime}",
    )
    require(acknowledged["readyReplicas"] >= 1, f"{runtime} readiness ACK was not counted")
    active = call(
        "POST",
        f"/api/v1/releases/{manifest['manifestId']}:activate",
        {},
        actor=f"r1-release-{SUFFIX}",
        idempotency_key=f"activate-{SUFFIX}-{runtime}",
    )
    require(active["state"] == "ACTIVE", f"{runtime} release was not activated")
    directive = active["activationDirective"]
    endpoint = (
        "/api/v1/decisions/runtime/activation"
        if runtime == "decision"
        else "/api/v1/journey-runtime/activation"
    )
    activated_runtime = call("PUT", endpoint, directive, actor=f"r1-runtime-{SUFFIX}")
    require(
        int(activated_runtime["generation"]) == int(manifest["generation"]),
        f"{runtime} runtime pointer did not advance",
    )
    desired_query = urllib.parse.urlencode(
        {
            "environment": "local",
            "cell": "cell-a",
            "runtime": runtime,
            "namespace": "main",
        }
    )
    desired = call("GET", f"/api/v1/releases/desired?{desired_query}")
    require(
        desired["manifest"]["manifestId"] == manifest["manifestId"],
        f"{runtime} desired-state read is inconsistent",
    )
    return manifest, directive


def cart_digest(cart: dict[str, Any]) -> str:
    canonical = cart["currency"] + "\n"
    for line in sorted(cart["lines"], key=lambda item: item["lineId"]):
        fields = [
            line["lineId"],
            line["skuId"],
            line["shopId"],
            line.get("categoryId", ""),
            line.get("brandId", ""),
            str(line["quantity"]),
            str(line["unitPrice"]["minorUnits"]),
            str(line["floorUnitPrice"]["minorUnits"]),
            str(line["shippingLine"]).lower(),
        ]
        canonical += "|".join(unicodedata.normalize("NFC", str(item)) for item in fields) + "\n"
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def dashboard(from_time: str, to_time: str) -> dict[str, Any]:
    query = urllib.parse.urlencode({"from": from_time, "to": to_time})
    return call("GET", f"/api/v1/measurements/dashboard?{query}")


def main() -> None:
    step("registering governed audience and authenticated event source")
    field_id = f"r1-score-{SUFFIX}"
    call(
        "POST",
        "/api/v1/fields",
        {
            "fieldId": field_id,
            "valueType": "DECIMAL",
            "owner": "crm-profile",
            "provenance": "r1-acceptance",
            "classification": "INTERNAL",
            "allowedUses": ["ELIGIBILITY"],
            "maxAgeSeconds": 3600,
            "nullPolicy": "NO_MATCH",
            "missingPolicy": "NO_MATCH",
            "retentionDays": 30,
        },
        idempotency_key=f"field-{SUFFIX}",
    )
    segment = call(
        "POST",
        "/api/v1/audiences",
        {
            "segmentId": AUDIENCE_ID,
            "name": "R1 qualified shoppers",
            "rule": {
                "match": "ALL",
                "conditions": [{"fieldId": field_id, "operator": "GTE", "value": "80"}],
            },
        },
        idempotency_key=f"audience-{SUFFIX}",
    )
    subject = f"subject-{SUFFIX}"
    preview = call(
        "POST",
        f"/api/v1/audiences/{AUDIENCE_ID}/versions/{segment['version']}:preview",
        [
            {"subjectToken": subject, "attributes": {field_id: "100"}},
            {"subjectToken": f"unqualified-{SUFFIX}", "attributes": {field_id: "10"}},
        ],
    )
    require(preview["estimatedCount"] == 1, "audience preview is incorrect")
    source_id = f"r1-source-{SUFFIX}"
    call(
        "POST",
        "/api/v1/events/sources",
        {
            "sourceId": source_id,
            "sourceUri": f"urn:marketing:r1:{SUFFIX}",
            "allowedTypes": ["PROFILE_CHANGED", "JOURNEY_SIGNAL", "MARKETING_FACT"],
            "schemaVersions": ["1.0.0"],
            "maxLatenessSeconds": 3600,
        },
        idempotency_key=f"event-source-{SUFFIX}",
    )

    step("authoring, approving, compiling, and activating offer policy")
    benefit_id = f"coupon-{SUFFIX}"
    funder_id = f"platform-{SUFFIX}"
    offer_definition = f"offer-r1-{SUFFIX}"
    offer_campaign = campaign(f"R1 offer {SUFFIX}")
    offer_graph = {
        "definitionId": offer_definition,
        "dialect": "OFFER_DECISION_DAG",
        "dialectVersion": "1.0.0",
        "nodes": [
            {"id": "start", "stableTypeId": "offer.start", "semanticVersion": "1.0.0", "config": {}},
            {
                "id": "discount",
                "stableTypeId": "offer.fixed",
                "semanticVersion": "1.0.0",
                "config": {
                    "offerId": f"fixed-{SUFFIX}",
                    "benefitDefinitionVersion": benefit_id,
                    "amountMinor": "1000",
                    "thresholdMinor": "5000",
                    "validFrom": "2025-01-01T00:00:00Z",
                    "validTo": "2035-01-01T00:00:00Z",
                    "channels": "APP",
                    "shopIds": SHOP,
                    "fundingRules": f"PLATFORM|{funder_id}|10000",
                    "requiredAudienceSnapshotId": AUDIENCE_ID,
                    "reasonCode": "R1_ACCEPTANCE_OFFER",
                },
            },
            {"id": "end", "stableTypeId": "offer.end", "semanticVersion": "1.0.0", "config": {}},
        ],
        "edges": [
            {"id": "offer-e1", "sourceNodeId": "start", "sourcePort": "next", "targetNodeId": "discount", "targetPort": "in"},
            {"id": "offer-e2", "sourceNodeId": "discount", "sourcePort": "next", "targetNodeId": "end", "targetPort": "in"},
        ],
        "variables": {},
        "annotations": {"terms": "满50元减10元；以最终结算页为准"},
    }
    offer_version, offer_case, offer_hash = approve_graph(
        offer_campaign["id"], offer_graph, True
    )
    offer_artifact, offer_reference = compile_graph(
        offer_graph, offer_version, "OFFER_POLICY", offer_hash
    )
    offer_manifest, _ = release(
        offer_definition,
        offer_version,
        offer_case,
        offer_artifact,
        offer_reference,
        "decision",
    )

    step("projecting audience membership through event outbox, Kafka, and Flink")
    profile_event = {
        "eventId": f"profile-{SUFFIX}",
        "sourceId": source_id,
        "eventType": "PROFILE_CHANGED",
        "businessKey": subject,
        "subjectToken": subject,
        "occurredAt": instant(),
        "schemaVersion": "1.0.0",
        "data": {"profileVersion": 1, "segmentIds": [AUDIENCE_ID]},
    }
    profile_receipt = call("POST", "/api/v1/events", profile_event)
    require(profile_receipt["status"] == "ACCEPTED", "profile event was not accepted")
    duplicate_profile = call("POST", "/api/v1/events", profile_event)
    require(duplicate_profile["status"] == "DUPLICATE", "event replay was not idempotent")

    cart = {
        "currency": "CNY",
        "lines": [
            {
                "lineId": "line-1",
                "skuId": f"sku-{SUFFIX}",
                "shopId": SHOP,
                "categoryId": "home",
                "brandId": "r1-brand",
                "quantity": 1,
                "unitPrice": {"currency": "CNY", "minorUnits": 10000},
                "floorUnitPrice": {"currency": "CNY", "minorUnits": 1000},
                "shippingLine": False,
            }
        ],
    }
    order_id = f"order-{SUFFIX}"
    attempt = 0

    def eligible_decision() -> tuple[dict[str, Any], dict[str, Any]] | None:
        nonlocal attempt
        attempt += 1
        request = {
            "requestId": f"decision-{SUFFIX}-{attempt}",
            "idempotencyKey": f"decision-idem-{SUFFIX}-{attempt}",
            "organizationId": ORGANIZATION,
            "orderId": order_id,
            "subjectToken": subject,
            "channel": "APP",
            "occurredAt": instant(),
            "cart": cart,
            "audienceSnapshotIds": [AUDIENCE_ID],
        }
        response = call("POST", "/api/v1/decisions:evaluate", request)
        if not response["pricing"]["appliedOffers"]:
            return None
        return response, request

    decision, decision_request = retry("audience-qualified decision", eligible_decision)
    require(decision["generation"] == offer_manifest["generation"], "decision used a stale generation")
    require(decision["pricing"]["payable"]["minorUnits"] == 9000, "decision price is incorrect")
    require(bool(decision["offerToken"]), "eligible decision did not issue a signed offer token")
    replayed_decision = call("POST", "/api/v1/decisions:evaluate", decision_request)
    require(replayed_decision == decision, "decision idempotency did not return the original response")

    step("reserving and settling fenced inventory and platform budget")
    inventory_key = f"INVENTORY:{benefit_id}"
    budget_key = f"BUDGET:PLATFORM:{funder_id}:CNY"
    call(
        "POST",
        "/api/v1/funding/accounts",
        {"resourceKey": inventory_key, "type": "INVENTORY", "currency": "UNIT", "authorized": 100, "fencingEpoch": 1},
        idempotency_key=f"funding-inventory-{SUFFIX}",
    )
    call(
        "POST",
        "/api/v1/funding/accounts",
        {"resourceKey": budget_key, "type": "BUDGET", "currency": "CNY", "authorized": 100000, "fencingEpoch": 1},
        idempotency_key=f"funding-budget-{SUFFIX}",
    )
    digest = cart_digest(cart)
    fences = {inventory_key: 1, budget_key: 1}
    reserve_request = {
        "offerToken": decision["offerToken"],
        "cartDigest": digest,
        "orderId": order_id,
        "expectedFencingEpochs": fences,
    }
    reserved = call(
        "POST",
        "/api/v1/promotion-applications",
        reserve_request,
        idempotency_key=f"reserve-{SUFFIX}",
    )
    require(reserved["state"] == "RESERVED", "promotion was not reserved")
    require(len(reserved["items"]) == 2, "reservation did not conserve both resources")
    reserve_replay = call(
        "POST",
        "/api/v1/promotion-applications",
        reserve_request,
        idempotency_key=f"reserve-{SUFFIX}",
    )
    require(reserve_replay == reserved, "reservation idempotency failed")
    confirmed = call(
        "POST",
        f"/api/v1/promotion-applications/{reserved['applicationId']}:confirm",
        {"expectedFencingEpochs": fences},
        idempotency_key=f"confirm-{SUFFIX}",
    )
    require(confirmed["state"] == "CONFIRMED", "promotion was not confirmed")
    reconciliation = call("GET", "/api/v1/funding/reconciliation")
    require(reconciliation["balanced"], f"funding ledger is unbalanced: {reconciliation['violations']}")

    step("recording idempotent measurement and decision trace")
    # Keep the observation window stable even on a cold CI worker where image
    # startup, Flink checkpoints, and the two governed releases can take minutes.
    measure_from = instant(-900)
    measure_to = instant(900)
    before_direct = dashboard(measure_from, measure_to)["counts"].get("CONVERSION", 0)
    fact = {
        "eventId": f"direct-conversion-{SUFFIX}",
        "type": "CONVERSION",
        "businessKey": order_id,
        "subjectToken": subject,
        "occurredAt": instant(),
        "ingestedAt": instant(),
        "schemaVersion": "1.0.0",
        "attributes": {"campaignId": offer_campaign["id"], "revenueMinor": "10000"},
        "correctionOf": "",
    }
    first_fact = call("POST", "/api/v1/measurements/facts", fact)
    second_fact = call("POST", "/api/v1/measurements/facts", fact)
    require(not first_fact["duplicate"] and second_fact["duplicate"], "fact idempotency failed")
    after_direct = dashboard(measure_from, measure_to)
    require(
        after_direct["counts"].get("CONVERSION", 0) == before_direct + 1,
        "direct measurement projection did not advance exactly once",
    )
    trace_request = {
        "traceId": f"trace-{SUFFIX}",
        "requestId": decision["requestId"],
        "orderId": order_id,
        "subjectToken": subject,
        "generation": decision["generation"],
        "durationMicros": decision["durationMicros"],
        "candidates": {item["offerId"]: item["outcome"] for item in decision["candidates"]},
        "pricing": decision["pricing"],
        "termsVersion": "terms-v1",
        "expiresAt": instant(3600),
        "legalHold": False,
    }
    call("POST", "/api/v1/traces", trace_request)
    trace = call("GET", f"/api/v1/traces/requests/{decision['requestId']}")
    require(trace["traceId"] == trace_request["traceId"], "decision trace is not queryable")

    step("authoring and activating stream-owned journey")
    journey_definition = f"journey-r1-{SUFFIX}"
    journey_campaign = campaign(f"R1 journey {SUFFIX}")
    template_id = f"journey-template-{SUFFIX}"
    journey_graph = {
        "definitionId": journey_definition,
        "dialect": "JOURNEY_STATE_MACHINE",
        "dialectVersion": "1.0.0",
        "nodes": [
            {"id": "trigger", "stableTypeId": "journey.trigger", "semanticVersion": "1.0.0", "config": {}},
            {
                "id": "send",
                "stableTypeId": "journey.send",
                "semanticVersion": "1.0.0",
                "config": {
                    "channel": "SMS",
                    "templateId": template_id,
                    "templateVersion": "1",
                    "campaignId": journey_campaign["id"],
                    "recipientToken": f"phone-token-{SUFFIX}",
                    "timezone": "UTC",
                    "personalized": "false",
                    "name": "R1 customer",
                },
            },
            {"id": "end", "stableTypeId": "journey.end", "semanticVersion": "1.0.0", "config": {}},
        ],
        "edges": [
            {"id": "journey-e1", "sourceNodeId": "trigger", "sourcePort": "next", "targetNodeId": "send", "targetPort": "in"},
            {"id": "journey-e2", "sourceNodeId": "send", "sourcePort": "next", "targetNodeId": "end", "targetPort": "in"},
        ],
        "variables": {"maxStepsPerSignal": "20", "maxIterations": "3", "stateTtlSeconds": "86400"},
        "annotations": {"terms": "R1 transactional journey"},
    }
    journey_version, journey_case, journey_hash = approve_graph(
        journey_campaign["id"], journey_graph, False
    )
    journey_artifact, journey_reference = compile_graph(
        journey_graph, journey_version, "JOURNEY_PLAN", journey_hash
    )
    journey_manifest, journey_directive = release(
        journey_definition,
        journey_version,
        journey_case,
        journey_artifact,
        journey_reference,
        "journey",
    )
    call(
        "PUT",
        "/api/v1/consents",
        {
            "subjectToken": subject,
            "channel": "SMS",
            "allowed": True,
            "minor": False,
            "personalizationAllowed": True,
            "source": "r1-acceptance",
            "effectiveAt": instant(-30),
        },
    )
    call(
        "PUT",
        "/api/v1/contacts/frequency-policies",
        {
            "campaignId": journey_campaign["id"],
            "channel": "SMS",
            "windowSeconds": 3600,
            "maxContacts": 5,
            "quietStart": "00:00:00",
            "quietEnd": "00:00:00",
        },
    )
    call(
        "POST",
        "/api/v1/templates",
        {
            "templateId": template_id,
            "version": 1,
            "channel": "SMS",
            "content": "R1 offer for {{name}}",
            "requiredVariables": ["name"],
        },
        idempotency_key=f"template-{SUFFIX}",
    )
    try:
        call(
            "POST",
            "/api/v1/enrollments",
            {
                "journeyId": journey_definition,
                "journeyVersion": journey_version,
                "subjectToken": subject,
                "triggerEventId": f"forbidden-direct-{SUFFIX}",
                "occurredAt": instant(),
            },
        )
        raise AssertionError("STREAM mode accepted a second direct journey writer")
    except ApiError as failure:
        require(failure.status == 409, f"unexpected direct journey rejection: {failure}")

    enrollment_id = f"enrollment-{SUFFIX}"
    journey_event = {
        "eventId": f"journey-start-{SUFFIX}",
        "sourceId": source_id,
        "eventType": "JOURNEY_SIGNAL",
        "businessKey": enrollment_id,
        "subjectToken": subject,
        "occurredAt": instant(),
        "schemaVersion": "1.0.0",
        "data": {
            "planReference": {
                "artifactId": journey_artifact["artifactId"],
                "generation": journey_manifest["generation"],
                "activationSequence": journey_directive["activationSequence"],
                "journeyId": journey_definition,
                "journeyVersion": journey_version,
            },
            "signal": {"type": "START", "attributes": {}},
        },
    }
    journey_receipt = call("POST", "/api/v1/events", journey_event)
    require(journey_receipt["status"] == "ACCEPTED", "journey signal was not accepted")

    def completed_enrollment() -> dict[str, Any] | None:
        value = call("GET", f"/api/v1/enrollments/{enrollment_id}")
        return value if value["status"] == "COMPLETED" else None

    retry("Flink-owned journey completion", completed_enrollment)
    command_id = hashlib.sha256(
        f"{TENANT}:{enrollment_id}:{journey_version}:send:1".encode("utf-8")
    ).hexdigest()

    def accepted_contact() -> dict[str, Any] | None:
        value = call("GET", f"/api/v1/contacts/{command_id}")
        return value if value["state"] == "ACCEPTED" else None

    contact = retry("journey SEND effect delivery", accepted_contact)

    step("projecting streamed measurement through Flink")
    before_stream = dashboard(measure_from, measure_to)["counts"].get("CONVERSION", 0)
    streamed_fact = {
        "eventId": f"stream-conversion-{SUFFIX}",
        "sourceId": source_id,
        "eventType": "MARKETING_FACT",
        "businessKey": f"stream-order-{SUFFIX}",
        "subjectToken": subject,
        "occurredAt": instant(),
        "schemaVersion": "1.0.0",
        "data": {
            "factType": "CONVERSION",
            "attributes": {"campaignId": offer_campaign["id"], "revenueMinor": "5000"},
        },
    }
    streamed_receipt = call("POST", "/api/v1/events", streamed_fact)
    require(streamed_receipt["status"] == "ACCEPTED", "streamed fact was not accepted")

    def streamed_projection() -> dict[str, Any] | None:
        value = dashboard(measure_from, measure_to)
        return value if value["counts"].get("CONVERSION", 0) >= before_stream + 1 else None

    retry("measurement Flink projection", streamed_projection)
    final_reconciliation = call("GET", "/api/v1/funding/reconciliation")
    require(final_reconciliation["balanced"], "final funding reconciliation is not balanced")

    print(
        json.dumps(
            {
                "status": "PASSED",
                "runId": SUFFIX,
                "offerManifestId": offer_manifest["manifestId"],
                "applicationId": confirmed["applicationId"],
                "journeyManifestId": journey_manifest["manifestId"],
                "enrollmentId": enrollment_id,
                "contactId": contact["contactId"],
            },
            ensure_ascii=False,
            sort_keys=True,
        ),
        flush=True,
    )


if __name__ == "__main__":
    try:
        main()
    except BaseException as failure:
        print(f"R1 acceptance failed: {failure}", file=sys.stderr, flush=True)
        raise
