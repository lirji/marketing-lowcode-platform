"""Exercise the CI dependency boundary and redact real diagnostic outputs."""

import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


catalog = load_module("ci_catalog", "tests/fixtures/benefit-catalog/server.py")
redactor = load_module("ci_redactor", "scripts/redact-ci-artifacts.py")


class CatalogFixtureTest(unittest.TestCase):
    def setUp(self):
        self.token = "isolated-test-machine-token"
        self.server = catalog.create_server(("127.0.0.1", 0), self.token)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)

    def request(self, path="?status=ACTIVE", tenant="retail-cn", token=None, method="GET"):
        headers = {"X-Tenant-Id": tenant, "Authorization": f"Bearer {self.token if token is None else token}"}
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.server.server_port}{catalog.CATALOG_PATH}{path}", headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def test_active_catalog_and_cursor_follow_the_contract(self):
        status, rows = self.request()
        self.assertEqual(200, status)
        self.assertEqual("CI-CASH-10", rows[0]["skuId"])
        self.assertEqual((200, []), self.request("?status=ACTIVE&afterSkuId=CI-CASH-10"))
        self.assertEqual((200, []), self.request("?status=RETIRED"))
        self.assertEqual(400, self.request("?limit=-1")[0])

    def test_credentials_tenant_and_writes_are_rejected(self):
        self.assertEqual(401, self.request(token="invalid")[0])
        self.assertEqual(403, self.request(tenant="other-tenant")[0])
        self.assertEqual(501, self.request(method="POST")[0])
        with self.assertRaises(ValueError):
            catalog.create_server(("127.0.0.1", 0), "")


class ArtifactRedactionTest(unittest.TestCase):
    def test_private_keys_passwords_and_tokens_are_removed_from_logs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "artifacts"
            artifact.mkdir()
            env = root / ".env"
            env.write_text('MYSQL_ROOT_PASSWORD="fake-password"\nSIGNING_PRIVATE_KEY_BASE64=fake-private\n')
            log = artifact / "compose.log"
            log.write_text("fake-password fake-private machine-token healthy")
            redactor.redact(artifact, [env], {"BEARER_TOKEN": "machine-token"})
            self.assertEqual("[REDACTED] [REDACTED] [REDACTED] healthy", log.read_text())
