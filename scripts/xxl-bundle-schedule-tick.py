#!/usr/bin/env python3
"""XXL-JOB GLUE(Python3) handler for one tenant's durable bundle schedule queue."""
import json
from http import HTTPStatus
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


MAX_RESPONSE_BYTES = 512
MAX_TOKEN_BYTES = 16384


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def tick(origin, token_file):
    parsed = urlsplit(origin)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment or parsed.path not in ("", "/")):
        raise ValueError("expected a trusted HTTPS Control origin")
    # 执行器挂载短期 OIDC 令牌；每次触发重新读取，调度参数及日志不含令牌。
    with Path(token_file).open("r", encoding="ascii") as token:
        bearer = token.read(MAX_TOKEN_BYTES + 1)
    if len(bearer) > MAX_TOKEN_BYTES:
        raise ValueError("oversized scheduler identity file")
    bearer = bearer.strip()
    if not bearer or any(c.isspace() for c in bearer):
        raise ValueError("invalid scheduler identity file")
    req = Request(origin.rstrip("/") + "/internal/v1/release-bundle-schedules:tick", data=b"", method="POST")
    req.add_unredirected_header("Authorization", "Bearer " + bearer)
    try:
        with build_opener(NoRedirect()).open(req, timeout=45) as response:
            data = response.read(MAX_RESPONSE_BYTES + 1)
            if len(data) > MAX_RESPONSE_BYTES or response.status != HTTPStatus.OK:
                raise ValueError("invalid tick response")
    except HTTPError as exc:
        exc.close()
        raise
    result = json.loads(data)
    if not isinstance(result, dict) or any(type(result.get(k)) is not bool for k in ("claimed", "recorded")):
        raise ValueError("invalid tick result")
    return {k: result[k] for k in ("claimed", "recorded")}


if __name__ == "__main__":
    print(json.dumps(tick(os.environ["BUNDLE_SCHEDULER_CONTROL_ORIGIN"],
                          os.environ["BUNDLE_SCHEDULER_TOKEN_FILE"])))
