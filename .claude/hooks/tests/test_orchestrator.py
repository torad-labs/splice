#!/usr/bin/env python3
"""Red/green proof for the splice orchestrator routing (walls-first, §17).

Every case drives the REAL orchestrator as a subprocess with a synthetic hook
event against a hermetic copy of the repo's sgconfig + rules (SPLICE_HOOK_ROOT).
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

# An error-severity finding used to prove the orchestrator routes a violation to
# a block. Uses L3 (a `message_stop` literal outside the sole emitter) — the
# former L1 include example was retired 2026-07-14 when reasoning replay shipped.
VIOLATION = 'export function endTurn(res) {\n  res.write("message_stop");\n}\n'
CLEAN = "export function endTurn(res) {\n  res.end();\n}\n"
L3_TARGET = "server/src/codex/stream.mjs"  # a server module that is NOT the emitter


class OrchestratorTest(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls._tmp = tempfile.TemporaryDirectory(prefix="splice-hook-test-")
        cls.root = Path(cls._tmp.name)
        shutil.copy(REPO / "sgconfig.yml", cls.root / "sgconfig.yml")
        shutil.copytree(REPO / ".rules", cls.root / ".rules")

    @classmethod
    def tearDownClass(cls) -> None:
        cls._tmp.cleanup()

    def run_hook(self, lifecycle: str, event: dict, env_extra: dict | None = None, root: Path | None = None):
        env = {**os.environ, "SPLICE_HOOK_ROOT": str(root or self.root)}
        env.pop("SPLICE_WALLS_OK", None)
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
        raw, _ = self.run_hook("pretooluse", self.write_event(L3_TARGET, VIOLATION))
        decision = self.expect_block(raw, "violation write must block")
        self.assertIn("l3-sole-message-stop-emitter", decision["reason"])
        self.assertIn("emitTerminal", decision["reason"])

    def test_write_clean_passes(self):
        decision, _ = self.run_hook("pretooluse", self.write_event(L3_TARGET, CLEAN))
        self.assertIsNone(decision)

    def test_files_glob_binds_same_shape_elsewhere_passes(self):
        # l3 is path-scoped: it ignores the sole emitter, so the identical
        # message_stop shape inside anthropic/sse.mjs is legal.
        decision, _ = self.run_hook("pretooluse", self.write_event("server/src/anthropic/sse.mjs", VIOLATION))
        self.assertIsNone(decision)

    def test_inline_suppression_is_honored(self):
        suppressed = VIOLATION.replace(
            "  res.write", "  // ast-grep-ignore: l3-sole-message-stop-emitter\n  res.write"
        )
        decision, _ = self.run_hook("pretooluse", self.write_event(L3_TARGET, suppressed))
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
            "tool_input": {"file_path": "/etc/hosts.test", "content": VIOLATION},
        })
        self.assertIsNone(decision)

    def test_wall_paths_are_grant_gated(self):
        event = self.write_event(".rules/rules/l2-single-mirror-definition.yml", "id: weakened\n")
        raw, _ = self.run_hook("pretooluse", event)
        decision = self.expect_block(raw, "un-granted wall edit must block")
        self.assertIn("SPLICE WALLS", decision["reason"])
        decision, _ = self.run_hook("pretooluse", event, env_extra={"SPLICE_WALLS_OK": "1"})
        self.assertIsNone(decision, "granted wall edit must pass")

    def test_missing_ast_grep_fails_closed(self):
        event = self.write_event(L3_TARGET, CLEAN)
        raw, _ = self.run_hook("pretooluse", event, env_extra={"PATH": "/nonexistent"})
        decision = self.expect_block(raw, "missing scanner must fail closed on PreToolUse")
        self.assertIn("HOOK POLICY INCOMPLETE", decision["reason"])

    # --- Stop ----------------------------------------------------------------------

    def test_stop_blocks_on_dirty_tree_and_respects_active_flag(self):
        with tempfile.TemporaryDirectory(prefix="splice-stop-test-") as tmp:
            root = Path(tmp)
            shutil.copy(REPO / "sgconfig.yml", root / "sgconfig.yml")
            shutil.copytree(REPO / ".rules", root / ".rules")
            decision, _ = self.run_hook("stop", {}, root=root)
            self.assertIsNone(decision, "clean tree must not block stop")
            bad = root / "server/src/codex/stream.mjs"
            bad.parent.mkdir(parents=True)
            bad.write_text(VIOLATION, encoding="utf-8")
            raw, _ = self.run_hook("stop", {}, root=root)
            decision = self.expect_block(raw, "dirty tree must block stop")
            self.assertIn("l3-sole-message-stop-emitter", decision["reason"])
            decision, _ = self.run_hook("stop", {"stop_hook_active": True}, root=root)
            self.assertIsNone(decision, "stop_hook_active must not re-block")

    def test_stop_fails_open_when_scanner_missing(self):
        decision, stderr = self.run_hook("stop", {}, env_extra={"PATH": "/nonexistent"})
        self.assertIsNone(decision, "stop must fail open on infra failure")
        self.assertIn("stop scan unavailable", stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
