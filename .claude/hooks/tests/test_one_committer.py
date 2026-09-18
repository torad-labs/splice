#!/usr/bin/env python3
"""Red/green proof for the one-committer Bash guard (module 09).

The guard blocks a Bash command opening with git add/commit/push unless LEDGER_ORCHESTRATOR=1
is an inline env PREFIX on the command as written, or the console-handover carve-out admits it
(every affected path inside splice-design's own fence). Three things this suite is built to pin:

  1. the grant parse runs on the COMMAND as written — a var set earlier in the shell (or as a
     separate command before `&&`, or passed as an argument) never satisfies it;
  2. the handover carve-out is path-exact — one path outside the set refuses BY NAME, so a
     "one webui path plus one src path" near-miss is refused, not waved through;
  3. the guard is WIRED (law 19): the runner dispatch drives the real module, so a renamed or
     deleted module (or a disabled hook chain) fails the wired cases, not just the direct ones.
"""
from __future__ import annotations

import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HOOKS_DIR = Path(__file__).resolve().parents[1]
MODULE_PATH = HOOKS_DIR / "modules" / "pretooluse" / "09_one_committer.py"
PRETOOLUSE_ENTRY = HOOKS_DIR / "orchestrator" / "pretooluse.py"


def _load_module():
    spec = importlib.util.spec_from_file_location("_one_committer_under_test", MODULE_PATH)
    assert spec is not None and spec.loader is not None, "module 09 not found at its expected path"
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _bash(command: str, cwd: str | None = None) -> dict:
    event = {"tool_name": "Bash", "tool_input": {"command": command}}
    if cwd:
        event["cwd"] = cwd
    return event


def _make_repo() -> str:
    """A hermetic git repo with one committed file, so `git diff --cached` has a base to read."""
    root = Path(tempfile.mkdtemp(prefix="one-committer-repo-"))
    subprocess.run(["git", "-C", str(root), "init", "-q"], check=True)
    subprocess.run(["git", "-C", str(root), "config", "user.email", "t@example.invalid"], check=True)
    subprocess.run(["git", "-C", str(root), "config", "user.name", "t"], check=True)
    (root / "base.txt").write_text("base\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(root), "add", "base.txt"], check=True)
    subprocess.run(["git", "-C", str(root), "commit", "-q", "-m", "base"], check=True)
    return str(root)


def _stage(root: str, rel: str) -> None:
    (Path(root) / rel).parent.mkdir(parents=True, exist_ok=True)
    (Path(root) / rel).write_text(f"# {rel}\n", encoding="utf-8")
    subprocess.run(["git", "-C", root, "add", rel], check=True)


class OneCommitterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.mod = _load_module()

    def result(self, command: str, cwd: str | None = None):
        return self.mod.run(_bash(command, cwd=cwd))

    def assert_blocked(self, command: str, cwd: str | None = None, named: str | None = None):
        result = self.result(command, cwd=cwd)
        self.assertIsNotNone(result, f"expected a block for {command!r}")
        self.assertEqual(result.kind, "block", f"expected kind=block for {command!r}")
        if named is not None:
            self.assertIn(named, result.payload, f"block for {command!r} must name {named!r}")
        return result

    def assert_allowed(self, command: str, cwd: str | None = None):
        self.assertIsNone(self.result(command, cwd=cwd), f"expected no block for {command!r}")

    # --- scope and read-only git --------------------------------------------------

    def test_applies_only_to_bash(self):
        self.assertTrue(self.mod.applies({"tool_name": "Bash"}))
        self.assertFalse(self.mod.applies({"tool_name": "Write"}))

    def test_read_only_git_is_untouched(self):
        for command in ("git status", "git log", "git diff", "git stash list"):
            self.assert_allowed(command)

    # --- the inline-prefix grant --------------------------------------------------

    def test_plain_git_write_is_blocked(self):
        self.assert_blocked("git add foo")
        self.assert_blocked("git commit -am x")
        self.assert_blocked("git push origin main")

    def test_compound_separator_is_blocked(self):
        self.assert_blocked("echo hi && git add foo")

    def test_inline_grant_allows(self):
        self.assert_allowed("LEDGER_ORCHESTRATOR=1 git add foo")

    def test_inline_grant_among_other_vars_allows(self):
        self.assert_allowed("VAR=1 LEDGER_ORCHESTRATOR=1 git -C /repo commit -am msg")

    def test_git_C_option_without_grant_is_blocked(self):
        self.assert_blocked("git -C /repo add foo")

    # --- near-miss forms (the grant is a PREFIX, not a presence) ------------------

    def test_var_set_mid_command_as_own_command_is_blocked(self):
        self.assert_blocked("LEDGER_ORCHESTRATOR=1 && git add foo")

    def test_var_passed_as_argument_is_blocked(self):
        self.assert_blocked("git add foo LEDGER_ORCHESTRATOR=1")

    def test_var_in_commit_message_is_blocked(self):
        self.assert_blocked('git commit -m "LEDGER_ORCHESTRATOR=1"')

    def test_var_set_in_a_previous_command_is_not_inherited(self):
        # The hook sees one command per event; a prior `export` cannot leak into the next.
        self.assert_allowed("export LEDGER_ORCHESTRATOR=1")
        self.assert_blocked("git add foo")

    def test_aliased_git_is_not_caught(self):
        # Documented gap: the guard matches literal `git`; a shell alias is invisible to it.
        self.assert_allowed("alias g=git; g add foo")

    # --- console handover: path-exact carve-out -----------------------------------

    def test_handover_add_inside_passes(self):
        self.assert_allowed("git add webui/x.ts")

    def test_handover_add_outside_is_blocked_by_name(self):
        self.assert_blocked("git add src/y.py", named="src/y.py")

    def test_handover_near_miss_one_webui_one_src_is_blocked(self):
        self.assert_blocked("git add webui/x.ts src/y.py", named="src/y.py")

    def test_handover_exact_files_pass(self):
        self.assert_allowed("git add package-lock.json .dev/campaigns/web-console.toml")

    def test_handover_directory_nested_passes(self):
        self.assert_allowed("git add .dev/web-console/a/b.ts")

    # --- fail-closed: empty or unreadable affected set ----------------------------

    def test_bare_add_is_refused(self):
        self.assert_blocked("git add")

    def test_broad_add_flag_is_refused(self):
        self.assert_blocked("git add -A")

    def test_commit_with_nothing_staged_is_refused(self):
        root = _make_repo()
        try:
            self.assert_blocked("git commit -m x", cwd=root)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_commit_staged_inside_passes(self):
        root = _make_repo()
        try:
            _stage(root, "webui/x.ts")
            self.assert_allowed("git commit -m x", cwd=root)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_commit_staged_outside_is_blocked_by_name(self):
        root = _make_repo()
        try:
            _stage(root, "src/y.py")
            self.assert_blocked("git commit -m x", cwd=root, named="src/y.py")
        finally:
            shutil.rmtree(root, ignore_errors=True)

    # --- law 19: the guard is WIRED, not merely implemented -----------------------

    def _drive_runner(self, command: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(PRETOOLUSE_ENTRY)],
            input=json.dumps(_bash(command)),
            capture_output=True,
            text=True,
            timeout=30,
        )

    def test_runner_dispatches_module_and_blocks(self):
        proc = self._drive_runner("git add foo")
        self.assertEqual(proc.returncode, 2, proc.stderr)
        self.assertIn("one-committer", proc.stderr)

    def test_runner_dispatches_module_and_allows_grant(self):
        proc = self._drive_runner("LEDGER_ORCHESTRATOR=1 git add foo")
        self.assertEqual(proc.returncode, 0, proc.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
