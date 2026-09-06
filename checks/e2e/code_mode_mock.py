#!/usr/bin/env python3
"""Exercise the packaged code-mode bridge through an isolated daemon and loopback upstream.

No real credentials or vendor calls. Client tools use code_mode_probe's in-memory workspace.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from code_mode_guidance import GUIDANCE
from code_mode_probe import CASES, MAX_BODY, TOOLS, request_json, run_case


SCRIPTS = {
    "lookup-edit": 'const data = JSON.parse(await tools.call("Read", {file_path:"settings.json"})); '
                   'data.timeout = 20; await tools.call("Write", {file_path:"settings.json", content:JSON.stringify(data)}); '
                   'return "UPDATED";',
    "independent-reads": 'const values = await Promise.all(["a.txt","b.txt","c.txt"].map('
                         'file_path => tools.call("Read", {file_path}))); return values.reduce((s,v)=>s+Number(v),0);',
    "optional-discovery": 'return await tools.call("mcp__release__lookup", {});',
    "background-result": 'return await tools.call("Agent", {prompt:"7 times 8"});',
}
EXPECTED = {"lookup-edit": "UPDATED", "independent-reads": "110", "optional-discovery": "0.4.0",
            "background-result": "56"}
EXPECTED_ROUNDS = {case: 3 if case == "optional-discovery" else 2 for case, _ in CASES} | {"toggle-probe": 4}
PREFACE = "Checking the current settings before updating them."
CALLER_SYSTEM = (
    "Use tools to verify the requested facts. Keep the final answer concise. "
    "If a script tool is available, it may batch related tool operations. "
    "Only synthetic tools exist; do not invent results or poll background jobs."
)
TOGGLE_SYSTEM = "Toggle probe caller instructions."
TOGGLE_PROMPT = "TOGGLE_GUIDANCE_PROBE"
TOGGLE_RESPONSE = "TOGGLE_OK"


def assert_guidance(body, caller_system, enabled):
    input_items = body.get("input")
    if not isinstance(input_items, list) or len(input_items) < 2:
        raise ValueError("Responses lite input omitted its developer instruction")
    if input_items[0].get("type") != "additional_tools" or input_items[0].get("role") != "developer":
        raise ValueError("missing Responses lite additional_tools prefix")
    developers = [item for item in input_items
                  if item.get("role") == "developer" and item.get("type") != "additional_tools"]
    if len(developers) != 1 or developers[0] is not input_items[1]:
        raise ValueError("developer instructions moved or duplicated")
    instructions = developers[0].get("content")
    expected = caller_system + ("\n\n" + GUIDANCE if enabled else "")
    if instructions != expected:
        raise ValueError("developer instructions did not preserve the expected caller/guidance boundary")
    if instructions.count("<code_mode_orchestration>") != int(enabled):
        raise ValueError("code-mode guidance section count was not exactly one when enabled")
    declarations = input_items[0].get("tools", [])
    runners = [tool for tool in declarations if tool.get("name") == "splice_exec"]
    if len(runners) != int(enabled) or any(tool.get("type") != "custom" for tool in runners):
        raise ValueError("code-mode runner and guidance were not coupled")


def mock_handler(state):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            del format, args

        def do_POST(self):
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if self.path != "/responses" or not 0 < size <= MAX_BODY:
                    raise ValueError("unexpected mock request")
                body = json.loads(self.rfile.read(size))
                wire = json.dumps(body)
                case = ("auth-preflight" if "AUTH_PREFLIGHT" in wire else "toggle-probe" if TOGGLE_PROMPT in wire
                        else next(name for name, prompt in CASES if prompt in wire))
                account = "baseline" if case == "auth-preflight" else "code_mode"
                if (self.headers.get("ChatGPT-Account-Id") != account
                        or not self.headers.get("Authorization", "").endswith(".synthetic-" + account)):
                    raise ValueError("wrong per-provider synthetic credentials")
                state[case] = state.get(case, 0) + 1
                if state[case] > EXPECTED_ROUNDS.get(case, 1):
                    raise ValueError("extra backend round")
                output = [item for item in body["input"] if item.get("type") == "custom_tool_call_output"]
                declared = body["input"][0].get("tools", [])
                if case == "auth-preflight":
                    item = {"type": "message", "id": "auth-ok", "role": "assistant", "status": "completed",
                            "content": [{"type": "output_text", "text": "AUTH_OK", "annotations": []}]}
                elif case == "toggle-probe":
                    if "toggle_enabled" not in state:
                        raise ValueError("toggle probe arrived without an expected TOML state")
                    assert_guidance(body, TOGGLE_SYSTEM, state["toggle_enabled"])
                    state.setdefault("toggle_seen", []).append(state["toggle_enabled"])
                    item = {"type": "message", "id": "toggle-ok", "role": "assistant", "status": "completed",
                            "content": [{"type": "output_text", "text": TOGGLE_RESPONSE, "annotations": []}]}
                else:
                    assert_guidance(body, CALLER_SYSTEM, True)
                    if case == "optional-discovery" and state[case] == 1:
                        if any(t.get("name") == "mcp__release__lookup" for t in declared):
                            raise ValueError("release lookup was not deferred")
                        if not any(t.get("type") == "tool_search" for t in declared):
                            raise ValueError("missing native discovery declaration")
                        item = {"type": "tool_search_call", "id": "native-search-item", "call_id": "native-search",
                                "arguments": {"query": "release lookup", "limit": 1}}
                    elif not output:
                        if case == "optional-discovery" and not any(
                                item.get("type") == "tool_search_output" and item.get("call_id") == "native-search"
                                and any(t.get("name") == "mcp__release__lookup" for t in item.get("tools", []))
                                for item in body["input"]):
                            raise ValueError("native discovery did not expose release lookup")
                        if not any(t.get("type") == "custom" and t.get("name") == "splice_exec" for t in declared):
                            raise ValueError("missing code-mode declaration")
                        item = {"type": "custom_tool_call", "id": "item-" + case, "call_id": "outer-" + case,
                                "name": "splice_exec", "input": SCRIPTS[case], "status": "completed"}
                    else:
                        expected = EXPECTED[case]
                        evidence = wire if case == "background-result" else json.dumps(output)
                        if expected not in evidence:
                            raise ValueError("missing real synthetic tool evidence")
                        if any(item.get("type") in ("function_call", "function_call_output") for item in body["input"]):
                            raise ValueError("owned client tool pairs leaked upstream")
                        if case == "lookup-edit":
                            prefaces = [index for index, entry in enumerate(body["input"]) if PREFACE in json.dumps(entry)]
                            outer_index = next(index for index, entry in enumerate(body["input"])
                                               if entry.get("type") == "custom_tool_call")
                            if len(prefaces) != 1 or prefaces[0] >= outer_index:
                                raise ValueError("assistant continuity was lost, duplicated, or reordered")
                        if case == "optional-discovery":
                            native = [item.get("type") for item in body["input"]
                                      if item.get("call_id") == "native-search"]
                            if native != ["tool_search_call", "tool_search_output"]:
                                raise ValueError("native discovery history was lost or duplicated")
                        item = {"type": "message", "id": "final-" + case, "role": "assistant", "status": "completed",
                                "content": [{"type": "output_text", "text": expected, "annotations": []}]}
                items: list[dict] = [item]
                if case == "lookup-edit" and item["type"] == "custom_tool_call":
                    # The client must replay emitted text while the same script awaits dependent calls.
                    items.insert(0, {"type": "message", "id": "preface", "role": "assistant", "status": "completed",
                                     "content": [{"type": "output_text", "text": PREFACE, "annotations": []}]})
                response = {"id": "response-" + case, "status": "completed", "output": items,
                            "usage": {"input_tokens": 100, "output_tokens": 10,
                                      "input_tokens_details": {"cached_tokens": 0}}}
                events = [{"type": "response.created", "response": {"id": response["id"], "output": []}}]
                for index, emitted in enumerate(items):
                    events.append({"type": "response.output_item.added", "output_index": index, "item": emitted})
                    if emitted["type"] == "message":
                        for content_index, part in enumerate(emitted["content"]):
                            events.append({"type": "response.output_text.delta", "output_index": index,
                                           "content_index": content_index, "delta": part["text"]})
                    events.append({"type": "response.output_item.done", "output_index": index, "item": emitted})
                events.append({"type": "response.completed", "response": response})
                data = "".join("data: " + json.dumps(event) + "\n\n" for event in events).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except (ValueError, KeyError, TypeError, StopIteration) as error:
                state["error"] = str(error)
                self.send_error(400, "mock protocol assertion failed")
    return Handler


def available_ports():
    with socket.socket() as control, socket.socket() as head, socket.socket() as baseline:
        control.bind(("127.0.0.1", 0))
        head.bind(("127.0.0.1", 0))
        baseline.bind(("127.0.0.1", 0))
        return control.getsockname()[1], head.getsockname()[1], baseline.getsockname()[1]


def configure(root, upstream, control, head, baseline, code_mode: bool | None = True):
    state = root / "state"
    state.mkdir()
    bearer = "synthetic-management-" + os.urandom(12).hex()
    (state / "mgmt-key").write_text(bearer)
    (state / "mgmt-key").chmod(0o600)
    payload = base64.urlsafe_b64encode(json.dumps({"exp": int(time.time()) + 3600}).encode()).decode().rstrip("=")
    blocks = [f"[daemon]\ncontrol_port = {control}\n"]
    for name, port, enabled in (("baseline", baseline, False), ("code_mode", head, code_mode)):
        synthetic_token = "e30." + payload + ".synthetic-" + name
        auth = root / f"{name}-auth.json"
        auth.write_text(json.dumps({"tokens": {"access_token": synthetic_token, "refresh_token": "mock-refresh",
                                              "account_id": name},
                                   "last_refresh": datetime.now(timezone.utc).isoformat()}))
        auth.chmod(0o600)
        code_mode_field = "" if enabled is None else f"code_mode = {str(enabled).lower()}, "
        blocks.append(f'''[providers.{name}]
dialect = "openai-responses"
base_url = "http://127.0.0.1:{upstream}"
auth = {{ kind = "chatgpt-oauth", file = "{auth}" }}
quirks = {{ {code_mode_field}account_id_header = true, websocket = false, zstd_request_body = false, tool_surface = {{ enabled = true }} }}
[[providers.{name}.models]]
id = "gpt-6-astra"
context_window = 400000
[heads.{name}]
provider = "{name}"
port = {port}
discovery_prefix = "claude-{name}--"
pinned_model = "gpt-6-astra"
[heads.{name}.claude]
command = "claude-{name}"
''')
    config = root / "splice.toml"
    config.write_text("\n".join(blocks))
    env = os.environ.copy()
    env.update(SPLICE_CONFIG=str(config), CLAUDEX_STATE_DIR=str(state), CLAUDEX_QUOTA_POLL="off",
               CODEX_AUTH_PATH=str(root / "missing-legacy-auth.json"),
               CODEX_OAUTH_TOKEN_URL=f"http://127.0.0.1:{upstream}/oauth/token", SPLICE_PROBE_BEARER=bearer)
    return env


def start_daemon(root, artifact, env, control):
    log = (root / "daemon-output.log").open("wb")
    process = subprocess.Popen(["java", "-Xmx256m", f"-Duser.home={root}", "-jar", str(artifact), "daemon"],
                               env=env, cwd=root, stdout=log, stderr=log)
    try:
        deadline = time.monotonic() + 20
        while True:
            if process.poll() is not None:
                raise ValueError("isolated daemon exited during startup")
            try:
                health = request_json(control, "GET", "/health")
                if health.get("ok") and health.get("readyHeads") == 2:
                    return process, log
            except (OSError, ValueError):
                pass
            if time.monotonic() >= deadline:
                raise ValueError("isolated daemon did not become ready")
            time.sleep(0.1)
    except BaseException:
        stop_daemon(process, log)
        raise


def stop_daemon(process, log):
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    finally:
        log.flush()
        log.close()


def run_toggle_probe(head, bearer):
    response = request_json(head, "POST", "/v1/messages", {
        "model": "gpt-6-astra", "max_tokens": 100, "stream": False, "system": TOGGLE_SYSTEM,
        "output_config": {"effort": "high"}, "tools": TOOLS,
        "messages": [{"role": "user", "content": TOGGLE_PROMPT}],
    }, {"Content-Type": "application/json", "Authorization": "Bearer " + bearer,
        "x-claude-code-session-id": str(uuid.uuid4())})
    if (response.get("stop_reason") != "end_turn"
            or not any(part.get("text") == TOGGLE_RESPONSE for part in response.get("content", []))):
        raise ValueError("toggle probe did not receive its terminal loopback response")


def run_toggle_boots(root, artifact, upstream, state):
    expected_states = ((None, False), (True, True), (False, False), (True, True))
    for index, (configured, expected_enabled) in enumerate(expected_states):
        boot = root / f"toggle-{index}"
        boot.mkdir()
        control, head, baseline = available_ports()
        env = configure(boot, upstream, control, head, baseline, code_mode=configured)
        state["toggle_enabled"] = expected_enabled
        process, log = start_daemon(boot, artifact, env, control)
        try:
            run_toggle_probe(head, env["SPLICE_PROBE_BEARER"])
        finally:
            stop_daemon(process, log)
    if state.get("toggle_seen") != [enabled for _, enabled in expected_states]:
        raise ValueError("fresh TOML boots did not preserve the requested code-mode toggle sequence")


def run(args):
    artifact = Path(args.artifact).resolve()
    receipt = Path(args.receipt)
    if receipt.exists():
        raise ValueError("refusing to overwrite a receipt")
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    print("Packaged mock bridge SHA-256: " + digest, flush=True)
    state = {}
    rows = []
    failure = None
    server = ThreadingHTTPServer(("127.0.0.1", 0), mock_handler(state))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="code-mode-mock-") as directory:
            root = Path(directory)
            control, head, baseline = available_ports()
            env = configure(root, server.server_port, control, head, baseline)
            previous = os.environ.get("SPLICE_PROBE_BEARER")
            os.environ["SPLICE_PROBE_BEARER"] = env["SPLICE_PROBE_BEARER"]
            try:
                process, log = start_daemon(root, artifact, env, control)
                try:
                    preflight = request_json(baseline, "POST", "/v1/messages", {
                        "model": "gpt-6-astra", "max_tokens": 100, "stream": False,
                        "messages": [{"role": "user", "content": "AUTH_PREFLIGHT"}],
                    }, {"Content-Type": "application/json", "Authorization": "Bearer " + env["SPLICE_PROBE_BEARER"]})
                    if (preflight.get("stop_reason") != "end_turn"
                            or not any(part.get("text") == "AUTH_OK" for part in preflight.get("content", []))
                            or state.get("auth-preflight") != 1 or state.get("error")):
                        raise ValueError("two-head auth preflight failed")
                    print("Two-head synthetic auth preflight: baseline passed", flush=True)
                    for case, prompt in CASES:
                        row = run_case(head, "gpt-6-astra", case, prompt)
                        row["backend_requests"] = state.get(case, 0)
                        rows.append(row)
                        print(json.dumps(row), flush=True)
                        if not row["passed"] or row["backend_requests"] != EXPECTED_ROUNDS[case] or state.get("error"):
                            log.flush()
                            receipt.parent.mkdir(parents=True, exist_ok=True)
                            receipt.with_suffix(".daemon.log").write_bytes((root / "daemon-output.log").read_bytes())
                            raise ValueError("mock bridge correctness failure")
                finally:
                    stop_daemon(process, log)
                run_toggle_boots(root, artifact, server.server_port, state)
            finally:
                if previous is None:
                    os.environ.pop("SPLICE_PROBE_BEARER", None)
                else:
                    os.environ["SPLICE_PROBE_BEARER"] = previous
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        failure = str(error)
        raise
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        receipt.parent.mkdir(parents=True, exist_ok=True)
        receipt.write_text(json.dumps({"kind": "code-mode-packaged-mock", "artifact_sha256": digest,
                                       "failure": failure, "mock_error": state.get("error"),
                                       "auth_preflight_requests": state.get("auth-preflight", 0),
                                       "toggle_probe_requests": state.get("toggle-probe", 0),
                                       "toggle_states": state.get("toggle_seen", []),
                                       "runs": rows}, indent=2) + "\n")


class MockTests(unittest.TestCase):
    def test_guidance_keeps_one_original_developer_item_and_runner(self):
        caller_tools = [{"type": "function", "name": "Read"}]
        body = {"input": [
            {"type": "additional_tools", "role": "developer",
             "tools": caller_tools + [{"type": "custom", "name": "splice_exec"}]},
            {"role": "developer", "content": CALLER_SYSTEM + "\n\n" + GUIDANCE},
            {"role": "user", "content": "synthetic"},
        ]}
        assert_guidance(body, CALLER_SYSTEM, True)

    def test_disabled_guidance_is_exactly_the_caller_instruction_without_runner(self):
        body = {"input": [
            {"type": "additional_tools", "role": "developer", "tools": [{"type": "function", "name": "Read"}]},
            {"role": "developer", "content": TOGGLE_SYSTEM},
            {"role": "user", "content": TOGGLE_PROMPT},
        ]}
        assert_guidance(body, TOGGLE_SYSTEM, False)
        body["input"].append({"role": "developer", "content": "duplicate"})
        with self.assertRaisesRegex(ValueError, "duplicated"):
            assert_guidance(body, TOGGLE_SYSTEM, False)

    def test_toggle_toml_has_true_false_and_omitted_states(self):
        import tomllib

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, (configured, expected) in enumerate(((None, None), (True, True), (False, False))):
                current = root / str(index)
                current.mkdir()
                env = configure(current, 1, 2, 3, 4, code_mode=configured)
                quirks = tomllib.loads(Path(env["SPLICE_CONFIG"]).read_text())["providers"]["code_mode"]["quirks"]
                self.assertEqual(expected, quirks.get("code_mode"))

    def test_toggle_probe_uses_normal_tools_system_and_session_header(self):
        captured = []

        def reply(port, method, path, body, headers):
            captured.append((port, method, path, body, headers))
            return {"stop_reason": "end_turn", "content": [{"type": "text", "text": TOGGLE_RESPONSE}]}

        with patch(__name__ + ".request_json", side_effect=reply):
            run_toggle_probe(1234, "synthetic-bearer")
        _, method, path, body, headers = captured[0]
        self.assertEqual(("POST", "/v1/messages"), (method, path))
        self.assertEqual(TOOLS, body["tools"])
        self.assertEqual(TOGGLE_SYSTEM, body["system"])
        self.assertEqual("Bearer synthetic-bearer", headers["Authorization"])
        self.assertTrue(headers["x-claude-code-session-id"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--artifact")
    parser.add_argument("--receipt")
    args = parser.parse_args()
    if args.selftest:
        unittest.main(argv=[__file__])
    elif args.artifact and args.receipt:
        run(args)
    else:
        parser.error("choose --selftest or both --artifact and --receipt")
