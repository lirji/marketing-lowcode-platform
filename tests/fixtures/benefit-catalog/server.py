#!/usr/bin/env python3
"""CI-only catalog contract fixture; exposes no fulfillment or mutation endpoint."""

import argparse
import hmac
import json
import os
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

CATALOG_PATH = "/internal/v1/catalog/skus"
FIXTURE_TENANT = "retail-cn"


def create_server(address: tuple[str, int], token: str) -> HTTPServer:
    """Only the random CI machine credential may read this synthetic tenant."""
    if not token:
        raise ValueError("catalog fixture requires a nonempty machine token")
    skus = json.loads(Path(__file__).with_name("skus.json").read_text())

    class CatalogHandler(BaseHTTPRequestHandler):
        def respond(self, status: HTTPStatus, value: object) -> None:
            body = json.dumps(value).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:
            route = urlsplit(self.path)
            if route.path != CATALOG_PATH:
                self.respond(HTTPStatus.NOT_FOUND, {"code": "CI_FIXTURE_ROUTE_UNKNOWN"})
                return
            if not hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {token}"):
                self.respond(HTTPStatus.UNAUTHORIZED, {"code": "CI_FIXTURE_AUTH_REQUIRED"})
                return
            if self.headers.get("X-Tenant-Id") != FIXTURE_TENANT:
                self.respond(HTTPStatus.FORBIDDEN, {"code": "BENEFIT_CATALOG_TENANT_MISMATCH"})
                return
            query = parse_qs(route.query)
            try:
                limit = int(query.get("limit", ["100"])[0])
                if not 1 <= limit <= 1000:
                    raise ValueError("invalid page size")
            except ValueError:
                self.respond(HTTPStatus.BAD_REQUEST, {"code": "CI_FIXTURE_LIMIT_INVALID"})
                return
            status = query.get("status", ["ACTIVE"])[0]
            after = query.get("afterSkuId", [""])[0]
            self.respond(HTTPStatus.OK, [sku for sku in skus
                                        if sku["status"] == status and sku["skuId"] > after][:limit])

    server = HTTPServer(address, CatalogHandler)
    server.timeout = 5
    return server


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18183)
    args = parser.parse_args()
    with create_server((args.bind, args.port), os.environ.get("BENEFIT_CENTER_BEARER_TOKEN", "")) as server:
        print(f"CI catalog fixture listening on {args.bind}:{args.port}; no fulfillment", flush=True)
        server.serve_forever()
