#!/usr/bin/env python3
"""Run the explicitly approved bounded comparison in one disposable, isolated daemon.

Uses an existing ChatGPT auth file without modifying it. Only the budget proxy may
contact the vendor; tools are in-memory fixtures and receipts contain aggregates.
This consumes subscription quota: never add this runner to the default gate.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
from http.server import ThreadingHTTPServer

from code_mode_probe import Budget, proxy_handler, request_json, run_comparison, validate_prompt_experiment


def configure(root, auth_file, proxy_port, prompt_guidance=False):
    state = root / "state"
    state.mkdir(mode=0o700)
    bearer = "probe-management-" + os.urandom(24).hex()
    (state / "mgmt-key").write_text(bearer)
    (state / "mgmt-key").chmod(0o600)
    # Refresh is deliberately unavailable in this experiment. A stale credential
    # fails locally rather than mutating the operator's token or expanding spend.
    auth = root / "auth.json"
    auth.touch(mode=0o600)
    auth.write_bytes(Path(auth_file).read_bytes())
    sockets = [socket.socket() for _ in range(3)]
    try:
        for listener in sockets:
            listener.bind(("127.0.0.1", 0))
        control, baseline, code_mode = [listener.getsockname()[1] for listener in sockets]
    finally:
        for listener in sockets:
            listener.close()
    config = root / "splice.toml"
    lines = ["[daemon]", f"control_port = {control}", 'effort = "high"']
    for variant, port, enabled in (("baseline", baseline, "true" if prompt_guidance else "false"),
                                   ("code_mode", code_mode, "true")):
        lines.extend([
            f"[providers.{variant}]", 'dialect = "openai-responses"',
            f'base_url = "http://127.0.0.1:{proxy_port}"',
            f'auth = {{ kind = "chatgpt-oauth", file = {json.dumps(str(auth))} }}',
            f'quirks = {{ code_mode = {enabled}, account_id_header = true, websocket = false, zstd_request_body = false, '
            'tool_surface = { enabled = true } }',
            f"[[providers.{variant}.models]]", 'id = "gpt-6-astra"', 'context_window = 400000',
            f"[heads.{variant}]", f'provider = "{variant}"', f"port = {port}",
            f'discovery_prefix = "claude-probe-{variant}--"', 'pinned_model = "gpt-6-astra"',
            f"[heads.{variant}.claude]", f'command = "claude-probe-{variant}"',
        ])
    config.write_text("\n".join(lines) + "\n")
    env = os.environ.copy()
    env.update(SPLICE_CONFIG=str(config), CLAUDEX_STATE_DIR=str(state), CLAUDEX_QUOTA_POLL="off",
               CODEX_OAUTH_TOKEN_URL=f"http://127.0.0.1:{proxy_port}/oauth/token", SPLICE_PROBE_BEARER=bearer)
    return env, control, baseline, code_mode


def stop_process(process):
    if process is None:
        return None, None
    cleanup_error = None
    try:
        status = process.poll()
    except (OSError, subprocess.SubprocessError) as exc:
        status = None
        cleanup_error = exc
    if status is None:
        try:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        except (OSError, subprocess.SubprocessError) as exc:
            cleanup_error = cleanup_error or exc
    try:
        status = process.poll()
    except (OSError, subprocess.SubprocessError) as exc:
        cleanup_error = cleanup_error or exc
        status = None
    return status if type(status) is int else None, cleanup_error


def stop_server(server, thread):
    cleanup_error = None
    if server is not None:
        for operation in (server.shutdown, server.server_close):
            try:
                operation()
            except (OSError, RuntimeError) as exc:
                cleanup_error = cleanup_error or exc
    if thread is not None:
        try:
            thread.join(timeout=5)
        except RuntimeError as exc:
            cleanup_error = cleanup_error or exc
    return cleanup_error


def finalize_receipt(receipt, artifact_sha256, phase, category, exit_status, budget):
    final_accounting = budget.snapshot()
    if final_accounting["error"] == "comparison ended":
        final_accounting["error"] = None
    if receipt.exists():
        saved = json.loads(receipt.read_text())
    else:
        saved = {"model": "gpt-6-astra", "planned_runs": 16, "completed_runs": 0, "runs": []}
    saved.update(artifact_sha256=artifact_sha256, phase=phase, category=category,
                 exit_status=exit_status, final_accounting=final_accounting)
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps(saved, indent=2) + "\n")


def run(args):
    validate_prompt_experiment(args)
    artifact = Path(args.artifact).resolve()
    receipt = Path(args.receipt)
    if receipt.exists():
        raise ValueError("refusing to overwrite a receipt")
    artifact_sha256 = hashlib.sha256(artifact.read_bytes()).hexdigest()
    budget = Budget()
    server = thread = process = None
    previous = os.environ.get("SPLICE_PROBE_BEARER")
    phase = "startup"
    category = "configuration_failure"
    exit_status = None
    cleanup_error = receipt_error = primary_error = None
    launch_attempted = False
    try:
        server = ThreadingHTTPServer(("127.0.0.1", 0), proxy_handler(budget))
        server.daemon_threads = False  # server_close must await every billed response's accounting.
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        with tempfile.TemporaryDirectory(prefix="code-mode-comparison-") as directory:
            root = Path(directory)
            try:
                prompt_guidance = getattr(args, "prompt_guidance", False)
                env, control, baseline, code_mode = configure(root, args.auth_file, server.server_port, prompt_guidance)
                os.environ["SPLICE_PROBE_BEARER"] = env["SPLICE_PROBE_BEARER"]
                with (root / "daemon-output.log").open("wb") as log:
                    launch_attempted = True
                    process = subprocess.Popen(
                        ["java", "-Xmx256m", f"-Duser.home={root}", "-jar", str(artifact), "daemon"],
                        env=env, cwd=root, stdout=log, stderr=log,
                    )
                    deadline = time.monotonic() + 20
                    while True:
                        status = process.poll()
                        if status is not None:
                            exit_status = status if type(status) is int else None
                            category = "daemon_exit"
                            raise ValueError("isolated daemon exited during startup")
                        try:
                            health = request_json(control, "GET", "/health")
                        except (OSError, ValueError):
                            pass
                        else:
                            if not (type(health) is dict and type(health.get("ok")) is bool and
                                    type(health.get("readyHeads")) is int):
                                category = "invalid_health_response"
                                raise ValueError("isolated daemon returned invalid startup health")
                            if health["ok"] and health["readyHeads"] == 2:
                                break
                        if time.monotonic() >= deadline:
                            category = "readiness_timeout"
                            raise ValueError("isolated daemon did not become ready")
                        time.sleep(0.1)
                    phase = "comparison"
                    run_comparison(argparse.Namespace(
                        artifact=str(artifact), receipt=str(receipt), model="gpt-6-astra",
                        baseline_port=baseline, code_mode_port=code_mode, metrics_port=server.server_port,
                        prompt_guidance=prompt_guidance,
                    ))
                    phase = "complete"
                    category = "complete"
            finally:
                if process is not None:
                    # Prevent retries from starting while teardown drains existing work.
                    with budget.lock:
                        budget.error = budget.error or "comparison ended"
                    stopped_status, process_cleanup_error = stop_process(process)
                    exit_status = stopped_status if stopped_status is not None else exit_status
                    cleanup_error = cleanup_error or process_cleanup_error
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as exc:
        primary_error = exc
        if phase == "startup" and category == "configuration_failure" and launch_attempted:
            category = "spawn_failure"
        elif phase == "comparison":
            category = "comparison_failure"
        raise
    finally:
        server_cleanup_error = stop_server(server, thread)
        cleanup_error = cleanup_error or server_cleanup_error
        if primary_error is None and cleanup_error is not None:
            phase = "cleanup"
            category = "cleanup_failure"
        try:
            finalize_receipt(receipt, artifact_sha256, phase, category, exit_status, budget)
        except (OSError, ValueError, TypeError) as exc:
            receipt_error = exc
        if previous is None:
            os.environ.pop("SPLICE_PROBE_BEARER", None)
        else:
            os.environ["SPLICE_PROBE_BEARER"] = previous
        if primary_error is None:
            if cleanup_error is not None:
                raise cleanup_error
            if receipt_error is not None:
                raise receipt_error


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", required=True)
    parser.add_argument("--auth-file", required=True)
    parser.add_argument("--receipt", required=True)
    parser.add_argument("--prompt-guidance", action="store_true",
                        help="enable code mode in both heads and vary only an appended instruction section")
    run(parser.parse_args())
