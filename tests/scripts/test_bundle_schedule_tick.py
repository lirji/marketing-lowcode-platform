import importlib.util
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "xxl-bundle-schedule-tick.py"
spec = importlib.util.spec_from_file_location("bundle_tick", SCRIPT)
handler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(handler)


class TickTests(unittest.TestCase):
    def test_rotating_identity_and_committed_tick_result(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(handler, "build_opener") as build:
            token = Path(directory) / "token"
            for credential in ("scheduled-one", "scheduled-two"):
                token.write_text(credential, encoding="ascii")
                response = io.BytesIO(b'{"claimed":true,"recorded":true}')
                response.status = 200
                build.return_value.open.return_value = response
                self.assertEqual({"claimed": True, "recorded": True}, handler.tick("https://control.test", token))
                request = build.return_value.open.call_args.args[0]
                self.assertEqual("POST", request.get_method())
                self.assertEqual("https://control.test/internal/v1/release-bundle-schedules:tick", request.full_url)
                self.assertEqual("Bearer " + credential, request.unredirected_hdrs["Authorization"])

    def test_invalid_destination_or_result_fails_the_job(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(handler, "build_opener") as build:
            token = Path(directory) / "token"
            token.write_text("test", encoding="ascii")
            with self.assertRaises(ValueError):
                handler.tick("http://control.test", token)
            build.assert_not_called()
            response = io.BytesIO(b'{"claimed":"true"}')
            response.status = 200
            build.return_value.open.return_value = response
            with self.assertRaises(ValueError):
                handler.tick("https://control.test", token)


if __name__ == "__main__":
    unittest.main()
