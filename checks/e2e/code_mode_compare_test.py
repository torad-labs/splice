#!/usr/bin/env python3
"""Focused receipt tests for the isolated code-mode comparison runner."""
import argparse
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from code_mode_compare import run
from code_mode_probe import Budget


class Process:
    def __init__(self, returncode=None, terminate_error=None):
        self.returncode = returncode
        self.terminate_error = terminate_error
        self.terminated = False

    def poll(self):
        return self.returncode

    def terminate(self):
        self.terminated = True
        if self.terminate_error:
            raise self.terminate_error
        self.returncode = -15

    def wait(self, timeout):
        del timeout
        return self.returncode

    def kill(self):
        self.returncode = -9


class CompareReceiptTests(unittest.TestCase):
    def make_args(self, directory):
        root = Path(directory)
        artifact = root / "app.jar"
        auth = root / "auth.json"
        receipt = root / "receipt.json"
        artifact.write_bytes(b"synthetic artifact")
        auth.write_bytes(b'{"tokens":{"access_token":"do-not-record"}}')
        return argparse.Namespace(artifact=str(artifact), auth_file=str(auth), receipt=str(receipt)), artifact, receipt

    def test_early_daemon_exit_writes_sanitized_startup_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            args, artifact, receipt = self.make_args(directory)
            with patch("code_mode_compare.subprocess.Popen", return_value=Process(23)):
                with self.assertRaisesRegex(ValueError, "exited during startup"):
                    run(args)
            saved = json.loads(receipt.read_text())
            self.assertEqual(hashlib.sha256(artifact.read_bytes()).hexdigest(), saved["artifact_sha256"])
            self.assertEqual("startup", saved["phase"])
            self.assertEqual("daemon_exit", saved["category"])
            self.assertEqual(23, saved["exit_status"])
            self.assertEqual(0, saved["final_accounting"]["requests"])
            self.assertNotIn("do-not-record", receipt.read_text())

    def test_readiness_timeout_records_terminated_exit_status(self):
        with tempfile.TemporaryDirectory() as directory:
            args, _, receipt = self.make_args(directory)
            process = Process()
            with patch("code_mode_compare.subprocess.Popen", return_value=process), \
                    patch("code_mode_compare.request_json", side_effect=OSError("private connection detail")), \
                    patch("code_mode_compare.time.monotonic", side_effect=[0, 20]):
                with self.assertRaisesRegex(ValueError, "did not become ready"):
                    run(args)
            saved = json.loads(receipt.read_text())
            self.assertEqual("readiness_timeout", saved["category"])
            self.assertEqual(-15, saved["exit_status"])
            self.assertNotIn("private connection detail", receipt.read_text())

    def test_invalid_health_response_is_a_sanitized_startup_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            args, _, receipt = self.make_args(directory)
            with patch("code_mode_compare.subprocess.Popen", return_value=Process()), \
                    patch("code_mode_compare.request_json", return_value=[]):
                with self.assertRaisesRegex(ValueError, "invalid startup health"):
                    run(args)
            saved = json.loads(receipt.read_text())
            self.assertEqual("startup", saved["phase"])
            self.assertEqual("invalid_health_response", saved["category"])
            self.assertEqual(-15, saved["exit_status"])

    def test_spawn_and_configuration_failures_are_receipted_without_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            args, _, receipt = self.make_args(directory)
            with patch("code_mode_compare.subprocess.Popen", side_effect=OSError("launch secret")):
                with self.assertRaises(OSError):
                    run(args)
            saved = json.loads(receipt.read_text())
            self.assertEqual("spawn_failure", saved["category"])
            self.assertIsNone(saved["exit_status"])
            self.assertNotIn("launch secret", receipt.read_text())
            self.assertNotIn("do-not-record", receipt.read_text())

        with tempfile.TemporaryDirectory() as directory:
            args, _, receipt = self.make_args(directory)
            Path(args.auth_file).unlink()
            with self.assertRaises(FileNotFoundError):
                run(args)
            saved = json.loads(receipt.read_text())
            self.assertEqual("configuration_failure", saved["category"])
            self.assertIsNone(saved["exit_status"])
            self.assertNotIn("auth.json", receipt.read_text())

    def test_existing_receipt_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            args, _, receipt = self.make_args(directory)
            receipt.write_text('{"keep":true}\n')
            with patch("code_mode_compare.ThreadingHTTPServer") as server, \
                    patch("code_mode_compare.subprocess.Popen") as launch:
                with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
                    run(args)
            self.assertEqual('{"keep":true}\n', receipt.read_text())
            server.assert_not_called()
            launch.assert_not_called()

    def test_cleanup_preserves_rows_and_drained_accounting(self):
        with tempfile.TemporaryDirectory() as directory:
            args, _, receipt = self.make_args(directory)
            process = Process()

            def comparison(_):
                receipt.write_text(json.dumps({"runs": [{"case": "kept"}], "failure": "OSError"}))
                raise OSError("private comparison detail")

            with patch("code_mode_compare.run_comparison", side_effect=comparison), \
                    patch("code_mode_compare.request_json", return_value={"ok": True, "readyHeads": 2}), \
                    patch("code_mode_compare.subprocess.Popen", return_value=process):
                with self.assertRaises(OSError):
                    run(args)
            saved = json.loads(receipt.read_text())
            self.assertEqual([{"case": "kept"}], saved["runs"])
            self.assertEqual("comparison", saved["phase"])
            self.assertEqual("comparison_failure", saved["category"])
            self.assertEqual(-15, saved["exit_status"])
            self.assertEqual(0, saved["final_accounting"]["requests"])
            self.assertIsNone(saved["final_accounting"]["error"])
            self.assertNotIn("private comparison detail", receipt.read_text())

    def test_cleanup_failure_still_drains_server_accounting_and_preserves_startup_failure(self):
        class Server:
            server_port = 12345

            def __init__(self, budget):
                self.budget = budget
                self.closed = False

            def serve_forever(self):
                pass

            def shutdown(self):
                pass

            def server_close(self):
                self.closed = True
                self.budget.finish({"input_tokens": 3, "output_tokens": 2})

        with tempfile.TemporaryDirectory() as directory:
            args, _, receipt = self.make_args(directory)
            budget = Budget()
            server = Server(budget)
            process = Process(terminate_error=OSError("cleanup secret"))
            with patch("code_mode_compare.Budget", return_value=budget), \
                    patch("code_mode_compare.ThreadingHTTPServer", return_value=server), \
                    patch("code_mode_compare.subprocess.Popen", return_value=process), \
                    patch("code_mode_compare.request_json", side_effect=OSError("private connection detail")), \
                    patch("code_mode_compare.time.monotonic", side_effect=[0, 20]):
                with self.assertRaisesRegex(ValueError, "did not become ready"):
                    run(args)
            saved = json.loads(receipt.read_text())
            self.assertTrue(server.closed)
            self.assertEqual(3, saved["final_accounting"]["input_tokens"])
            self.assertEqual(2, saved["final_accounting"]["output_tokens"])
            self.assertNotIn("cleanup secret", receipt.read_text())


if __name__ == "__main__":
    unittest.main()
