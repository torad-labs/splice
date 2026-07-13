#!/usr/bin/env python3
"""Red/green proof for the mythos orchestrator routing (walls-first, §17).

Every case drives the REAL orchestrator as a subprocess with a synthetic hook
event against a hermetic copy of the repo's sgconfig + rules (MYTHOS_HOOK_ROOT).
The rules themselves are proven by `ast-grep test`; this suite proves the
ROUTING: proposed-content computation, glob binding via the temp mirror,
severity → decision mapping, the walls grant gate, and fail-closed behavior.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HOOKS_DIR = Path(__file__).resolve().parents[1]
ORCHESTRATOR = HOOKS_DIR / "orchestrator.py"
REPO = HOOKS_DIR.parents[1]

VIOLATION_L1 = 'export function buildRequest(m) {\n  return { model: m, include: ["reasoning.encrypted_content"] };\n}\n'
CLEAN_L1 = "export function buildRequest(m) {\n  return { model: m, stream: true };\n}\n"


class OrchestratorTest(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls._tmp = tempfile.TemporaryDirectory(prefix="mythos-hook-test-")
        cls.root = Path(cls._tmp.name)
        shutil.copy(REPO / "sgconfig.yml", cls.root / "sgconfig.yml")
        shutil.copytree(REPO / ".rules", cls.root / ".rules")

    @classmethod
    def tearDownClass(cls) -> None:
        cls._tmp.cleanup()

    def run_hook(self, lifecycle: str, event: dict, env_extra: dict | None = None, root: Path | None = None):
        env = {**os.environ, "MYTHOS_HOOK_ROOT": str(root or self.root)}
        env.pop("MYTHOS_WALLS_OK", None)
        env.update(env_extra or {})
        proc = subprocess.run(
            [sys.executable, str(ORCHESTRATOR), lifecycle],
            input=json.dumps(event),
            capture_output=True,
            text=True,
            env=env,
            timeout=60,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        decision = json.loads(proc.stdout) if proc.stdout.strip() else None
        return decision, proc.stderr

    def expect_block(self, decision, msg: str | None = None) -> dict:
        self.assertIsNotNone(decision, msg)
        assert isinstance(decision, dict)
        self.assertEqual(decision.get("decision"), "block", msg)
        return decision

    def write_event(self, rel: str, content: str) -> dict:
        return {
            "tool_name": "Write",
            "tool_input": {"file_path": str(self.root / rel), "content": content},
            "cwd": str(self.root),
        }

    # --- PreToolUse: Write routing -------------------------------------------------

    def test_write_violation_blocks_with_rule_id_and_note(self):
        raw, _ = self.run_hook("pretooluse", self.write_event("server/src/codex/translate-request.mjs", VIOLATION_L1))
        decision = self.expect_block(raw, "violation write must block")
        self.assertIn("l1-no-reasoning-replay", decision["reason"])
        self.assertIn("locked non-goal", decision["reason"])

    def test_write_clean_passes(self):
        decision, _ = self.run_hook("pretooluse", self.write_event("server/src/codex/translate-request.mjs", CLEAN_L1))
        self.assertIsNone(decision)

    def test_files_glob_binds_same_shape_elsewhere_passes(self):
        # The l1 rule is path-scoped; the identical shape outside its files: glob is legal.
        decision, _ = self.run_hook("pretooluse", self.write_event("server/src/usage/hud.mjs", VIOLATION_L1))
        self.assertIsNone(decision)

    def test_inline_suppression_is_honored(self):
        suppressed = VIOLATION_L1.replace(
            "  return {", "  // ast-grep-ignore: l1-no-reasoning-replay\n  return {"
        )
        decision, _ = self.run_hook("pretooluse", self.write_event("server/src/codex/translate-request.mjs", suppressed))
        self.assertIsNone(decision)

    def test_tsx_and_css_rules_route(self):
        raw, _ = self.run_hook("pretooluse", self.write_event(
            "webui/src/widgets/UsageMeter/ui.tsx",
            'export const L = () => <span>usage — live</span>;\n',
        ))
        self.assertIn("webui-no-emdash-ui-text", self.expect_block(raw)["reason"])
        raw, _ = self.run_hook("pretooluse", self.write_event(
            "webui/src/app/app.css", ".myx-panel { font-size: 13px; }\n"
        ))
        self.assertIn("webui-css-tokens-only", self.expect_block(raw)["reason"])

    # --- PreToolUse: Edit routing --------------------------------------------------

    def test_edit_introducing_violation_blocks(self):
        target = self.root / "server/src/codex/stream.mjs"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("export function onDone(res) {\n  finish(res);\n}\n", encoding="utf-8")
        event = {
            "tool_name": "Edit",
            "tool_input": {
                "file_path": str(target),
                "old_string": "finish(res);",
                "new_string": 'res.write(`event: message_stop\\n`);',
            },
            "cwd": str(self.root),
        }
        raw, _ = self.run_hook("pretooluse", event)
        decision = self.expect_block(raw, "edit that introduces message_stop outside sse.mjs must block")
        self.assertIn("l3-sole-message-stop-emitter", decision["reason"])
        target.unlink()

    def test_edit_clean_passes_and_multiedit_applies_sequentially(self):
        target = self.root / "server/src/http/server.mjs"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("server.listen(PORT, '127.0.0.1');\n", encoding="utf-8")
        event = {
            "tool_name": "MultiEdit",
            "tool_input": {
                "file_path": str(target),
                "edits": [
                    {"old_string": "PORT", "new_string": "3099"},
                    {"old_string": "'127.0.0.1'", "new_string": "'0.0.0.0'"},
                ],
            },
            "cwd": str(self.root),
        }
        raw, _ = self.run_hook("pretooluse", event)
        decision = self.expect_block(raw, "second edit rebinds to 0.0.0.0 — must block")
        self.assertIn("loopback-bind-only", decision["reason"])
        target.unlink()

    # --- Jurisdiction and walls ----------------------------------------------------

    def test_non_write_tool_passes(self):
        decision, _ = self.run_hook("pretooluse", {"tool_name": "Bash", "tool_input": {"command": "ls"}})
        self.assertIsNone(decision)

    def test_outside_repo_passes(self):
        decision, _ = self.run_hook("pretooluse", {
            "tool_name": "Write",
            "tool_input": {"file_path": "/etc/hosts.test", "content": VIOLATION_L1},
        })
        self.assertIsNone(decision)

    def test_wall_paths_are_grant_gated(self):
        event = self.write_event(".rules/rules/l1-no-reasoning-replay.yml", "id: weakened\n")
        raw, _ = self.run_hook("pretooluse", event)
        decision = self.expect_block(raw, "un-granted wall edit must block")
        self.assertIn("MYTHOS WALLS", decision["reason"])
        decision, _ = self.run_hook("pretooluse", event, env_extra={"MYTHOS_WALLS_OK": "1"})
        self.assertIsNone(decision, "granted wall edit must pass")

    def test_missing_ast_grep_fails_closed(self):
        event = self.write_event("server/src/codex/translate-request.mjs", CLEAN_L1)
        raw, _ = self.run_hook("pretooluse", event, env_extra={"PATH": "/nonexistent"})
        decision = self.expect_block(raw, "missing scanner must fail closed on PreToolUse")
        self.assertIn("HOOK POLICY INCOMPLETE", decision["reason"])

    # --- Stop ----------------------------------------------------------------------

    def test_stop_blocks_on_dirty_tree_and_respects_active_flag(self):
        with tempfile.TemporaryDirectory(prefix="mythos-stop-test-") as tmp:
            root = Path(tmp)
            shutil.copy(REPO / "sgconfig.yml", root / "sgconfig.yml")
            shutil.copytree(REPO / ".rules", root / ".rules")
            decision, _ = self.run_hook("stop", {}, root=root)
            self.assertIsNone(decision, "clean tree must not block stop")
            bad = root / "server/src/codex/translate-request.mjs"
            bad.parent.mkdir(parents=True)
            bad.write_text(VIOLATION_L1, encoding="utf-8")
            raw, _ = self.run_hook("stop", {}, root=root)
            decision = self.expect_block(raw, "dirty tree must block stop")
            self.assertIn("l1-no-reasoning-replay", decision["reason"])
            decision, _ = self.run_hook("stop", {"stop_hook_active": True}, root=root)
            self.assertIsNone(decision, "stop_hook_active must not re-block")

    def test_stop_fails_open_when_scanner_missing(self):
        decision, stderr = self.run_hook("stop", {}, env_extra={"PATH": "/nonexistent"})
        self.assertIsNone(decision, "stop must fail open on infra failure")
        self.assertIn("stop scan unavailable", stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
