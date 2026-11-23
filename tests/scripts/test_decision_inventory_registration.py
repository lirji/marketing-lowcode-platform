import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/register-decision-inventory.py"
SPEC = importlib.util.spec_from_file_location("decision_inventory_registration", SCRIPT)
registration = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(registration)


class RegistrationTest(unittest.TestCase):
    position = {"sequence": 5, "digest": "sha256:" + "a" * 64}

    def exercise(self, responses):
        with patch.object(registration, "request_position", side_effect=responses) as call:
            registration.register("https://decision.example", "test", b"{}", self.position)
            self.assertEqual(["PUT", "GET"], [args.args[2] for args in call.call_args_list])
            self.assertEqual(self.position["digest"], call.call_args_list[0].args[4])

    def test_success_confirms_current_position(self):
        self.exercise([self.position, self.position])

    def test_unknown_commit_reads_original_position(self):
        self.exercise([URLError("response lost"), self.position])
        self.exercise([HTTPError("https://decision.example", 503, "timeout", {}, None), self.position])

    def test_different_committed_position_fails_without_reissuing(self):
        with self.assertRaises(ValueError):
            self.exercise([TimeoutError(), {"sequence": 6, "digest": self.position["digest"]}])

    def test_rejection_is_not_reinterpreted_as_success(self):
        with patch.object(registration, "request_position", side_effect=HTTPError(
                "https://decision.example", 409, "stale", {}, None)) as call:
            with self.assertRaises(HTTPError):
                registration.register("https://decision.example", "test", b"{}", self.position)
            self.assertEqual(1, call.call_count)
