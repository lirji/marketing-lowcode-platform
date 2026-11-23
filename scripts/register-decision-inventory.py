#!/usr/bin/env python3
"""Register a pipeline-signed Decision inventory and confirm its durable position."""
import json
from http import HTTPStatus
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

TIMEOUT_SECONDS = 15
MAX_POSITION_BYTES = 4096
MAX_INVENTORY_BYTES = 64 * 1024
API_PATH = "/api/v1/decisions/runtime/deployment-inventory"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request_position(url, token, method, body=None, digest=None):
    request = Request(url, method=method, data=body, headers={"Content-Type": "application/json"})
    if digest is not None:
        request.add_header("X-Inventory-Digest", digest)
    request.add_unredirected_header("Authorization", "Bearer " + token)
    with build_opener(NoRedirect()).open(request, timeout=TIMEOUT_SECONDS) as response:
        if response.status != HTTPStatus.OK:
            raise ValueError("unexpected Decision response")
        data = response.read(MAX_POSITION_BYTES + 1)
        if len(data) > MAX_POSITION_BYTES:
            raise ValueError("oversized Decision response")
        return json.loads(data)


def register(url, token, body, expected):
    try:
        recorded = request_position(url, token, "PUT", body, expected["digest"])
    except HTTPError as err:
        if err.code < HTTPStatus.INTERNAL_SERVER_ERROR:
            raise
    except (URLError, TimeoutError, ConnectionError) as err:
        print(f"PUT outcome unknown ({type(err).__name__}); checking recorded position", file=sys.stderr)
    else:
        if recorded != expected:
            raise ValueError("Decision returned a different inventory position")
    # A timeout may follow a successful commit. Confirm the original revision without minting a new one.
    if request_position(url, token, "GET") != expected:
        raise ValueError("the signed inventory is not the current deployment position")


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: register-decision-inventory.py SIGNED_JSON CONTENT_DIGEST_FILE")
    base = os.environ["DECISION_DEPLOYMENT_BASE_URL"]
    parts = urlsplit(base)
    if parts.scheme != "https" or not parts.hostname or parts.username is not None or parts.password is not None or parts.query or parts.fragment:
        sys.exit("a trusted HTTPS Decision origin is required")
    token = os.environ["DECISION_DEPLOYMENT_TOKEN"]
    if not token or any(c.isspace() for c in token):
        sys.exit("a pipeline access token is required")
    with Path(sys.argv[1]).open("rb") as stream:
        body = stream.read(MAX_INVENTORY_BYTES + 1)
    with Path(sys.argv[2]).open("rb") as stream:
        digest = stream.read(73).decode("ascii").strip()
        if stream.read(1):
            sys.exit("invalid inventory digest file")
    if len(body) > MAX_INVENTORY_BYTES or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        sys.exit("invalid inventory size or digest")
    sequence = json.loads(body)["sequence"]
    if type(sequence) is not int or sequence < 1:
        sys.exit("invalid inventory sequence")
    expected = {"sequence": sequence, "digest": digest}
    register(base.rstrip("/") + API_PATH, token, body, expected)
    print(json.dumps(expected))


if __name__ == "__main__":
    main()
