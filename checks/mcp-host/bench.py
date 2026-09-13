#!/usr/bin/env python3
"""Shared MCP hosting benchmark (FEATURES.md §8): N parallel Claude Code sessions, the same
stdio MCP servers, once launched per session (Claude Code's own behaviour) and once through the
splice host. The receipt records the PEAK summed RSS of the MCP server processes in each mode
(the denominator the >50% target is measured against), the daemon's RSS delta while hosting,
whole-workload RSS, and wall time.

Usage:
  bench.py --control-port 3196 --mgmt-key-file ~/.claude-codex/state/mgmt-key \\
           --servers ast-grep,remotion-docs,torad-fleet,figma-comments --sessions 4 \\
           --out checks/e2e/receipts/mcp-host-bench.json

Each session is `claude -p` with --strict-mcp-config and a prompt that lists the tools and stops,
so the servers are actually initialized and queried. The tool surface is read from Claude Code's
own stream-json `system/init` event (the tools it loaded and each MCP server's connection status),
never from the model's prose — a session's answer once named tools of servers it did not have
(review 2, 2026-09-13). Sampling is /proc based (Linux only).
"""
from __future__ import annotations

import argparse
import json
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

PROMPT = "List the names of every MCP tool you have available, one per line, then stop. Do not call any tool."
SAMPLE_S = 0.5


def read_global_servers() -> dict:
    return json.load(open(Path.home() / ".claude.json")).get("mcpServers", {})


def proc_table() -> list[tuple[int, int, int, str]]:
    """(pid, ppid, rss_kb, cmdline) for every process we can read."""
    out = []
    for p in Path("/proc").iterdir():
        if not p.name.isdigit():
            continue
        try:
            stat = (p / "status").read_text()
            cmd = (p / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace").strip()
        except OSError:
            continue
        rss = ppid = 0
        for line in stat.splitlines():
            if line.startswith("VmRSS:"):
                rss = int(line.split()[1])
            elif line.startswith("PPid:"):
                ppid = int(line.split()[1])
        out.append((int(p.name), ppid, rss, cmd))
    return out


def descendants(root_pids: set[int], table) -> set[int]:
    children: dict[int, list[int]] = {}
    for pid, ppid, _, _ in table:
        children.setdefault(ppid, []).append(pid)
    seen, stack = set(), list(root_pids)
    while stack:
        pid = stack.pop()
        for c in children.get(pid, []):
            if c not in seen:
                seen.add(c)
                stack.append(c)
    return seen


class Sampler(threading.Thread):
    """Peak summed RSS, sampled while the sessions run, of:
    (a) MCP server processes — every descendant of the client sessions that is not a claude
        binary (unhosted), plus every descendant of the daemon (hosted); process-tree based so
        npm/uvx wrappers and their grandchildren count the same way in both modes;
    (b) the whole workload (sessions + everything under them + the daemon tree);
    (c) the daemon JVM itself."""

    def __init__(self, session_pids: set[int], daemon_pid: int | None):
        super().__init__(daemon=True)
        self.session_pids = session_pids
        self.daemon_pid = daemon_pid
        self.peak_servers = 0
        self.peak_workload = 0
        self.peak_daemon = 0
        self.server_pids_seen: set[int] = set()
        self.stop = threading.Event()

    def run(self):
        while not self.stop.is_set():
            table = proc_table()
            under_sessions = descendants(self.session_pids, table)
            under_daemon = descendants({self.daemon_pid}, table) if self.daemon_pid else set()
            servers = workload = daemon = 0
            for pid, _, rss, cmd in table:
                is_claude = "claude" in cmd.split(" ")[0]
                if (pid in under_sessions and not is_claude) or pid in under_daemon:
                    servers += rss
                    self.server_pids_seen.add(pid)
                if pid in under_sessions or pid in self.session_pids or pid in under_daemon:
                    workload += rss
                if pid == self.daemon_pid:
                    daemon = rss
            self.peak_servers = max(self.peak_servers, servers)
            self.peak_workload = max(self.peak_workload, workload + daemon)
            self.peak_daemon = max(self.peak_daemon, daemon)
            time.sleep(SAMPLE_S)


def find_daemon_pid() -> int | None:
    """The one splice JVM running `app-all.jar daemon`; pass --daemon-pid when several run."""
    pids = [pid for pid, _, _, cmd in proc_table() if "app-all.jar daemon" in cmd and cmd.split(" ")[0].endswith("java")]
    return pids[0] if len(pids) == 1 else None


def daemon_jar_sha256(pid: int) -> str | None:
    """The jar the daemon actually runs — the receipt names the artifact it measured."""
    argv = Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0")
    jar = next((argv[i + 1].decode() for i, a in enumerate(argv) if a == b"-jar"), None)
    return hashlib.sha256(Path(jar).read_bytes()).hexdigest() if jar and Path(jar).exists() else None


def init_event(output: str) -> dict:
    """Claude Code's `system/init` event from stream-json output; {} when the session never got there."""
    for line in output.splitlines():
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        if msg.get("type") == "system" and msg.get("subtype") == "init":
            return msg
    return {}


def tools_by_server(init: dict) -> dict[str, list[str]]:
    """The exact MCP tool names the session LOADED, grouped by server (`mcp__<server>__<tool>`).
    Identity, not a count: equal totals could hide one server's tools replaced by another's."""
    by_server: dict[str, set[str]] = {}
    for name in init.get("tools", []):
        # Split on the delimiter only; a tool name is whatever the protocol allowed (dots included).
        if not name.startswith("mcp__") or "__" not in name[5:]:
            continue
        server, tool = name[5:].split("__", 1)
        if server and tool:
            by_server.setdefault(server, set()).add(tool)
    return {s: sorted(t) for s, t in sorted(by_server.items())}


def selftest() -> int:
    """The parser keeps dotted and hyphenated tool names and groups by server (review 3)."""
    init = {"tools": ["Read", "mcp__remotion-docs__remotion-documentation", "mcp__fs__files.read",
                      "mcp__fs__files.write_v2", "mcp__x__", "mcp____t"],
            "mcp_servers": [{"name": "fs", "status": "connected"}]}
    got = tools_by_server(init)
    want = {"fs": ["files.read", "files.write_v2"], "remotion-docs": ["remotion-documentation"]}
    if got != want:
        print(f"SELFTEST FAILED: {got} != {want}", file=sys.stderr)
        return 1
    if server_status(init) != {"fs": "connected"}:
        print("SELFTEST FAILED: server status", file=sys.stderr)
        return 1
    print("bench selftest: PASS")
    return 0


def server_status(init: dict) -> dict[str, str]:
    return {s["name"]: s.get("status", "?") for s in init.get("mcp_servers", [])}


def run_sessions(n: int, mcp_config: dict, model: str, daemon_pid: int | None) -> dict:
    with tempfile.TemporaryDirectory() as tmp:
        cfg = Path(tmp) / "mcp.json"
        cfg.write_text(json.dumps({"mcpServers": mcp_config}))
        procs = []
        for i in range(n):
            procs.append(
                subprocess.Popen(
                    ["claude", "-p", PROMPT, "--model", model, "--mcp-config", str(cfg), "--strict-mcp-config",
                     "--output-format", "stream-json", "--verbose"],
                    cwd=tmp,
                    stdout=open(Path(tmp) / f"out{i}.txt", "w"),
                    stderr=subprocess.STDOUT,
                    env={**os.environ, "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1"},
                )
            )
        sampler = Sampler({p.pid for p in procs}, daemon_pid)
        t0 = time.time()
        sampler.start()
        for p in procs:
            p.wait()
        sampler.stop.set()
        sampler.join()
        full = [(Path(tmp) / f"out{i}.txt").read_text() for i in range(n)]
        inits = [init_event(o) for o in full]
        outputs = [o[:400] for o in full]
        return {
            "sessions": n,
            "exit_codes": [p.returncode for p in procs],
            "mcp_servers": [server_status(i) for i in inits],
            "mcp_tools": [tools_by_server(i) for i in inits],
            "mcp_tools_listed": [sum(len(v) for v in tools_by_server(i).values()) for i in inits],
            "wall_s": round(time.time() - t0, 1),
            "peak_server_rss_kb": sampler.peak_servers,
            "peak_workload_rss_kb": sampler.peak_workload,
            "peak_daemon_rss_kb": sampler.peak_daemon,
            "server_pids_seen": len(sampler.server_pids_seen),
            "outputs_head": outputs,
        }


def main() -> int:
    if sys.argv[1:] == ["--selftest"]:
        return selftest()
    ap = argparse.ArgumentParser()
    ap.add_argument("--control-port", type=int, required=True)
    ap.add_argument("--mgmt-key-file", required=True)
    ap.add_argument("--servers", required=True, help="comma-separated names from ~/.claude.json mcpServers")
    ap.add_argument("--sessions", type=int, default=4)
    ap.add_argument("--model", default="claude-haiku-4-5-20251001")
    ap.add_argument("--daemon-pid", type=int, default=None)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    names = [s.strip() for s in args.servers.split(",") if s.strip()]
    global_servers = read_global_servers()
    missing = [n for n in names if n not in global_servers]
    if missing:
        print(f"not in ~/.claude.json mcpServers: {missing}", file=sys.stderr)
        return 2
    key = Path(os.path.expanduser(args.mgmt_key_file)).read_text().strip()
    unhosted = {n: global_servers[n] for n in names}
    hosted = {
        n: {"type": "http", "url": f"http://127.0.0.1:{args.control_port}/mcp/{n}", "headers": {"Authorization": f"Bearer {key}"}}
        for n in names
    }
    daemon_pid = args.daemon_pid or find_daemon_pid()
    if daemon_pid is None:
        print("no splice daemon found (pass --daemon-pid)", file=sys.stderr)
        return 2

    print(f"unhosted: {args.sessions} sessions x {len(names)} servers")
    a = run_sessions(args.sessions, unhosted, args.model, None)
    print(json.dumps({k: v for k, v in a.items() if k != "outputs_head"}))
    print(f"hosted via splice :{args.control_port}")
    b = run_sessions(args.sessions, hosted, args.model, daemon_pid)
    print(json.dumps({k: v for k, v in b.items() if k != "outputs_head"}))

    saving = 1 - (b["peak_server_rss_kb"] / a["peak_server_rss_kb"]) if a["peak_server_rss_kb"] else None
    # Fail closed: a receipt is only written when the run PROVES the claim — every session exited 0
    # in both modes, every session saw the same non-empty tool surface hosted as unhosted, and the
    # saving clears the 50% bar. Anything else is a failed benchmark, not a receipt.
    problems = []
    if any(code != 0 for code in a["exit_codes"] + b["exit_codes"]):
        problems.append(f"session exit codes unhosted={a['exit_codes']} hosted={b['exit_codes']}")
    # Every session, both modes, must list EVERY requested server with the SAME tool names — per
    # server, by name, never by total count.
    for mode, run in (("unhosted", a), ("hosted", b)):
        for i, (tools, status) in enumerate(zip(run["mcp_tools"], run["mcp_servers"])):
            absent = [s for s in names if not tools.get(s)]
            if absent:
                problems.append(f"{mode} session {i} loaded no tools for {absent}")
            down = {s: status.get(s) for s in names if status.get(s) != "connected"}
            if down:
                problems.append(f"{mode} session {i} MCP server status not connected: {down}")
    if a["mcp_tools"] != b["mcp_tools"]:
        problems.append(f"tool surface differs between modes: unhosted={a['mcp_tools']} hosted={b['mcp_tools']}")
    if saving is None or saving < 0.5:
        problems.append(f"server RSS saving {saving} is below the 50% bar")
    if problems:
        for problem in problems:
            print(f"BENCH FAILED: {problem}", file=sys.stderr)
        return 1
    receipt = {
        "kind": "mcp-host-bench",
        "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "servers": names,
        "daemon_pid": daemon_pid,
        "daemon_jar_sha256": daemon_jar_sha256(daemon_pid),
        "model": args.model,
        "unhosted": a,
        "hosted": b,
        "server_rss_saving": None if saving is None else round(saving, 3),
        "note": "server_rss = peak summed RSS of the MCP server process trees: under the sessions when unhosted, "
        "under the daemon when hosted (the >50% denominator); "
        "workload = every process under the sessions (+ daemon when hosted); daemon = the splice JVM.",
    }
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"receipt -> {args.out}; server RSS saving = {receipt['server_rss_saving']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
