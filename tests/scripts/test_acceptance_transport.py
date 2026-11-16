"""验证网关不确定回执只重试有保护的请求，且保留原始业务字节。"""
import importlib.util
import json
from pathlib import Path
import threading
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SPEC = importlib.util.spec_from_file_location(
    "acceptance", Path(__file__).resolve().parents[2] / "scripts/acceptance-r1.py")
ACCEPTANCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ACCEPTANCE)


class AcceptanceTransportTest(unittest.TestCase):
    def setUp(self):
        self.requests = []
        self.statuses = [504, 200]
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def handle_request(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                fixture.requests.append((self.command, self.headers.get("Idempotency-Key"), body))
                status = fixture.statuses.pop(0)
                if status is None:
                    self.close_connection = True
                    return
                payload = json.dumps({"code": "FIXTURE", "accepted": status == 200}).encode()
                self.send_response(status)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            do_POST = handle_request
            do_GET = handle_request

            def log_message(self, *_args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.gateway = patch.object(ACCEPTANCE, "GATEWAY", f"http://127.0.0.1:{self.server.server_port}")
        self.sleep = patch.object(ACCEPTANCE.time, "sleep")
        self.gateway.start()
        self.sleep.start()

    def tearDown(self):
        self.gateway.stop()
        self.sleep.stop()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_keyed_write_retries_identical_bytes_and_key(self):
        result = ACCEPTANCE.call("POST", "/write", {"message": "验收", "amount": 100}, idempotency_key="original")
        self.assertTrue(result["accepted"])
        self.assertEqual(2, len(self.requests))
        self.assertEqual(self.requests[0], self.requests[1])
        self.assertEqual("original", self.requests[0][1])

    def test_unkeyed_write_does_not_repeat_after_timeout_response(self):
        with self.assertRaises(ACCEPTANCE.ApiError) as failure:
            ACCEPTANCE.call("POST", "/write", {"amount": 100})
        self.assertEqual(504, failure.exception.status)
        self.assertEqual(1, len(self.requests))

    def test_explicit_content_addressed_operation_can_replay(self):
        self.assertTrue(ACCEPTANCE.call("POST", "/compile", {"graph": "same"}, replay_safe=True)["accepted"])
        self.assertEqual(2, len(self.requests))
        self.assertEqual(self.requests[0], self.requests[1])

    def test_missing_real_catalog_fails_before_any_write(self):
        with patch.object(ACCEPTANCE, "call", return_value=[]) as api:
            with self.assertRaisesRegex(AssertionError, "no ACTIVE benefit SKU"):
                ACCEPTANCE.main()
        api.assert_called_once_with("GET", "/api/v1/benefit-skus?status=ACTIVE")

    def test_reads_retry_but_business_conflict_is_not_retried(self):
        self.assertTrue(ACCEPTANCE.call("GET", "/read")["accepted"])
        self.assertEqual(2, len(self.requests))
        self.requests.clear()
        self.statuses = [409]
        with self.assertRaises(ACCEPTANCE.ApiError):
            ACCEPTANCE.call("POST", "/write", {}, idempotency_key="original")
        self.assertEqual(1, len(self.requests))

    def test_retry_budget_is_bounded(self):
        self.statuses = [503, 503, 503]
        with self.assertRaises(ACCEPTANCE.ApiError):
            ACCEPTANCE.call("GET", "/read")
        self.assertEqual(3, len(self.requests))

    def test_connection_closed_before_response_only_retries_safe_requests(self):
        self.statuses = [None, 200]
        self.assertTrue(ACCEPTANCE.call("GET", "/read")["accepted"])
        self.assertEqual(2, len(self.requests))
        self.statuses = [None, 200]
        self.requests.clear()
        with self.assertRaises(ConnectionError):
            ACCEPTANCE.call("POST", "/write", {})
        self.assertEqual(1, len(self.requests))


if __name__ == "__main__":
    unittest.main()
