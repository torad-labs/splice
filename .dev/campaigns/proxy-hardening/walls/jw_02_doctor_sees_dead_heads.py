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
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
CLIENT = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/ControlPlaneClient.kt"
DOCTOR = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/DoctorCommand.kt"


def detect(client: str | None, doctor: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if client is None:
        return ["ControlPlaneClient.kt missing — refusing to pass vacuously"]
    if doctor is None:
        return ["DoctorCommand.kt missing — refusing to pass vacuously"]
    problems: list[str] = []
    if "HealthView" not in client:
        problems.append("ControlPlaneClient still extracts only `version` from /health — the "
                        "head counters the shim already waits on never reach doctor")
    if "failedHeads" not in doctor:
        problems.append("daemonChecks never reads failedHeads — doctor blesses a daemon whose "
                        "every head failed to start")
    if "not listening" not in doctor:
        problems.append("no per-head TCP probe — a bound-but-unassembled head is "
                        "indistinguishable from an unbound one")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


CLIENT_OPEN = 'fun healthVersion(port: Int): String? = obj.str("version")'
CLIENT_OK = "data class HealthView(...)\nfun healthView(port: Int): HealthView?"
DOCTOR_OPEN = "daemonChecks branches on version only"
DOCTOR_OK = 'if (failedHeads > 0) FAIL\nadd("head x", ":4101 not listening")'


def selftest() -> int:
    fails = []
    if not detect(CLIENT_OPEN, DOCTOR_OPEN):
        fails.append("version-only client + blind doctor must be RED")
    if detect(CLIENT_OK, DOCTOR_OK):
        fails.append(f"HealthView + failedHeads + probe must be GREEN, got {detect(CLIENT_OK, DOCTOR_OK)}")
    if not detect(CLIENT_OK, DOCTOR_OPEN):
        fails.append("a doctor that never reads failedHeads must be RED")
    if not detect(CLIENT_OPEN, DOCTOR_OK):
        fails.append("a client without HealthView must be RED")
    if not detect(CLIENT_OK, 'reads failedHeads but has no probe'):
        fails.append("missing per-head probe must be RED")
    if not detect(None, DOCTOR_OK):
        fails.append("missing files must be RED, never a vacuous pass")
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
