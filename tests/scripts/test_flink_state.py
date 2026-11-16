"""以本机 HTTP fixture 和隔离命令替身验证拒绝重提与明确恢复，不访问开发集群。"""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("flink_state", ROOT / "scripts/flink-state.py")
STATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STATE)


def running_jobs():
    return {"jobs": [{"name": name, "jid": str(index), "state": "RUNNING", "tasks": {"running": 4, "total": 4}}
                     for index, name in enumerate(sorted(STATE.EXPECTED_JOBS))]}


def checkpoints():
    return {"latest": {"completed": {"status": "COMPLETED", "latest_ack_timestamp": int(time.time() * 1000)}}}


class FlinkStateTest(unittest.TestCase):
    def test_requires_exact_job_identity_and_running_subtasks(self):
        jobs = running_jobs()
        self.assertEqual("healthy", STATE.assess(jobs, lambda _: checkpoints(), int(time.time() * 1000))[0])
        jobs["jobs"][0]["name"] = "unrelated-job"
        self.assertEqual("conflict", STATE.assess(jobs, lambda _: checkpoints(), 0)[0])
        jobs = running_jobs()
        jobs["jobs"][0]["state"] = "RESTARTING"
        self.assertEqual("waiting", STATE.assess(jobs, lambda _: checkpoints(), 0)[0])
        jobs = running_jobs()
        jobs["jobs"][0]["tasks"]["running"] = 3
        self.assertEqual("waiting", STATE.assess(jobs, lambda _: checkpoints(), 0)[0])

    def test_requires_completed_recent_checkpoint(self):
        self.assertEqual("waiting", STATE.assess(running_jobs(), lambda _: {"latest": {}}, 1000)[0])
        checkpoint = {"latest": {"completed": {"status": "COMPLETED", "trigger_timestamp": 1}}}
        self.assertEqual("waiting", STATE.assess(running_jobs(), lambda _: checkpoint, 200_000)[0])

    def test_restore_plan_is_complete_canonical_and_not_shared(self):
        plan = {key: f"file:///opt/flink/checkpoints/{key}/chk-1/_metadata" for key in STATE.RESTORE_VARIABLES}
        self.assertEqual(3, len(STATE.restore_environment(plan)))
        for invalid in [{"audience": plan["audience"]}, {**plan, "journey": plan["audience"]},
                        {**plan, "audience": "file:///opt/flink/checkpoints/../secret/_metadata"},
                        {**plan, "audience": "file:///opt/flink/checkpoints/$(touch bad)/_metadata"}]:
            with self.assertRaises(ValueError):
                STATE.restore_environment(invalid)


class SubmissionScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        for name in ["_lib.sh", "submit-flink-jobs.sh", "flink-state.py"]:
            shutil.copy2(ROOT / "scripts" / name, self.root / "scripts" / name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        docker = self.bin / "docker"
        docker.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
if 'find' in args: print(os.environ.get('FAKE_RETAINED',''))
elif 'test' in args: sys.exit(0 if os.environ.get('FAKE_METADATA','1') == '1' else 1)
elif 'run' in args: Path(os.environ['FAKE_SUBMITTED']).write_text(json.dumps({k:os.environ.get(k) for k in ['AUDIENCE_RESTORE_PATH','JOURNEY_RESTORE_PATH','MEASUREMENT_RESTORE_PATH']}))
else: sys.exit(2)
''')
        docker.chmod(0o755)
        self.submitted = self.root / "submitted.json"
        self.overview = {"jobs": []}
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                data = checkpoints() if self.path.endswith('/checkpoints') else (
                    running_jobs() if fixture.submitted.exists() else fixture.overview)
                body = json.dumps(data).encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.env = {**os.environ, 'PATH': str(self.bin) + os.pathsep + os.environ['PATH'],
                    'FLINK_URL': f'http://127.0.0.1:{self.server.server_port}',
                    'FAKE_SUBMITTED': str(self.submitted), 'DEV_INFRA_ROOT': str(self.root / 'infra'),
                    'DEV_INFRA_ENV_FILE': str(self.root / 'missing.env'), 'PLATFORM_PORTS_LOADER': str(self.root / 'missing.sh')}

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def run_script(self, *args):
        result = subprocess.run(['bash', str(self.root / 'scripts/submit-flink-jobs.sh'), *args],
                                env=self.env, text=True, capture_output=True, timeout=15)
        self.assertFalse((self.root / '.flink-submit.lock').exists())
        return result

    def test_healthy_jobs_never_submit(self):
        self.overview = running_jobs()
        self.assertEqual(0, self.run_script().returncode)
        self.assertFalse(self.submitted.exists())

    def test_unrelated_jobs_and_stopped_history_never_resubmit(self):
        self.overview = running_jobs()
        self.overview['jobs'][0]['name'] = 'foreign'
        self.assertNotEqual(0, self.run_script().returncode)
        self.overview = {'jobs': [{'name': 'old-job', 'state': 'FAILED'}]}
        self.assertNotEqual(0, self.run_script().returncode)
        self.assertFalse(self.submitted.exists())

    def test_retained_state_requires_explicit_recovery(self):
        self.env['FAKE_RETAINED'] = '/opt/flink/checkpoints/old/chk-1/_metadata'
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Refusing an empty-state restart', result.stderr)
        self.assertFalse(self.submitted.exists())

    def test_fresh_environment_submits_once_then_checks_checkpoints(self):
        self.assertEqual(0, self.run_script().returncode)
        self.assertEqual({'AUDIENCE_RESTORE_PATH': '', 'JOURNEY_RESTORE_PATH': '', 'MEASUREMENT_RESTORE_PATH': ''},
                         json.loads(self.submitted.read_text()))
        self.assertEqual(0, self.run_script().returncode)

    def test_explicit_recovery_passes_each_validated_metadata_path(self):
        plan = {key: f'file:///opt/flink/checkpoints/{key}/chk-1/_metadata' for key in STATE.RESTORE_VARIABLES}
        path = self.root / 'restore.json'
        path.write_text(json.dumps(plan))
        self.env['FAKE_METADATA'] = '0'
        self.assertNotEqual(0, self.run_script('--restore-plan', str(path)).returncode)
        self.assertFalse(self.submitted.exists())
        self.env['FAKE_METADATA'] = '1'
        self.overview = {'jobs': [{'state': 'FAILED', 'name': 'old-job'}]}
        result = self.run_script('--restore-plan', str(path))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({STATE.RESTORE_VARIABLES[key]: value for key, value in plan.items()},
                         json.loads(self.submitted.read_text()))


if __name__ == '__main__':
    unittest.main()
