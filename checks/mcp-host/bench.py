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
import hmac
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

PROMPT = "List the names of every MCP tool you have available, one per line, then stop. Do not call any tool."
SAMPLE_S = 0.5
SESSION_TIMEOUT_S = 180
TERMINATE_GRACE_S = 5
SAMPLER_JOIN_S = 1
GROUP_POLL_S = 0.05
MCP_SCOPE = b"splice:mcp-access:v1"


def read_global_servers() -> dict:
    return json.load(open(Path.home() / ".claude.json")).get("mcpServers", {})


def mcp_access_bearer(management_secret: str) -> str:
    """Domain-separated HMAC bearer for MCP access; never send the management secret."""
    return hmac.new(management_secret.encode(), MCP_SCOPE, hashlib.sha256).hexdigest()


def hosted_servers(names: list[str], control_port: int, mcp_bearer: str) -> dict:
    return {
        name: {"type": "http", "url": f"http://127.0.0.1:{control_port}/mcp/{name}",
               "headers": {"Authorization": f"Bearer {mcp_bearer}"}}
        for name in names
    }


ProcRow = tuple[int, int | None, int | None, str | None]


def stat_ppid(path: Path) -> int | None:
    try:
        fields = path.read_text().rsplit(")", 1)[1].split()
        return int(fields[1])
    except (IndexError, OSError, ValueError):
        return None


def proc_table(proc_root: Path = Path("/proc")) -> list[ProcRow]:
    """(pid, ppid, rss_kb, cmdline) from a /proc snapshot.

    A raced cmdline read retains status-derived linkage. A raced status read falls back to
    `/proc/<pid>/stat` for PPID; without either linkage the row is unrelated/unknown, not evidence
    about this workload. `None` distinguishes unavailable data from a real zero/empty value.
    """
    out = []
    for p in proc_root.iterdir():
        if not p.name.isdigit():
            continue
        pid = int(p.name)
        try:
            stat = (p / "status").read_text()
        except OSError:
            out.append((pid, stat_ppid(p / "stat"), None, None))
            continue
        try:
            cmd = (p / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace").strip()
        except OSError:
            cmd = None
        rss = None
        ppid = None
        for line in stat.splitlines():
            if line.startswith("VmRSS:"):
                try:
                    rss = int(line.split()[1])
                except (IndexError, ValueError):
                    pass
            elif line.startswith("PPid:"):
                try:
                    ppid = int(line.split()[1])
                except (IndexError, ValueError):
                    pass
        out.append((pid, ppid, rss, cmd))
    return out


def proc_alive(pid: int) -> bool:
    return (Path("/proc") / str(pid)).exists()


def descendants(root_pids: set[int], table) -> set[int]:
    children: dict[int | None, list[int]] = {}
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
    (c) the daemon JVM itself. A still-live PID with demonstrable session/daemon lineage that
        disappears or lacks RSS makes measurement incomplete. An exited race and a first-sample
        row with no derivable linkage are not evidence about this workload."""

    def __init__(self, session_pids: set[int], daemon_pid: int | None, pid_exists=proc_alive):
        super().__init__(daemon=True)
        self.session_pids = session_pids
        self.daemon_pid = daemon_pid
        self.pid_exists = pid_exists
        self.peak_servers = 0
        self.peak_workload = 0
        self.peak_daemon = 0
        self.server_pids_seen: set[int] = set()
        self.session_descendants_seen: set[int] = set()
        self.relevant_pids_seen = set(session_pids)
        if daemon_pid:
            self.relevant_pids_seen.add(daemon_pid)
        self.measurement_gaps: set[int] = set()
        self.failed = False
        self.stop = threading.Event()

    def sample(self, table: list[ProcRow]) -> None:
        observed = {pid for pid, _, _, _ in table}
        self.measurement_gaps.update(
            pid for pid in self.relevant_pids_seen - observed if self.pid_exists(pid)
        )
        under_sessions = descendants(self.session_pids, table)
        under_daemon = descendants({self.daemon_pid}, table) if self.daemon_pid else set()
        relevant = self.session_pids | under_sessions | under_daemon
        if self.daemon_pid:
            relevant.add(self.daemon_pid)
        self.session_descendants_seen.update(under_sessions)
        self.relevant_pids_seen.update(relevant)
        servers = workload = daemon = 0
        for pid, _, rss, cmd in table:
            is_claude = cmd is not None and "claude" in cmd.split(" ")[0]
            is_server = (pid in under_sessions and not is_claude) or pid in under_daemon
            is_workload = pid in self.session_pids or pid in under_sessions or pid in under_daemon
            if is_server:
                self.server_pids_seen.add(pid)
            if pid in self.relevant_pids_seen and rss is None:
                self.measurement_gaps.add(pid)
            if rss is None:
                continue
            if is_server:
                servers += rss
            if is_workload:
                workload += rss
            if pid == self.daemon_pid:
                daemon = rss
        self.peak_servers = max(self.peak_servers, servers)
        self.peak_workload = max(self.peak_workload, workload + daemon)
        self.peak_daemon = max(self.peak_daemon, daemon)

    def run(self):
        try:
            while not self.stop.is_set():
                self.sample(proc_table())
                time.sleep(SAMPLE_S)
        except Exception:
            self.failed = True


def find_daemon_pid() -> int | None:
    """The one splice JVM running `app-all.jar daemon`; pass --daemon-pid when several run."""
    pids = [pid for pid, _, _, cmd in proc_table() if cmd and "app-all.jar daemon" in cmd and cmd.split(" ")[0].endswith("java")]
    return pids[0] if len(pids) == 1 else None


def daemon_jar_sha256(pid: int, proc_root: Path = Path("/proc")) -> str | None:
    """The jar the daemon actually runs — resolving relative `-jar` paths from its own cwd."""
    try:
        proc = proc_root / str(pid)
        argv = (proc / "cmdline").read_bytes().split(b"\0")
        cwd = (proc / "cwd").resolve()
        jar = next((argv[i + 1].decode() for i in range(len(argv) - 1) if argv[i] == b"-jar"), None)
        path = Path(jar) if jar else None
        if path and not path.is_absolute():
            path = cwd / path
        return hashlib.sha256(path.read_bytes()).hexdigest() if path and path.exists() else None
    except OSError:
        return None


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
    """Mocked parser, /proc, and child-process tests; never launches a daemon or client."""
    try:
        init = {"tools": ["Read", "mcp__remotion-docs__remotion-documentation", "mcp__fs__files.read",
                          "mcp__fs__files.write_v2", "mcp__x__", "mcp____t"],
                "mcp_servers": [{"name": "fs", "status": "connected"}]}
        want = {"fs": ["files.read", "files.write_v2"], "remotion-docs": ["remotion-documentation"]}
        if tools_by_server(init) != want or server_status(init) != {"fs": "connected"}:
            raise AssertionError("tool parser or server status")

        raw_secret = "bench-fixture-management-secret"
        bearer = mcp_access_bearer(raw_secret)
        if bearer != "c815d9ac36f92c0cf480e934f0a8f17535d7fbe8401daa54b983ac23680a8be4" or bearer == raw_secret:
            raise AssertionError("MCP access HMAC vector")
        hosted = hosted_servers(["fixture"], 3196, bearer)
        if hosted["fixture"]["headers"]["Authorization"] != f"Bearer {bearer}" or raw_secret in str(hosted):
            raise AssertionError("hosted config leaked management secret")

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "proc"
            proc = root / "77"
            daemon_cwd = Path(tmp) / "daemon"
            proc.mkdir(parents=True)
            daemon_cwd.mkdir()
            jar = daemon_cwd / "app-all.jar"
            jar.write_bytes(b"fixture jar")
            (proc / "cmdline").write_bytes(b"java\0-jar\0app-all.jar\0daemon\0")
            (proc / "cwd").symlink_to(daemon_cwd, target_is_directory=True)
            expected_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
            if daemon_jar_sha256(77, root) != expected_hash:
                raise AssertionError("relative daemon jar hash")

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "proc"
            for pid, status, cmd in (
                (10, "PPid:\t1\nVmRSS:\t10 kB\n", b"/usr/bin/claude\0"),
                (11, "PPid:\t10\nVmRSS:\t20 kB\n", None),
                (12, "PPid:\t10\n", b"node mcp\0"),
                (13, None, None),
                (14, None, None),
                (99, "PPid:\t1\n", b"other\0"),
            ):
                process = root / str(pid)
                process.mkdir(parents=True)
                if status is not None:
                    (process / "status").write_text(status)
                if cmd is not None:
                    (process / "cmdline").write_bytes(cmd)
            (root / "13" / "stat").write_text("13 (fixture) S 10 0")
            table = proc_table(root)
            child = next(row for row in table if row[0] == 11)
            status_race = next(row for row in table if row[0] == 13)
            if child[1:] != (10, 20, None) or status_race[1:] != (10, None, None):
                raise AssertionError(f"first-sample linkage {child}, {status_race}")
            first_sample = Sampler({10}, None, pid_exists=lambda pid: pid in {10, 11, 12, 13, 14})
            first_sample.sample(table)
            if first_sample.peak_servers != 20 or first_sample.measurement_gaps != {12, 13}:
                raise AssertionError(f"first-sample classification {first_sample.peak_servers}, {first_sample.measurement_gaps}")

        live = {10, 11, 20, 21, 99}
        sampler = Sampler({10}, 20, pid_exists=live.__contains__)
        sampler.sample([(10, 1, 10, "/usr/bin/claude"), (11, 10, 20, "node server"),
                        (20, 1, 30, "java"), (21, 20, 40, "node hosted"), (99, 1, 50, "other")])
        sampler.sample([(10, 1, 10, "/usr/bin/claude"), (20, 1, 30, "java")])
        if sampler.peak_workload != 100:
            raise AssertionError(f"workload double-counted daemon {sampler.peak_workload}")
        if sampler.measurement_gaps != {11, 21}:
            raise AssertionError(f"live descendant gaps {sampler.measurement_gaps}")
        exited = Sampler({10}, None, pid_exists=lambda pid: pid == 10)
        exited.sample([(10, 1, 10, "/usr/bin/claude"), (11, 10, 20, "node server")])
        exited.sample([(10, 1, 10, "/usr/bin/claude")])
        if exited.measurement_gaps:
            raise AssertionError(f"exited descendant gap {exited.measurement_gaps}")
        unreadable = Sampler({10}, None, pid_exists=lambda pid: pid in {10, 11})
        unreadable.sample([(10, 1, 10, "/usr/bin/claude"), (11, 10, 20, "node server")])
        unreadable.sample([(10, 1, 10, "/usr/bin/claude"), (11, None, None, None)])
        if unreadable.measurement_gaps != {11}:
            raise AssertionError(f"known unreadable descendant gap {unreadable.measurement_gaps}")

        failure = Sampler({10}, None, pid_exists=lambda _: False)
        tables = iter(([(10, 1, 10, "/usr/bin/claude")], OSError("fixture")))
        old_proc_table, old_sleep = globals()["proc_table"], time.sleep

        def failing_proc_table():
            table = next(tables)
            if isinstance(table, Exception):
                raise table
            return table

        try:
            globals()["proc_table"] = failing_proc_table
            time.sleep = lambda _: None
            failure.run()
        finally:
            globals()["proc_table"] = old_proc_table
            time.sleep = old_sleep
        if not failure.failed or failure.peak_workload != 10:
            raise AssertionError("sampler exception was accepted")

        class UnfinishedSampler(Sampler):
            def join(self, timeout=None):
                self.join_timeout = timeout

            def is_alive(self):
                return True

        unfinished = UnfinishedSampler(set(), None)
        stop_sampler(unfinished)
        if not unfinished.failed or unfinished.join_timeout != SAMPLER_JOIN_S:
            raise AssertionError("unfinished sampler was accepted")

        run = {"exit_codes": [0], "mcp_tools": [{"fixture": ["tool"]}],
               "mcp_servers": [{"fixture": "connected"}], "peak_server_rss_kb": 40,
               "measurement_complete": True, "measurement_gaps": [], "measurement_failure": False}
        if not any("jar SHA-256" in p for p in receipt_problems(run, run, ["fixture"], 0.6, None)):
            raise AssertionError("missing daemon hash accepted")
        zero_hosted = {**run, "peak_server_rss_kb": 0}
        if not any("hosted server RSS was zero" == p for p in receipt_problems(run, zero_hosted, ["fixture"], 1.0, "hash")):
            raise AssertionError("zero hosted RSS accepted")
        incomplete = {**run, "measurement_complete": False, "measurement_gaps": [21]}
        if not any("measurement incomplete" in p for p in receipt_problems(run, incomplete, ["fixture"], 0.6, "hash")):
            raise AssertionError("incomplete workload accepted")
        failed_sampler = {**run, "measurement_complete": False, "measurement_failure": True}
        if not any("sampler failed" in p for p in receipt_problems(run, failed_sampler, ["fixture"], 0.6, "hash")):
            raise AssertionError("failed sampler accepted")

        class FakeProcess:
            def __init__(self, pid):
                self.pid = pid
                self.returncode = None
                self.wait_timeouts = []

            def poll(self):
                return self.returncode

            def wait(self, timeout=None):
                self.wait_timeouts.append(timeout)
                self.returncode = 0
                return 0

        signals = []
        proc = FakeProcess(101)
        old_killpg, old_getpgid = os.killpg, os.getpgid

        def ending_group(pid, sig):
            signals.append((pid, sig))
            if sig == signal.SIGTERM:
                proc.returncode = 0

        try:
            os.killpg = ending_group
            os.getpgid = lambda pid: 101 if proc.returncode is None and pid == 101 else (_ for _ in ()).throw(ProcessLookupError())
            terminate_owned_children([proc], grace_s=0, proc_snapshot=lambda: [])
        finally:
            os.killpg, os.getpgid = old_killpg, old_getpgid
        if signals != [(101, signal.SIGTERM)]:
            raise AssertionError(f"owned group cleanup signals {signals}")

        def failed_snapshot():
            raise OSError("synthetic proc failure")

        signals.clear()
        proc.returncode = None
        try:
            os.killpg = ending_group
            os.getpgid = lambda pid: 101 if proc.returncode is None and pid == 101 else (_ for _ in ()).throw(ProcessLookupError())
            terminate_owned_children([proc], grace_s=0, proc_snapshot=failed_snapshot)
        finally:
            os.killpg, os.getpgid = old_killpg, old_getpgid
        if signals != [(101, signal.SIGTERM)]:
            raise AssertionError(f"snapshot failure prevented owned cleanup {signals}")

        leader = FakeProcess(101)
        leader.returncode = 0
        survivors = {202}
        signals = []

        def surviving_group(pid, sig):
            signals.append((pid, sig))
            if sig == signal.SIGKILL:
                survivors.clear()

        try:
            os.killpg = surviving_group
            os.getpgid = lambda pid: 101 if pid in survivors else (_ for _ in ()).throw(ProcessLookupError())
            terminate_owned_children([leader], {202}, grace_s=0, proc_snapshot=lambda: [])
        finally:
            os.killpg, os.getpgid = old_killpg, old_getpgid
        if signals != [(101, signal.SIGTERM), (101, signal.SIGKILL)]:
            raise AssertionError(f"surviving owned group cleanup signals {signals}")

        signals = []
        try:
            os.killpg = lambda pid, sig: signals.append((pid, sig))
            os.getpgid = lambda pid: (_ for _ in ()).throw(ProcessLookupError(pid))
            terminate_owned_children([leader], grace_s=0, proc_snapshot=lambda: [])
        finally:
            os.killpg, os.getpgid = old_killpg, old_getpgid
        if signals:
            raise AssertionError(f"recycled group was signalled {signals}")

        ticks = iter((0.0, 0.0, 1.0, 1.0))
        sleeps = []
        old_clock, old_sleep = time.monotonic, time.sleep
        old_group_alive = globals()["owned_group_alive"]
        try:
            time.monotonic = lambda: next(ticks)
            time.sleep = sleeps.append
            globals()["owned_group_alive"] = lambda *_: True
            wait_for_owned_groups([leader], set(), 0.5)
        finally:
            time.monotonic, time.sleep = old_clock, old_sleep
            globals()["owned_group_alive"] = old_group_alive
        if any(delay < 0 for delay in sleeps):
            raise AssertionError(f"cleanup deadline produced a negative sleep {sleeps}")

        class TimeoutProcess:
            def __init__(self):
                self.timeouts = []

            def wait(self, timeout: float | None = None):
                self.timeouts.append(timeout)
                raise subprocess.TimeoutExpired("claude", timeout if timeout is not None else 0)

        timeout_proc = TimeoutProcess()
        try:
            wait_for_clients([timeout_proc])
        except subprocess.TimeoutExpired:
            pass
        else:
            raise AssertionError("unbounded client wait")
        if not 0 <= timeout_proc.timeouts[0] <= SESSION_TIMEOUT_S:
            raise AssertionError(f"client wait bound {timeout_proc.timeouts}")

        spawned = FakeProcess(202)
        popen_calls, cleaned = [], []
        old_popen = subprocess.Popen
        old_cleanup = terminate_owned_children

        def fake_popen(*args, **kwargs):
            if not args or args[0][0] != "claude":
                raise AssertionError("unexpected fixture launch")
            popen_calls.append(kwargs)
            if len(popen_calls) == 1:
                return spawned
            raise KeyboardInterrupt

        try:
            subprocess.Popen = fake_popen
            globals()["terminate_owned_children"] = lambda procs, *_: cleaned.extend(p.pid for p in procs)
            try:
                run_sessions(2, {}, "fixture", None)
            except KeyboardInterrupt:
                pass
            else:
                raise AssertionError("partial spawn interruption")
        finally:
            subprocess.Popen = old_popen
            globals()["terminate_owned_children"] = old_cleanup
        if cleaned != [202] or not popen_calls[0].get("start_new_session"):
            raise AssertionError(f"partial spawn cleanup {cleaned}, Popen={popen_calls}")
    except AssertionError as error:
        print(f"SELFTEST FAILED: {error}", file=sys.stderr)
        return 1
    print("bench selftest: PASS (MCP HMAC, parser, scoped first-sample coverage, workload, sampler failure, receipt guards, group cleanup, bounded wait)")
    return 0


def server_status(init: dict) -> dict[str, str]:
    return {s["name"]: s.get("status", "?") for s in init.get("mcp_servers", [])}


def wait_for_clients(procs) -> None:
    """One deadline bounds the whole client workload, not one unbounded wait per client."""
    deadline = time.monotonic() + SESSION_TIMEOUT_S
    for proc in procs:
        proc.wait(timeout=max(0, deadline - time.monotonic()))


def owned_group_alive(proc, owned_pids: set[int]) -> bool:
    """Whether a group still has a direct child or a sampled descendant we created."""
    pids = set(owned_pids)
    if proc.poll() is None:
        pids.add(proc.pid)
    for pid in pids:
        try:
            if os.getpgid(pid) == proc.pid:
                return True
        except ProcessLookupError:
            continue
    return False


def wait_for_owned_groups(procs, owned_pids: set[int], grace_s: float):
    deadline = time.monotonic() + grace_s
    while True:
        live = [proc for proc in procs if owned_group_alive(proc, owned_pids)]
        if not live or time.monotonic() >= deadline:
            return live
        time.sleep(max(0, min(GROUP_POLL_S, deadline - time.monotonic())))


def terminate_owned_children(procs, owned_pids: set[int] | None = None, grace_s: float = TERMINATE_GRACE_S,
                             proc_snapshot=proc_table) -> None:
    """End only verified process groups this benchmark created; never touch the external daemon."""
    owned_pids = set(owned_pids or ())
    roots = {proc.pid for proc in procs if proc.poll() is None}
    if roots:
        try:
            owned_pids.update(descendants(roots, proc_snapshot()))
        except OSError:
            # A failed measurement must not prevent cleanup of the children already owned.
            print("BENCH: cleanup snapshot unavailable; using known owned groups", file=sys.stderr)
    groups = [proc for proc in procs if owned_group_alive(proc, owned_pids)]
    for proc in groups:
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except ProcessLookupError:
            continue
    stubborn = wait_for_owned_groups(groups, owned_pids, grace_s)
    for proc in stubborn:
        if owned_group_alive(proc, owned_pids):
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                continue
    wait_for_owned_groups(stubborn, owned_pids, grace_s)


def stop_sampler(sampler: Sampler) -> None:
    sampler.stop.set()
    sampler.join(timeout=SAMPLER_JOIN_S)
    if sampler.is_alive():
        sampler.failed = True


def run_sessions(n: int, mcp_config: dict, model: str, daemon_pid: int | None) -> dict:
    with tempfile.TemporaryDirectory() as tmp:
        cfg = Path(tmp) / "mcp.json"
        cfg.write_text(json.dumps({"mcpServers": mcp_config}))
        procs = []
        output_files = []
        sampler = None
        sampler_started = False
        try:
            for i in range(n):
                output = open(Path(tmp) / f"out{i}.txt", "w")
                output_files.append(output)
                procs.append(
                    subprocess.Popen(
                        ["claude", "-p", PROMPT, "--model", model, "--mcp-config", str(cfg), "--strict-mcp-config",
                         "--output-format", "stream-json", "--verbose"],
                        cwd=tmp,
                        stdout=output,
                        stderr=subprocess.STDOUT,
                        env={**os.environ, "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1"},
                        start_new_session=True,
                    )
                )
            sampler = Sampler({p.pid for p in procs}, daemon_pid)
            sampler.start()
            sampler_started = True
            t0 = time.time()
            wait_for_clients(procs)
        finally:
            if sampler_started and sampler is not None:
                stop_sampler(sampler)
            terminate_owned_children(procs, sampler.session_descendants_seen if sampler else set())
            for output in output_files:
                output.close()
        if sampler is None:
            raise RuntimeError("sampler did not start")
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
            "measurement_complete": not sampler.measurement_gaps and not sampler.failed,
            "measurement_gaps": sorted(sampler.measurement_gaps),
            "measurement_failure": sampler.failed,
            "outputs_head": outputs,
        }


def receipt_problems(a: dict, b: dict, names: list[str], saving: float | None, daemon_hash: str | None) -> list[str]:
    problems = []
    if not daemon_hash:
        problems.append("daemon jar SHA-256 is unavailable")
    if any(code != 0 for code in a["exit_codes"] + b["exit_codes"]):
        problems.append(f"session exit codes unhosted={a['exit_codes']} hosted={b['exit_codes']}")
    # Every session, both modes, must list EVERY requested server with the SAME tool names — per
    # server, by name, never by total count.
    for mode, run in (("unhosted", a), ("hosted", b)):
        if not run["measurement_complete"]:
            if run["measurement_failure"]:
                problems.append(f"{mode} workload measurement incomplete: sampler failed or did not stop")
            else:
                problems.append(f"{mode} workload measurement incomplete: missing live PIDs {run['measurement_gaps']}")
        for i, (tools, status) in enumerate(zip(run["mcp_tools"], run["mcp_servers"])):
            absent = [s for s in names if not tools.get(s)]
            if absent:
                problems.append(f"{mode} session {i} loaded no tools for {absent}")
            down = {s: status.get(s) for s in names if status.get(s) != "connected"}
            if down:
                problems.append(f"{mode} session {i} MCP server status not connected: {down}")
    if a["mcp_tools"] != b["mcp_tools"]:
        problems.append(f"tool surface differs between modes: unhosted={a['mcp_tools']} hosted={b['mcp_tools']}")
    if b["peak_server_rss_kb"] == 0:
        problems.append("hosted server RSS was zero")
    if saving is None or saving < 0.5:
        problems.append(f"server RSS saving {saving} is below the 50% bar")
    return problems


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
    management_secret = Path(os.path.expanduser(args.mgmt_key_file)).read_text().strip()
    unhosted = {n: global_servers[n] for n in names}
    hosted = hosted_servers(names, args.control_port, mcp_access_bearer(management_secret))
    daemon_pid = args.daemon_pid or find_daemon_pid()
    if daemon_pid is None:
        print("no splice daemon found (pass --daemon-pid)", file=sys.stderr)
        return 2
    daemon_hash = daemon_jar_sha256(daemon_pid)
    if not daemon_hash:
        print("BENCH FAILED: daemon jar SHA-256 is unavailable", file=sys.stderr)
        return 2

    try:
        print(f"unhosted: {args.sessions} sessions x {len(names)} servers")
        a = run_sessions(args.sessions, unhosted, args.model, None)
        print(json.dumps({k: v for k, v in a.items() if k != "outputs_head"}))
        print(f"hosted via splice :{args.control_port}")
        b = run_sessions(args.sessions, hosted, args.model, daemon_pid)
        print(json.dumps({k: v for k, v in b.items() if k != "outputs_head"}))
    except subprocess.TimeoutExpired:
        print(f"BENCH FAILED: client workload exceeded {SESSION_TIMEOUT_S}s", file=sys.stderr)
        return 1

    saving = 1 - (b["peak_server_rss_kb"] / a["peak_server_rss_kb"]) if a["peak_server_rss_kb"] else None
    # Fail closed: a receipt is only written when the run PROVES the claim — every session exited 0
    # in both modes, every session saw the same non-empty tool surface hosted as unhosted, the
    # measurement is complete, and the saving clears the 50% bar.
    problems = receipt_problems(a, b, names, saving, daemon_hash)
    if problems:
        for problem in problems:
            print(f"BENCH FAILED: {problem}", file=sys.stderr)
        return 1
    receipt = {
        "kind": "mcp-host-bench",
        "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "servers": names,
        "daemon_pid": daemon_pid,
        "daemon_jar_sha256": daemon_hash,
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
