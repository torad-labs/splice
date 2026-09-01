#!/usr/bin/env python3
"""WALL for JW-02 — `splice doctor` must see dead heads, not bless a degraded daemon.

GAP (RED at authoring, 2026-08-07): /health already carries heads/readyHeads/failedHeads, but
the CLI's health client extracts only `version` — doctor prints a green "daemon running" and
"Everything checks out." on an install where every head failed to bind, and the operator has to
grep daemon.log for DEGRADED=.

GREEN requires ALL of:
  1. ControlPlaneClient parses the head counters (a HealthView, not a bare version string);
  2. daemonChecks turns failedHeads > 0 into a FAIL row with a fix, and a still-converging
     count into a WARN;
  3. doctor probes each configured head's TCP port so bound-but-unassembled is distinguishable
     from unbound.

EXIT 0 = doctor sees. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
# 2026-08-23: HealthView left ControlPlaneClient.kt when the fetch cluster moved to
# DaemonLock.kt (concentration split). The wall follows the declaration, not the old file.
CLIENT = ROOT / "gateway/app/src/main/kotlin/splice/app/DaemonLock.kt"
# HD-25: headChecks + headSummary — BOTH declarations this wall reads — moved out of DoctorCommand.kt
# into their own collaborator when that file was decomposed (it was the tree's worst concentration
# row at 8.10). Re-anchored onto the ONE file that now holds them, at the same single-file
# resolution, following the code rather than the god-file it used to live in — the same repoint
# jw_13 already carries for HeadBoot.kt.
DOCTOR = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/DoctorHeadChecks.kt"


def detect(client: str | None, doctor: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if client is None:
        return ["DaemonLock.kt missing — refusing to pass vacuously"]
    if doctor is None:
        return ["DoctorHeadChecks.kt missing — refusing to pass vacuously"]
    problems: list[str] = []
    if "HealthView" not in client:
        problems.append("ControlPlaneClient still extracts only `version` from /health — the "
                        "head counters the shim already waits on never reach doctor")
    if "failedHeads" not in doctor:
        problems.append("headSummary never reads failedHeads — doctor blesses a daemon whose "
                        "every head failed to start")
    if "not listening" not in doctor:
        problems.append("no per-head TCP probe — a bound-but-unassembled head is "
                        "indistinguishable from an unbound one")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
    a REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.

    Both readers strip because all three checks here are REQUIRED tokens; this wall asserts no
    BANNED string, which is the one direction that must stay on raw text (the jw_08 split) so a
    violation cannot hide inside a comment. DoctorHeadChecks.kt already narrates `failedHeads`
    twice in KDoc, so without this the doctor half of the wall was satisfiable with zero code."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read(p: pathlib.Path) -> str | None:
    return code_only(p.read_text(encoding="utf-8")) if p.exists() else None


CLIENT_OPEN = 'fun healthVersion(port: Int): String? = obj.str("version")'
CLIENT_OK = "data class HealthView(...)\nfun healthView(port: Int): HealthView?"
DOCTOR_OPEN = "daemonChecks branches on version only"
DOCTOR_OK = 'if (failedHeads > 0) FAIL\nadd("head x", ":4101 not listening")'


def derived_mutants() -> list[str]:
    """DR-35b: the gate's polarity law sees vacuity only on TODO items — a DONE item's wall that
    can no longer fail is invisible (neutered-but-present rot). Derive mutants from the LIVE
    sources, cx_02's derived-selftest idiom: deleting each required token from today's tree must
    turn detect red, or that token has rotted into always-green furniture."""
    live = [_read(CLIENT), _read(DOCTOR)]
    if detect(*live):
        return ["derived mutants need the live tree green; the wall is RED right now"]
    fails = []
    for where, tokens in ((0, ["HealthView"]), (1, ["failedHeads"]), (1, ["not listening"])):
        mutated = [x for x in live]
        for t in tokens:
            mutated[where] = (mutated[where] or "").replace(t, "")
        if not detect(*mutated):
            fails.append(f"live tree with {'/'.join(tokens)} deleted must be RED — furniture token")
    return fails


def selftest() -> int:
    fails = []
    if not detect(CLIENT_OPEN, DOCTOR_OPEN):
        fails.append("version-only client + blind doctor must be RED")
    if detect(CLIENT_OK, DOCTOR_OK):
        fails.append(f"HealthView + failedHeads + probe must be GREEN, got {detect(CLIENT_OK, DOCTOR_OK)}")
    if not detect(CLIENT_OK, DOCTOR_OPEN):
        fails.append("head checks that never read failedHeads must be RED")
    if not detect(CLIENT_OPEN, DOCTOR_OK):
        fails.append("a client without HealthView must be RED")
    if not detect(CLIENT_OK, 'reads failedHeads but has no probe'):
        fails.append("missing per-head probe must be RED")
    if not detect(None, DOCTOR_OK):
        fails.append("missing files must be RED, never a vacuous pass")
    fails.extend(derived_mutants())
    if fails:
        print("JW-02 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("JW-02 SELFTEST OK — red on version-only client, failedHeads-blind doctor, missing "
          "probe, and missing files; green only when doctor sees the degraded state")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(CLIENT), _read(DOCTOR))
    if problems:
        print("JW-02 WALL RED — doctor blesses a daemon with dead heads:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("JW-02 WALL GREEN: doctor reads the head counters, fails on failedHeads, and probes each head port.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
