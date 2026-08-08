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
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
DOCTOR = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/DoctorCommand.kt"


def detect(doctor: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if doctor is None:
        return ["DoctorCommand.kt missing — refusing to pass vacuously"]
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


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


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
        fails.append("a missing DoctorCommand.kt must be RED, never a vacuous pass")
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
    problems = detect(_read(DOCTOR))
    if problems:
        print("JW-17 WALL RED — doctor never checks the state/log dirs are writable:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("JW-17 WALL GREEN: doctor probes the state and log dirs for writability, with a fix, and cleans up.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
