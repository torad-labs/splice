#!/usr/bin/env python3
"""WALL for JW-17 — doctor must PROVE the state and log dirs are writable, not just print paths.

GAP (RED at authoring, 2026-08-08): three subsystems degrade silently when ~/.claude-codex is
unwritable or full — daemon.log creation (swallowed), config PATCH persistence (best-effort), and
usage/perf/compact appends (best-effort by design). The operator sees "dashboard empty, logs
empty, config changes don't survive restart" with no error anywhere. doctor prints the state-dir
path but never touches it.

GREEN requires ALL of:
  1. doctor writes-and-deletes a dot-prefixed probe file in the state AND log dirs;
  2. the probe is removed in a finally (doctor stays non-mutating in spirit);
  3. a failure is a FAIL row carrying an actionable fix (chmod / df pointer).

EXIT 0 = probed. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
# HD-25: stateInfo and BOTH writableProbe call sites are inside daemonChecks, which moved out of
# DoctorCommand.kt into the daemon-section collaborator when that file was decomposed (it was the
# tree's worst concentration row at 8.10). Re-anchored onto the ONE file that now holds the call
# sites, at the same single-file resolution; the PROBE half below is unmoved.
DOCTOR = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/DoctorDaemonChecks.kt"
# The probe helper lives in its own file (DoctorCommand.kt was at the file function budget); the
# wall reads both so a legitimate split cannot read as a gap.
PROBE = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/DoctorProbeWrite.kt"


def detect(doctor: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if doctor is None:
        return ["DoctorDaemonChecks.kt missing — refusing to pass vacuously"]
    if "stateInfo" not in doctor:
        return ["doctor state section not found (shape changed?) — refusing to pass vacuously"]
    problems: list[str] = []
    if "writableProbe" not in doctor and "probeWritable" not in doctor:
        problems.append("doctor never probes the state/log dirs for writability — an unwritable "
                        "~/.claude-codex degrades three subsystems silently")
    if ".delete" not in doctor and "deleteIfExists" not in doctor:
        problems.append("the probe file is not removed — doctor must stay non-mutating in spirit")
    if "chmod" not in doctor and "df -h" not in doctor:
        problems.append("a writability FAIL carries no actionable fix (chmod / df pointer)")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
    this wall after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 already carry.

    Every assertion here is a REQUIRED token (a probe, a delete, an actionable fix) — this wall
    carries no banned string — so BOTH readers are stripped. Stripping only ever makes a required
    token harder to satisfy; the direction that must stay raw is a ban, and there is none."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read(p: pathlib.Path) -> str | None:
    return code_only(p.read_text(encoding="utf-8")) if p.exists() else None


OPEN_FIX = "stateInfo = listOf(state dir path only)"
CLOSED_FIX = ('stateInfo\nwritableProbe(dir)\ntry { write } finally { Files.deleteIfExists(probe) }\n'
              'fix = "chmod u+rwx <dir>"')


def selftest() -> int:
    fails = []
    if not detect(OPEN_FIX):
        fails.append("path-only doctor must be RED")
    if detect(CLOSED_FIX):
        fails.append(f"probing doctor must be GREEN, got {detect(CLOSED_FIX)}")
    if not detect(CLOSED_FIX.replace("try { write } finally { Files.deleteIfExists(probe) }\n", "")):
        fails.append("a probe that never deletes must be RED")
    if not detect(CLOSED_FIX.replace('fix = "chmod u+rwx <dir>"', "")):
        fails.append("a fixless writability failure must be RED")
    if not detect(None):
        fails.append("a missing DoctorDaemonChecks.kt must be RED, never a vacuous pass")
    if fails:
        print("JW-17 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("JW-17 SELFTEST OK — red on path-only, non-deleting, fixless shapes and missing file; "
          "green only when doctor probes writability and cleans up")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect((_read(DOCTOR) or "") + "\n" + (_read(PROBE) or "") if _read(DOCTOR) else None)
    if problems:
        print("JW-17 WALL RED — doctor never checks the state/log dirs are writable:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("JW-17 WALL GREEN: doctor probes the state and log dirs for writability, with a fix, and cleans up.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
