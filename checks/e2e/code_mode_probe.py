#!/usr/bin/env python3
"""Bounded, opt-in code-mode A/B probe. Fake client tools never touch files or run commands.

Run --proxy-port first and point BOTH isolated heads at that loopback upstream, with
WebSocket and request compression disabled. The proxy caps actual vendor requests,
including discovery/retry rounds; comparison mode reads its counters between synthetic tasks.
No credential, prompt, source, or tool-output content is written to the receipt.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import io
import json
import os
import re
from pathlib import Path
import threading
import time
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import uuid
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MAX_REQUESTS = 64
INPUT_BUDGET = 400_000
OUTPUT_BUDGET = 32_000
MAX_BODY = 1_048_576


class Budget:
    def __init__(self):
        self.lock = threading.Lock()
        self.requests = self.input_tokens = self.output_tokens = self.cached_tokens = 0
        self.request_bytes = self.response_bytes = self.schema_bytes = self.search_calls = self.code_calls = 0
        self.direct_calls = self.code_tool_requests = self.guidance_requests = 0
        self.error = None

    def snapshot(self):
        with self.lock:
            return {key: getattr(self, key) for key in (
                "requests", "input_tokens", "output_tokens", "cached_tokens", "request_bytes", "response_bytes",
                "schema_bytes", "search_calls", "code_calls", "direct_calls", "code_tool_requests",
                "guidance_requests", "error"
            )}

    def reserve(self, size):
        with self.lock:
            if (self.error or self.requests >= MAX_REQUESTS or
                    self.input_tokens >= INPUT_BUDGET or self.output_tokens >= OUTPUT_BUDGET):
                return False
            self.requests += 1
            self.request_bytes += size
            return True

    def record_shape(self, payload):
        declarations = list(payload.get("tools", []))
        for item in payload.get("input", []):
            if isinstance(item, dict) and item.get("type") in ("additional_tools", "tool_search_output"):
                declarations.extend(item.get("tools", []))
        with self.lock:
            self.schema_bytes += len(json.dumps(declarations, separators=(",", ":")).encode())
            self.code_tool_requests += any(tool.get("name") == "splice_exec" for tool in declarations)
            self.guidance_requests += "<code_mode_orchestration>" in json.dumps(payload)

    def record_item(self, item, index, seen):
        kind = item.get("type")
        if kind not in ("tool_search_call", "custom_tool_call", "function_call"):
            return
        # Added/done/terminal representations of the same item count once. The
        # registry belongs to one response, not the entire multi-request probe.
        keys = {(kind, key, item[key]) for key in ("id", "call_id") if item.get(key)}
        if index is not None:
            keys.add((kind, "index", index))
        duplicate = bool(keys & seen)
        seen.update(keys)
        if duplicate:
            return
        with self.lock:
            self.search_calls += kind == "tool_search_call"
            self.code_calls += kind == "custom_tool_call"
            self.direct_calls += kind == "function_call"

    def finish(self, usage, error=None):
        with self.lock:
            if (not isinstance(usage, dict) or any(type(usage.get(key)) is not int or usage[key] < 0
                                                   for key in ("input_tokens", "output_tokens"))):
                self.error = error or "vendor response omitted usage; stopping rather than guessing spend"
                return
            self.input_tokens += usage["input_tokens"]
            self.output_tokens += usage["output_tokens"]
            details = usage.get("input_tokens_details", {})
            cached = details.get("cached_tokens", 0) if isinstance(details, dict) else None
            if type(cached) is not int or not 0 <= cached <= usage["input_tokens"]:
                self.error = "vendor response contained invalid cache usage; stopping"
                return
            self.cached_tokens += cached
            self.error = error or self.error


def proxy_handler(budget):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format, *args):
            del format, args  # Request and credential details must not enter logs.

        def do_GET(self):
            payload = json.dumps(budget.snapshot()).encode()
            self.send_response(200 if self.path == "/metrics" else 404)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_POST(self):
            size = int(self.headers.get("Content-Length", "0"))
            bearer = self.headers.get("Authorization", "")
            local_bearer = os.environ.get("SPLICE_PROBE_BEARER", "")
            if (self.path != "/responses" or not 0 < size <= MAX_BODY or
                    self.headers.get("Content-Encoding") or not bearer.startswith("Bearer ") or
                    (local_bearer and bearer == "Bearer " + local_bearer)):
                self.send_error(400, "invalid probe request or credential boundary")
                return
            body = self.rfile.read(size)
            try:
                payload = json.loads(body)
                if not isinstance(payload, dict) or payload.get("model") != "gpt-6-astra":
                    raise ValueError("probe permits only the approved Astra model")
                reasoning = payload.get("reasoning")
                if not isinstance(reasoning, dict) or reasoning.get("effort") != "high":
                    raise ValueError("probe permits only the approved high effort")
            except (ValueError, TypeError):
                self.send_error(400, "invalid probe model or JSON")
                return
            if not budget.reserve(len(body)):
                self.send_error(429, "probe budget exhausted or halted")
                return
            budget.record_shape(payload)
            upstream = http.client.HTTPSConnection("chatgpt.com", timeout=120)
            usage = None
            error = None
            accounted = False
            seen_items = set()
            try:
                excluded = {"host", "connection", "content-length", "transfer-encoding", "accept-encoding"}
                headers = {k: v for k, v in self.headers.items() if k.lower() not in excluded}
                headers["Accept-Encoding"] = "identity"
                upstream.request("POST", "/backend-api/codex/responses", body, headers)
                response = upstream.getresponse()
                if response.status != 200:
                    error = f"vendor HTTP {response.status}"
                self.send_response(response.status)
                self.send_header("Content-Type", response.getheader("Content-Type", "application/json"))
                self.send_header("Connection", "close")
                self.end_headers()
                pending = b""
                forwarding = True
                while chunk := response.read1(65536):
                    with budget.lock:
                        budget.response_bytes += len(chunk)
                    pending += chunk
                    while b"\n" in pending:
                        line, pending = pending.split(b"\n", 1)
                        if line.startswith(b"data: ") and line[6:].strip() != b"[DONE]":
                            try:
                                event = json.loads(line[6:])
                            except ValueError:
                                continue
                            if event.get("type") in ("response.output_item.added", "response.output_item.done"):
                                budget.record_item(event.get("item", {}), event.get("output_index"), seen_items)
                            if event.get("type") in ("response.completed", "response.incomplete", "response.failed"):
                                if accounted:
                                    raise ValueError("duplicate terminal response")
                                terminal = event.get("response", {})
                                usage = terminal.get("usage")
                                for index, item in enumerate(terminal.get("output", [])):
                                    budget.record_item(item, index, seen_items)
                                if event["type"] != "response.completed":
                                    error = "vendor response did not complete"
                                # A forwarded terminal can immediately trigger another vendor request.
                                budget.finish(usage, error)
                                accounted = True
                    if len(pending) > MAX_BODY:
                        raise ValueError("oversized upstream frame")
                    if forwarding:
                        try:
                            self.wfile.write(chunk)
                            self.wfile.flush()
                        except OSError:
                            forwarding = False
                            error = "client disconnected; upstream drained for usage"
            except (OSError, ValueError, http.client.HTTPException) as exc:
                error = type(exc).__name__
            finally:
                self.close_connection = True
                upstream.close()
                if not accounted:
                    budget.finish(usage, error)
                elif error:
                    with budget.lock:
                        budget.error = error
    return Handler


def definition(name, description, properties):
    return {"name": name, "description": description, "input_schema": {
        "type": "object", "properties": properties, "required": list(properties), "additionalProperties": False,
    }}


TOOLS = [
    definition("Read", "Read a named file in the synthetic workspace.", {"file_path": {"type": "string"}}),
    definition("Write", "Replace one synthetic workspace file.", {
        "file_path": {"type": "string"}, "content": {"type": "string"},
    }),
    definition("Agent", "Start a background arithmetic job. Its result arrives in a completion notification; do not poll.", {
        "prompt": {"type": "string"},
    }),
    definition("mcp__release__lookup", "Look up the next release version.", {}),
] + [definition(f"mcp__unrelated__operation_{i}", "An unrelated integration, not needed by these tasks.", {})
     for i in range(8)]

CASES = (
    ("lookup-edit", "Read settings.json, change timeout from 10 to 20 without losing other fields, and report UPDATED."),
    ("independent-reads", "Read a.txt, b.txt, and c.txt and report the sum of the three numbers."),
    ("optional-discovery", "Use the release lookup integration to obtain the next release version. Report that version."),
    ("background-result", "Start an Agent to calculate 7 times 8, then report the answer from its completion notification. Do not poll."),
)


class Workspace:
    def __init__(self):
        self.files = {"settings.json": '{"timeout":10,"enabled":true}', "a.txt": "21", "b.txt": "34", "c.txt": "55"}
        self.calls = []
        self.arguments_seen = set()
        self.repeated_calls = 0
        self.read_paths = set()
        self.notification = None

    def execute(self, tool):
        name, args = tool["name"], tool.get("input", {})
        self.calls.append(name)
        signature = json.dumps([name, args], sort_keys=True, separators=(",", ":"))
        self.repeated_calls += signature in self.arguments_seen
        self.arguments_seen.add(signature)
        if name == "Read" and set(args) == {"file_path"}:
            self.read_paths.add(args["file_path"])
            return self.files[args["file_path"]]
        if name == "Write" and set(args) == {"file_path", "content"}:
            if args["file_path"] not in self.files:
                raise ValueError("write outside synthetic workspace")
            self.files[args["file_path"]] = args["content"]
            return "Updated synthetic file."
        if name == "mcp__release__lookup" and not args:
            return '{"version":"0.4.0"}'
        if name == "Agent" and set(args) == {"prompt"} and self.calls.count("Agent") == 1:
            self.notification = "Background job probe-job completed: 7 times 8 = 56."
            return "Background job probe-job queued; wait for its completion notification, not polling."
        raise ValueError("unexpected tool or arguments in synthetic task")

    def metrics(self):
        return {}

    def correct(self, case, text):
        if case == "lookup-edit":
            return (json.loads(self.files["settings.json"]) == {"timeout": 20, "enabled": True}
                    and "Read" in self.calls and "Write" in self.calls and "UPDATED" in text)
        if case == "independent-reads":
            return {"a.txt", "b.txt", "c.txt"}.issubset(self.read_paths) and bool(re.search(r"\b110\b", text))
        if case == "optional-discovery":
            return "mcp__release__lookup" in self.calls and "0.4.0" in text
        return self.calls.count("Agent") == 1 and "56" in text


def request_json(port, method, path, body=None, headers=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=180)
    try:
        connection.request(method, path, json.dumps(body).encode() if body is not None else None, headers or {})
        response = connection.getresponse()
        data = response.read(MAX_BODY + 1)
        if len(data) > MAX_BODY:
            raise ValueError("probe response exceeded byte limit")
        if response.status != 200:
            raise ValueError(f"probe HTTP {response.status}")
        return json.loads(data)
    finally:
        connection.close()


def run_case(port, model, case, prompt, scenario=None, instruction_suffix=""):
    workspace = scenario.Workspace() if scenario else Workspace()
    system = scenario.SYSTEM if scenario else (
        "Use tools to verify the requested facts. Keep the final answer concise. "
        "If a script tool is available, it may batch related tool operations. "
        "Only synthetic tools exist; do not invent results or poll background jobs."
    )
    if instruction_suffix:
        system += "\n\n" + instruction_suffix
    messages = [{"role": "user", "content": prompt}]
    headers = {"Content-Type": "application/json", "x-claude-code-session-id": str(uuid.uuid4())}
    if token := os.environ.get("SPLICE_PROBE_BEARER"):
        headers["Authorization"] = "Bearer " + token
    start = time.monotonic()
    final = ""
    turn = 0
    error = None
    failed_tools = 0
    client_bytes = 0
    bridge_callbacks = bridge_batches = max_bridge_batch = 0
    seen_call_ids = set()
    try:
        for turn in range(12):  # Local script resumptions do not each invoke the model.
            payload = {
                "model": model, "stream": False, "max_tokens": 2048,
                "system": system,
                "output_config": {"effort": "high"}, "tools": scenario.TOOLS if scenario else TOOLS,
                "messages": messages,
            }
            client_bytes += len(json.dumps(payload).encode())
            response = request_json(port, "POST", "/v1/messages", payload, headers)
            content = response.get("content", [])
            messages.append({"role": "assistant", "content": content})
            calls = [b for b in content if b.get("type") == "tool_use"]
            if not calls:
                final = "".join(b.get("text", "") for b in content if b.get("type") == "text")
                if response.get("stop_reason") not in ("end_turn", "stop_sequence"):
                    raise ValueError("unexpected terminal reason")
                break
            bridge_count = sum(tool["id"].startswith("toolu_splice_") for tool in calls)
            bridge_callbacks += bridge_count
            bridge_batches += bool(bridge_count)
            max_bridge_batch = max(max_bridge_batch, bridge_count)
            results = []
            for tool in calls:
                try:
                    if tool["id"] in seen_call_ids:
                        raise ValueError("duplicate callback identity")
                    seen_call_ids.add(tool["id"])
                    output = workspace.execute({"name": tool["name"], "input": tool.get("input", {})})
                except (KeyError, TypeError, ValueError):
                    failed_tools += 1
                    raise
                results.append({"type": "tool_result", "tool_use_id": tool["id"], "content": output})
            if getattr(workspace, "notification", None):
                results.append({"type": "text", "text": workspace.notification})
                workspace.notification = None
            messages.append({"role": "user", "content": results})
        passed = workspace.correct(case, final)
    except (OSError, ValueError, KeyError, TypeError, http.client.HTTPException) as exc:
        # Only the exception class enters the receipt; never vendor output or fixture content.
        error = type(exc).__name__
        passed = False
    return {"case": case, "passed": passed, "error": error, "client_requests": turn + 1,
            "tool_calls": len(workspace.calls), "repeated_tool_calls": workspace.repeated_calls,
            "failed_tool_calls": failed_tools, "client_request_bytes": client_bytes,
            "bridge_callbacks": bridge_callbacks, "bridge_callback_batches": bridge_batches,
            "max_bridge_batch": max_bridge_batch,
            "elapsed_ms": round((time.monotonic() - start) * 1000),
            **(workspace.metrics() if scenario else {})}


def validate_prompt_experiment(args):
    if not getattr(args, "prompt_guidance", False):
        return
    with zipfile.ZipFile(args.artifact) as artifact:
        if "splice/provider/codex/code-mode-orchestration.txt" in artifact.namelist():
            raise ValueError("prompt-only A/B requires a pre-injection artifact; this build adds guidance automatically")


def run_comparison(args):
    validate_prompt_experiment(args)
    rows = []
    receipt = Path(args.receipt)
    if receipt.exists():
        raise ValueError("refusing to overwrite an existing receipt")
    digest = hashlib.sha256(Path(args.artifact).read_bytes()).hexdigest()
    scenario = None
    experiment: dict[str, object] = {"kind": "bridge-on-off"}
    if getattr(args, "prompt_guidance", False):
        import code_mode_guidance as scenario
        experiment = {
            "kind": "code-mode-prompt-guidance", "base_system": scenario.SYSTEM,
            "appended_section": scenario.GUIDANCE,
            "guidance_sha256": hashlib.sha256(scenario.GUIDANCE_PATH.read_bytes()).hexdigest(),
            "fixture_sha256": hashlib.sha256(json.dumps(scenario.FILES, sort_keys=True).encode()).hexdigest(),
            "catalog_sha256": hashlib.sha256(json.dumps(scenario.TOOLS, sort_keys=True).encode()).hexdigest(),
        }
    experiment["harness_sha256"] = {
        name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
        for name in ("code_mode_probe.py", "code_mode_compare.py") +
        (("code_mode_guidance.py",) if scenario else ())
    }
    failure = None
    accounting = None
    try:
        for repetition in range(2):
            for case, prompt in (scenario.CASES if scenario else CASES):
                # Alternate ordering to reduce a consistent warm-cache/order advantage.
                variants = [("existing", args.baseline_port), ("guided", args.code_mode_port)] if scenario else [
                    ("baseline", args.baseline_port), ("code_mode", args.code_mode_port)]
                for variant, port in (variants if repetition == 0 else list(reversed(variants))):
                    before = request_json(args.metrics_port, "GET", "/metrics")
                    accounting = before
                    if (before["error"] or before["requests"] >= MAX_REQUESTS or
                            before["input_tokens"] >= INPUT_BUDGET or before["output_tokens"] >= OUTPUT_BUDGET):
                        raise ValueError("proxy budget halted")
                    row = run_case(port, args.model, case, prompt, scenario=scenario,
                                   instruction_suffix=scenario.GUIDANCE if scenario and variant == "guided" else "")
                    row.update({"variant": variant, "repetition": repetition + 1, "accounting_complete": False})
                    rows.append(row)
                    after = request_json(args.metrics_port, "GET", "/metrics")
                    accounting = after
                    row.update({key: after[key] - before[key] for key in before if key != "error"})
                    row["accounting_complete"] = not bool(after["error"])
                    print(json.dumps(row), flush=True)
                    if not row["passed"] or after["error"]:
                        raise ValueError("correctness failure or upstream accounting failure; comparison stopped")
    except (OSError, ValueError, KeyError, TypeError, http.client.HTTPException) as exc:
        failure = type(exc).__name__
        raise
    finally:
        receipt.parent.mkdir(parents=True, exist_ok=True)
        receipt.write_text(json.dumps({"artifact_sha256": digest, "model": args.model, "experiment": experiment,
                                      "planned_runs": 16, "completed_runs": len(rows),
                                      "failure": failure, "last_observed_accounting": accounting,
                                      "cache_condition": "uncontrolled; observed cached tokens recorded per run",
                                      "budgets": {"requests": MAX_REQUESTS, "input_tokens": INPUT_BUDGET,
                                                  "output_tokens": OUTPUT_BUDGET},
                                      "runs": rows}, indent=2) + "\n")
    print(json.dumps({"artifact_sha256": digest, "completed_runs": len(rows), "passed": all(r["passed"] for r in rows)}))


class ProbeTests(unittest.TestCase):
    def test_prompt_only_experiment_rejects_auto_injection_before_launch(self):
        from code_mode_compare import run

        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "app.jar"
            with zipfile.ZipFile(artifact, "w") as archive:
                archive.writestr("splice/provider/codex/code-mode-orchestration.txt", "synthetic guidance")
            args = argparse.Namespace(artifact=str(artifact), prompt_guidance=True)
            with patch("code_mode_compare.subprocess.Popen") as launch, \
                    patch("code_mode_compare.ThreadingHTTPServer") as server:
                with self.assertRaisesRegex(ValueError, "adds guidance automatically"):
                    run(args)
                launch.assert_not_called()
                server.assert_not_called()
            with zipfile.ZipFile(artifact, "w") as archive:
                archive.writestr("legacy.txt", "no automatic guidance")
            validate_prompt_experiment(args)

    def test_guidance_comparison_enables_identical_provider_configs(self):
        import tomllib
        from code_mode_compare import configure

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source-auth.json"
            source.write_bytes(b'{"tokens":{"access_token":"synthetic"}}')
            env, _, _, _ = configure(root, source, 12345, prompt_guidance=True)
            providers = tomllib.loads(Path(env["SPLICE_CONFIG"]).read_text())["providers"]
            self.assertEqual(providers["baseline"], providers["code_mode"])
            self.assertTrue(providers["baseline"]["quirks"]["code_mode"])

    def test_guidance_only_appends_system_text_and_records_real_callback_ids(self):
        import code_mode_guidance as scenario

        captured = []
        case, prompt = scenario.CASES[1]

        def reply(port, method, path, body, headers):
            del port, method, path, headers
            captured.append(json.loads(json.dumps(body)))
            if len(captured) % 2:
                return {"content": [{"type": "tool_use", "id": "toolu_splice_test", "name": "LSP", "input": {
                    "operation": "findReferences", "file_path": "app/limits.py", "symbol": "build_client",
                }}]}
            return {"content": [{"type": "text", "text": json.dumps(scenario.CONTRACTS[case])}],
                    "stop_reason": "end_turn"}

        for suffix in ("", scenario.GUIDANCE):
            with patch(__name__ + ".request_json", side_effect=reply):
                row = run_case(1, "gpt-6-astra", case, prompt, scenario=scenario, instruction_suffix=suffix)
            self.assertTrue(row["passed"], row)
            self.assertEqual(1, row["bridge_callbacks"])
            self.assertEqual(1, row["bridge_callback_batches"])
            self.assertEqual({"LSP": 1}, row["tool_counts"])
        existing, guided = dict(captured[0]), dict(captured[2])
        self.assertEqual(scenario.SYSTEM, existing.pop("system"))
        self.assertEqual(scenario.SYSTEM + "\n\n" + scenario.GUIDANCE, guided.pop("system"))
        self.assertEqual(existing, guided)

    def test_upstream_shape_counts_guidance_and_script_declaration_separately(self):
        budget = Budget()
        payload = {"input": [{"type": "additional_tools", "tools": [
            {"type": "custom", "name": "splice_exec"}]}]}
        budget.record_shape(payload)
        payload["input"].append({"role": "developer", "content": "<code_mode_orchestration>guide</code_mode_orchestration>"})
        budget.record_shape(payload)
        self.assertEqual(2, budget.snapshot()["code_tool_requests"])
        self.assertEqual(1, budget.snapshot()["guidance_requests"])

    def test_isolated_comparison_config_changes_only_bridge_policy(self):
        import tomllib
        from code_mode_compare import configure

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source-auth.json"
            source.write_bytes(b'{"tokens":{"access_token":"synthetic"}}')
            env, control, baseline, code_mode = configure(root, source, 12345)
            config = tomllib.loads(Path(env["SPLICE_CONFIG"]).read_text())
            providers = config["providers"]
            self.assertFalse(providers["baseline"]["quirks"].pop("code_mode"))
            self.assertTrue(providers["code_mode"]["quirks"].pop("code_mode"))
            self.assertEqual(providers["baseline"], providers["code_mode"])
            self.assertTrue(providers["baseline"]["quirks"]["tool_surface"]["enabled"])
            self.assertTrue(providers["baseline"]["quirks"].get("account_id_header"))
            self.assertEqual("high", config["daemon"]["effort"])
            self.assertEqual(3, len({control, baseline, code_mode}))
            self.assertEqual(source.read_bytes(), (root / "auth.json").read_bytes())
            self.assertEqual(0o600, (root / "auth.json").stat().st_mode & 0o777)
            self.assertTrue(env["CODEX_OAUTH_TOKEN_URL"].startswith("http://127.0.0.1:"))
            self.assertEqual("off", env["CLAUDEX_QUOTA_POLL"])

    def test_isolated_comparison_tears_down_without_vendor_requests(self):
        from code_mode_compare import run

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact, auth, receipt = [root / name for name in ("app.jar", "auth.json", "receipt.json")]
            artifact.write_bytes(b"synthetic jar")
            auth.write_bytes(b'{"tokens":{"access_token":"synthetic"}}')
            args = argparse.Namespace(artifact=str(artifact), auth_file=str(auth), receipt=str(receipt))

            def comparison(_):
                receipt.write_text('{"runs":[],"failure":null}')

            previous = os.environ.get("SPLICE_PROBE_BEARER")
            with patch("code_mode_compare.run_comparison", side_effect=comparison), \
                    patch("code_mode_compare.request_json", return_value={"ok": True, "readyHeads": 2}), \
                    patch("code_mode_compare.subprocess.Popen") as launch:
                launch.return_value.poll.return_value = None
                run(args)
            launch.return_value.terminate.assert_called_once()
            launch.return_value.wait.assert_called_once_with(timeout=10)
            self.assertEqual(previous, os.environ.get("SPLICE_PROBE_BEARER"))
            saved = json.loads(receipt.read_text())
            self.assertEqual(0, saved["final_accounting"]["requests"])
            self.assertIsNone(saved["final_accounting"]["error"])
            self.assertEqual(b'{"tokens":{"access_token":"synthetic"}}', auth.read_bytes())

    def test_terminal_usage_is_recorded_before_forwarding(self):
        budget = Budget()
        body = json.dumps({"model": "gpt-6-astra", "reasoning": {"effort": "high"}}).encode()
        terminal = b"data: " + json.dumps({"type": "response.completed", "response": {
            "usage": {"input_tokens": INPUT_BUDGET, "output_tokens": 2}, "output": [],
        }}).encode() + b"\n\n"
        handler = MagicMock()
        handler.path = "/responses"
        handler.headers = {"Content-Length": str(len(body)), "Authorization": "Bearer synthetic-upstream"}
        handler.rfile = io.BytesIO(body)

        def check_budget_before_write(chunk):
            self.assertEqual(terminal, chunk)
            self.assertEqual(INPUT_BUDGET, budget.snapshot()["input_tokens"])
            self.assertFalse(budget.reserve(1))

        handler.wfile.write.side_effect = check_budget_before_write
        with patch(__name__ + ".http.client.HTTPSConnection") as connect:
            response = connect.return_value.getresponse.return_value
            response.status = 200
            response.read1.side_effect = [terminal, b""]
            proxy_handler(budget).do_POST(handler)
        handler.wfile.write.assert_called_once()
        self.assertEqual(INPUT_BUDGET, budget.snapshot()["input_tokens"])
        self.assertEqual(2, budget.snapshot()["output_tokens"])

    def test_streamed_calls_count_once_with_sparse_or_repeated_terminal_output(self):
        items = [
            {"type": "custom_tool_call", "id": "script-item", "call_id": "script", "name": "splice_exec"},
            {"type": "tool_search_call", "id": "search-item", "call_id": "search"},
        ]
        for terminal_output in ([], items):
            with self.subTest(terminal_output=bool(terminal_output)):
                budget = Budget()
                events = []
                for index, item in enumerate(items):
                    for kind in ("response.output_item.added", "response.output_item.done"):
                        events.append({"type": kind, "output_index": index, "item": item})
                events.append({"type": "response.completed", "response": {
                    "usage": {"input_tokens": 100, "output_tokens": 10}, "output": terminal_output,
                }})
                chunks = [b"data: " + json.dumps(event).encode() + b"\n\n" for event in events] + [b""]
                body = json.dumps({"model": "gpt-6-astra", "reasoning": {"effort": "high"}}).encode()
                handler = MagicMock()
                handler.path = "/responses"
                handler.headers = {"Content-Length": str(len(body)), "Authorization": "Bearer synthetic-upstream"}
                # Dedupe is response-local: a later response may reuse an item identifier.
                for expected in (1, 2):
                    handler.rfile = io.BytesIO(body)
                    with patch(__name__ + ".http.client.HTTPSConnection") as connect:
                        response = connect.return_value.getresponse.return_value
                        response.status = 200
                        response.read1.side_effect = chunks
                        proxy_handler(budget).do_POST(handler)
                    self.assertEqual(expected, budget.snapshot()["code_calls"])
                    self.assertEqual(expected, budget.snapshot()["search_calls"])

    def test_other_model_or_effort_is_rejected_before_vendor_dispatch(self):
        for payload in ([1], {"model": "other"}, {"model": "gpt-6-astra", "reasoning": None},
                        {"model": "gpt-6-astra", "reasoning": {"effort": "low"}}):
            with self.subTest(payload=payload):
                budget = Budget()
                body = json.dumps(payload).encode()
                handler = MagicMock()
                handler.path = "/responses"
                handler.headers = {"Content-Length": str(len(body)), "Authorization": "Bearer synthetic-upstream"}
                handler.rfile = io.BytesIO(body)
                with patch(__name__ + ".http.client.HTTPSConnection") as connect:
                    proxy_handler(budget).do_POST(handler)
                    connect.assert_not_called()
                self.assertEqual(0, budget.snapshot()["requests"])
                handler.send_error.assert_called_once()

    def test_request_budget_is_enforced_before_dispatch(self):
        budget = Budget()
        for _ in range(MAX_REQUESTS):
            self.assertTrue(budget.reserve(10))
        self.assertFalse(budget.reserve(10))
        self.assertEqual(MAX_REQUESTS, budget.snapshot()["requests"])

    def test_missing_usage_stops_future_calls(self):
        budget = Budget()
        budget.finish(None)
        self.assertFalse(budget.reserve(1))

    def test_token_threshold_stops_future_calls(self):
        budget = Budget()
        budget.finish({"input_tokens": INPUT_BUDGET, "output_tokens": 0})
        self.assertFalse(budget.reserve(1))

    def test_incomplete_usage_stops_future_calls(self):
        budget = Budget()
        budget.finish({"input_tokens": 1})
        self.assertFalse(budget.reserve(1))

    def test_invalid_cache_usage_halts_but_keeps_known_token_spend(self):
        for details in (None, {"cached_tokens": -1}, {"cached_tokens": "1"}, {"cached_tokens": 11}):
            with self.subTest(details=details):
                budget = Budget()
                budget.finish({"input_tokens": 10, "output_tokens": 2, "input_tokens_details": details})
                self.assertFalse(budget.reserve(1))
                self.assertEqual(10, budget.snapshot()["input_tokens"])
                self.assertEqual(2, budget.snapshot()["output_tokens"])

    def test_in_flight_usage_is_accounted_after_halt(self):
        budget = Budget()
        budget.finish(None)
        budget.finish({"input_tokens": 10, "output_tokens": 2})
        self.assertFalse(budget.reserve(1))
        self.assertEqual(10, budget.snapshot()["input_tokens"])

    def test_metrics_failure_retains_executed_case_in_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "synthetic.jar"
            artifact.write_bytes(b"synthetic artifact")
            receipt = Path(directory) / "receipt.json"
            args = argparse.Namespace(artifact=str(artifact), receipt=str(receipt), model="gpt-6-astra",
                                      baseline_port=1, code_mode_port=2, metrics_port=3)
            with patch(__name__ + ".request_json", side_effect=[Budget().snapshot(), OSError("private detail")]), \
                    patch(__name__ + ".run_case", return_value={"passed": True}):
                with self.assertRaises(OSError):
                    run_comparison(args)
            saved = json.loads(receipt.read_text())
            self.assertEqual(1, saved["completed_runs"])
            self.assertFalse(saved["runs"][0]["accounting_complete"])
            self.assertEqual("OSError", saved["failure"])
            self.assertNotIn("private detail", receipt.read_text())

    def test_repeated_read_cannot_replace_missing_file_evidence(self):
        workspace = Workspace()
        for _ in range(3):
            workspace.execute({"name": "Read", "input": {"file_path": "a.txt"}})
        self.assertFalse(workspace.correct("independent-reads", "110"))
        for path in ("b.txt", "c.txt"):
            workspace.execute({"name": "Read", "input": {"file_path": path}})
        self.assertTrue(workspace.correct("independent-reads", "The sum is 110."))

    def test_tools_are_fake_and_unknown_operations_fail(self):
        workspace = Workspace()
        self.assertEqual("21", workspace.execute({"name": "Read", "input": {"file_path": "a.txt"}}))
        with self.assertRaises(ValueError):
            workspace.execute({"name": "Bash", "input": {"command": "anything"}})

    def test_failed_request_is_a_failed_row_without_exception_content(self):
        with patch(__name__ + ".request_json", side_effect=ValueError("private vendor content")):
            row = run_case(1, "gpt-6-astra", *CASES[0])
        self.assertFalse(row["passed"])
        self.assertEqual("ValueError", row["error"])
        self.assertNotIn("private vendor content", json.dumps(row))
        self.assertEqual(1, row["client_requests"])

    def test_invalid_tool_is_counted_in_failed_row(self):
        response = {"content": [{"type": "tool_use", "id": "call-1", "name": "Bash", "input": {}}]}
        with patch(__name__ + ".request_json", return_value=response):
            row = run_case(1, "gpt-6-astra", *CASES[0])
        self.assertFalse(row["passed"])
        self.assertEqual(1, row["tool_calls"])
        self.assertEqual(1, row["failed_tool_calls"])

    def test_repeat_counts_use_arguments_not_only_tool_names(self):
        workspace = Workspace()
        for path in ("a.txt", "b.txt", "a.txt"):
            workspace.execute({"name": "Read", "input": {"file_path": path}})
        self.assertEqual(1, workspace.repeated_calls)

    def test_notification_is_not_a_poll(self):
        workspace = Workspace()
        workspace.execute({"name": "Agent", "input": {"prompt": "7 times 8"}})
        self.assertIsNotNone(workspace.notification)
        self.assertIn("56", workspace.notification or "")
        self.assertTrue(workspace.correct("background-result", "56"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--proxy-port", type=int)
    parser.add_argument("--baseline-port", type=int)
    parser.add_argument("--code-mode-port", type=int)
    parser.add_argument("--metrics-port", type=int)
    parser.add_argument("--model", default="gpt-6-astra")
    parser.add_argument("--artifact")
    parser.add_argument("--receipt")
    args = parser.parse_args()
    if args.selftest:
        unittest.main(argv=[__file__])
    elif args.proxy_port:
        server = ThreadingHTTPServer(("127.0.0.1", args.proxy_port), proxy_handler(Budget()))
        print(f"Budget proxy listening on loopback:{server.server_port}", flush=True)
        try:
            server.serve_forever()
        finally:
            server.server_close()
    elif all((args.baseline_port, args.code_mode_port, args.metrics_port, args.artifact, args.receipt)):
        run_comparison(args)
    else:
        parser.error("choose --selftest, --proxy-port, or all comparison arguments")
