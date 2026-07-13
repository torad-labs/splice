#!/usr/bin/env python3
"""mythos hook orchestrator — every write-time policy routes to ast-grep rules.

ONE orchestrator, ZERO per-rule Python (operator design constraint, 2026-07-13):
policy lives only in .rules/rules/*.yml. This file owns routing, not rules.

  PreToolUse (Write|Edit|MultiEdit)
      1. Compute the file content AS IT WOULD EXIST after the tool call
         (Write payload; Edit/MultiEdit applied to the on-disk file).
      2. Mirror it to <tmp>/<repo-relative-path> and run
         `ast-grep scan --config <repo>/sgconfig.yml <relpath>` with cwd=<tmp>,
         so each rule's files:/ignores: globs bind exactly as they do at the
         gate (globs resolve against the path as scanned; verified 2026-07-13
         on ast-grep 0.44.0).
      3. severity:error matches block the call; anything lower goes to stderr.

  Stop / SubagentStop
      `ast-grep scan` over the working tree; error findings block the stop.

The SAME rules run at the gate (`npm run gate:rules`) and in CI — a weakened
hook still fails the build (same-checker-twice).

Failure policy: PreToolUse fails CLOSED (a broken wall must not silently wave
writes through; the block names this file and the error log). Stop fails OPEN
with a loud stderr warning (session end must never wedge on scan
infrastructure; the write-time wall and the gate are the backstops).

Walls are grant-gated: edits to .rules/, .claude/hooks/, .claude/settings.json,
or sgconfig.yml are blocked unless the operator sets MYTHOS_WALLS_OK=1. The
override is loud by design — never set it to push past your own block.

Deliberate rule exceptions go inline: `// ast-grep-ignore: <rule-id>` plus a
justification; ast-grep honors these in both the hook and the gate.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import traceback
from datetime import datetime, timezone
from pathlib import Path

# MYTHOS_HOOK_ROOT exists for the hermetic test harness only. Redirecting it to
# dodge policy is equivalent to setting MYTHOS_WALLS_OK — visible, auditable,
# and caught by the gate re-running the same rules on the real tree.
ROOT = Path(os.environ.get("MYTHOS_HOOK_ROOT", "") or Path(__file__).resolve().parents[2])
SGCONFIG = ROOT / "sgconfig.yml"
ERROR_LOG = Path(__file__).resolve().parent / "log" / "orchestrator_errors.log"

WRITE_TOOLS = ("Write", "Edit", "MultiEdit")
WALL_PATHS = (".rules", ".claude/hooks", ".claude/settings.json", "sgconfig.yml")
SCAN_TIMEOUT = 20


def main() -> int:
    lifecycle = sys.argv[1] if len(sys.argv) > 1 else ""
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0
    if not isinstance(data, dict):
        return 0
    try:
        if lifecycle == "pretooluse":
            return pretooluse(data)
        if lifecycle == "stop":
            return stop(data)
    except Exception:
        _log(traceback.format_exc())
        if lifecycle == "pretooluse":
            _emit_block(
                "HOOK POLICY INCOMPLETE: mythos orchestrator failed on a blocking lifecycle.\n\n"
                f"log: {ERROR_LOG}\n\n"
                "Failing closed — silently skipping write-time policy is unsafe. Fix the\n"
                "orchestrator or the scan toolchain (is ast-grep on PATH?); never route around it."
            )
        else:
            sys.stderr.write(f"mythos orchestrator: stop lifecycle degraded, see {ERROR_LOG}\n")
    return 0


def pretooluse(data: dict) -> int:
    tool = data.get("tool_name")
    if not isinstance(tool, str) or tool not in WRITE_TOOLS:
        return 0
    tool_input = data.get("tool_input") or {}
    file_path = tool_input.get("file_path") or ""
    if not file_path:
        return 0

    path = Path(file_path)
    if not path.is_absolute():
        path = Path(data.get("cwd") or ROOT) / path
    try:
        rel = path.resolve().relative_to(ROOT.resolve())
    except ValueError:
        return 0  # outside the repo — not this wall's jurisdiction

    if _is_wall_path(rel) and os.environ.get("MYTHOS_WALLS_OK") != "1":
        _emit_block(
            f"MYTHOS WALLS: {rel} is wall infrastructure (rules / orchestrator / settings).\n\n"
            "Walls are grant-gated: a blocked write means fix the code, not the wall.\n"
            "If the operator consciously approved a wall change, re-run with\n"
            "MYTHOS_WALLS_OK=1 — loud and never silent. Then re-run the gate\n"
            "(npm run gate:rules && npm run test:hooks) to prove red/green."
        )
        return 0

    content = _proposed_content(tool, tool_input, path)
    if content is None:
        return 0

    matches = _scan_mirrored(rel, content)  # infra failure raises → main() fails closed
    errors = [m for m in matches if m.get("severity") == "error"]
    advisories = [m for m in matches if m.get("severity") != "error"]
    if advisories:
        sys.stderr.write(_format_findings("mythos walls (advisory)", advisories) + "\n")
    if errors:
        _emit_block(
            _format_findings(f"MYTHOS WALLS: write to {rel} blocked", errors)
            + "\n\nFix the content. For a deliberate, justified exception add\n"
            "`// ast-grep-ignore: <rule-id>` with a reason on the line above.\n"
            "Rules: .rules/rules/ (tests in .rules/rule-tests/). The gate re-runs\n"
            "the same rules: npm run gate:rules."
        )
    return 0


def stop(data: dict) -> int:
    if data.get("stop_hook_active"):
        return 0
    try:
        matches = _scan_tree()
    except Exception:
        _log(traceback.format_exc())
        sys.stderr.write(
            f"mythos orchestrator: stop scan unavailable, relying on gate (see {ERROR_LOG})\n"
        )
        return 0
    errors = [m for m in matches if m.get("severity") == "error"]
    if errors:
        _emit_block(
            _format_findings("MYTHOS WALLS: the tree has rule violations — not done yet", errors)
            + "\n\nFix them before stopping (same rules as npm run gate:rules)."
        )
    return 0


def _is_wall_path(rel: Path) -> bool:
    rel_str = str(rel)
    return any(rel_str == wall or rel_str.startswith(wall.rstrip("/") + "/") for wall in WALL_PATHS)


def _proposed_content(tool: str, tool_input: dict, path: Path) -> str | None:
    """The file content as it would exist after the tool call."""
    if tool == "Write":
        return str(tool_input.get("content", ""))
    edits = tool_input.get("edits") if tool == "MultiEdit" else [tool_input]
    if not isinstance(edits, list):
        return None
    try:
        content = path.read_text(encoding="utf-8")
    except OSError:
        # No readable base file (Claude Code will reject the Edit anyway) —
        # scan the raw replacement text so shape violations still surface.
        return "\n".join(str(e.get("new_string", "")) for e in edits if isinstance(e, dict))
    for edit in edits:
        if not isinstance(edit, dict):
            continue
        old = str(edit.get("old_string", ""))
        new = str(edit.get("new_string", ""))
        if not old:
            continue
        content = content.replace(old, new) if edit.get("replace_all") else content.replace(old, new, 1)
    return content


def _scan_mirrored(rel: Path, content: str) -> list[dict]:
    """Scan proposed content at its repo-relative path so files:/ignores: bind."""
    if not SGCONFIG.exists():
        raise RuntimeError(f"missing {SGCONFIG}")
    with tempfile.TemporaryDirectory(prefix="mythos-walls-") as tmp:
        target = Path(tmp) / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        return _run_scan([str(rel)], cwd=tmp)


def _scan_tree() -> list[dict]:
    if not SGCONFIG.exists():
        raise RuntimeError(f"missing {SGCONFIG}")
    return _run_scan([], cwd=str(ROOT))


def _run_scan(targets: list[str], cwd: str) -> list[dict]:
    proc = subprocess.run(
        ["ast-grep", "scan", "--config", str(SGCONFIG), "--json=compact", *targets],
        cwd=cwd,
        capture_output=True,
        text=True,
        timeout=SCAN_TIMEOUT,
    )
    # ast-grep exits 0 = clean, 1 = findings reported; anything else is infra failure.
    if proc.returncode not in (0, 1):
        raise RuntimeError(f"ast-grep exit {proc.returncode}: {proc.stderr.strip()[:400]}")
    out = proc.stdout.strip()
    if not out:
        return []
    matches = json.loads(out)
    return matches if isinstance(matches, list) else []


def _format_findings(header: str, matches: list[dict], cap: int = 15) -> str:
    lines = [f"{header}: {len(matches)} finding(s)", ""]
    for match in matches[:cap]:
        line = match.get("range", {}).get("start", {}).get("line", 0) + 1
        lines.append(f"  {match.get('ruleId', '?')}  {match.get('file', '?')}:{line}")
        message = (match.get("message") or "").strip()
        if message:
            lines.append(f"    {message}")
        note = (match.get("note") or "").strip()
        if note:
            lines.extend(f"    | {note_line}" for note_line in note.splitlines() if note_line.strip())
    if len(matches) > cap:
        lines.append(f"  … and {len(matches) - cap} more")
    return "\n".join(lines)


def _emit_block(reason: str) -> None:
    sys.stdout.write(json.dumps({"decision": "block", "reason": reason}))


def _log(text: str) -> None:
    try:
        ERROR_LOG.parent.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now(timezone.utc).isoformat()
        with ERROR_LOG.open("a", encoding="utf-8") as f:
            f.write(f"[{stamp}]\n{text}\n{'-' * 80}\n")
    except OSError:
        pass


if __name__ == "__main__":
    sys.exit(main())
