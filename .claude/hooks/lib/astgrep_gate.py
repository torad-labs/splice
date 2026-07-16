"""Shared helpers for the write-time architecture gates (campaign kotlin-hardening D2).

Ported from proof-main .claude/hooks/orchestrator/_lib.py (the north-star repo) and
re-anchored to torad-fleet: root detection uses CLAUDE_PROJECT_DIR (set by both
.claude/settings.json and .codex/hooks.json) with a marker-walk fallback, because
fleet's sgconfig.yml does not exist until campaign item C1 lands — the gates that
depend on it self-arm at that point.
"""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import subprocess
import tempfile

# Suffixes the architecture gate scans. Kotlin backend campaign first; TS is
# included so C2's ported frontend-boundary tenets fire on write when they land.
GATE_LANG_EXTS = {".kt", ".ts", ".tsx", ".mts", ".cts"}


def target_file_path(tool_input: dict) -> str | None:
    return tool_input.get("file_path") or tool_input.get("path")


def find_project_root(*starts: str | None) -> pathlib.Path | None:
    """Resolve the fleet repo root: CLAUDE_PROJECT_DIR first (validated), then a
    walk up from each start to the dir holding settings.gradle.kts + .claude/."""
    candidates = [os.environ.get("CLAUDE_PROJECT_DIR"), *starts]
    for start in candidates:
        if not start:
            continue
        p = pathlib.Path(start)
        try:
            p = p.resolve()
        except OSError:
            continue
        if p.is_file():
            p = p.parent
        for cur in [p, *p.parents]:
            if (cur / "sgconfig.yml").exists() and (cur / ".claude").is_dir():  # splice adaptation 2026-07-16: root marker is sgconfig.yml (settings.gradle.kts lives under gateway/)
                return cur
    return None


def proposed_file_content(tool_name: str, tool_input: dict) -> str:
    """The full proposed FILE content after the write — what a real `ast-grep scan`
    sees, so the write-time gate matches the Gradle/CI gate exactly. For Edit/
    MultiEdit the replacement is applied to the CURRENT file (a method edit must be
    scanned WITH its enclosing class). Falls back to the raw new text when the file
    can't be read or an edit anchor is absent (new file / unmatched old_string)."""
    if tool_name == "Write":
        return tool_input.get("content", "") or ""

    path = target_file_path(tool_input)
    current = ""
    if path:
        try:
            current = pathlib.Path(path).read_text(encoding="utf-8")
        except OSError:
            current = ""

    if tool_name == "Edit":
        old = tool_input.get("old_string", "") or ""
        new = tool_input.get("new_string", "") or ""
        if current and old and old in current:
            return current.replace(old, new) if tool_input.get("replace_all") else current.replace(old, new, 1)
        return new

    if tool_name == "MultiEdit":
        text, applied = current, False
        for e in tool_input.get("edits", []):
            old = e.get("old_string", "") or ""
            new = e.get("new_string", "") or ""
            if old and old in text:
                text = text.replace(old, new) if e.get("replace_all") else text.replace(old, new, 1)
                applied = True
        return text if applied else "\n".join(e.get("new_string", "") or "" for e in tool_input.get("edits", []))

    return ""


def astgrep_scan_proposed(root: pathlib.Path, file_path: str, content: str) -> list[dict]:
    """Scan PROPOSED content against the repo rules (sgconfig.yml at root), with the
    file's real relative path so each rule's files:/ignores globs resolve exactly as
    in a normal `ast-grep scan`.

    Strategy (MIRRORED SCRATCH ROOT — supersedes the proof sibling-probe pattern,
    D2 amendment 2026-07-02): write the content at its TRUE relative path under a
    temp root with sgconfig.yml + .rules/ copied in, and scan there. The sibling
    .fleet-gate-<pid>/ probe dir broke rules whose ignores: name EXACT file paths
    (e.g. the persistence-boundary allowlist): the inserted dir segment defeated
    the ignore and every edit to an allowlisted file was falsely blocked. At the
    true relpath, files:/ignores globs resolve byte-identically to a real scan.
    Plain file I/O (not the Edit tool) so it cannot re-trigger the hook chain.
    Raises on subprocess/JSON errors — the caller declares FAIL_CLOSED, so a
    broken scanner blocks instead of waving violations through."""
    abs_fp = pathlib.Path(file_path)
    if not abs_fp.is_absolute():
        abs_fp = root / file_path
    try:
        abs_fp = abs_fp.resolve()
    except OSError:
        return []
    if abs_fp.suffix not in GATE_LANG_EXTS:
        return []
    try:
        rel = abs_fp.relative_to(root)
    except ValueError:
        return []  # outside the repo — not ours to gate

    matches: list[dict] = []
    tmp = tempfile.mkdtemp(prefix="fleet-gate-")
    try:
        tmp_root = pathlib.Path(tmp)
        sgconfig = root / "sgconfig.yml"
        if not sgconfig.exists():
            return []
        shutil.copy(sgconfig, tmp_root / "sgconfig.yml")
        rules_dir = root / ".rules"
        if rules_dir.is_dir():
            shutil.copytree(rules_dir, tmp_root / ".rules")
        probe = tmp_root / rel
        probe.parent.mkdir(parents=True, exist_ok=True)
        probe.write_text(content, encoding="utf-8")
        proc = subprocess.run(
            ["ast-grep", "scan", "--json=compact", str(probe)],
            cwd=str(tmp_root),
            capture_output=True,
            text=True,
            timeout=30,
        )
        out = (proc.stdout or "").strip()
        if out:
            for m in json.loads(out):
                if m.get("severity", "error") in ("error", "warning"):
                    rng = m.get("range", {}).get("start", {})
                    matches.append(
                        {
                            "ruleId": m.get("ruleId", "?"),
                            "severity": m.get("severity", "error"),
                            "line": rng.get("line", 0) + 1,
                        }
                    )
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return matches
