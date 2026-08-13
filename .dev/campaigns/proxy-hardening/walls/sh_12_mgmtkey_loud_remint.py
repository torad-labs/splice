#!/usr/bin/env python3
"""WALL for SH-12 — MgmtKey may mint quietly only on first run; an unreadable/blank EXISTING
file must mint LOUDLY and record when.

GAP (RED at authoring, 2026-08-07): ensure() collapses "no key file yet" and "key file exists
but unreadable" into one silent fallthrough (runCatching...discard) and mints fresh bytes —
silently revoking the dashboard session, every script's bearer, and the launch shim's
stale-daemon stop hook. The operator's symptom is an unexplained 401 everywhere.

GREEN requires ALL of:
  1. the silent discard fallthrough is gone;
  2. the unreadable/blank-but-present case logs a loud line naming the consequence ("every
     existing bearer" invalid) — the absent-file first-run mint stays quiet;
  3. mintedAtMs is recorded so doctor/status can flag a suspiciously fresh key (JW wiring).

EXIT 0 = loud remint. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
KEY = ROOT / "gateway/core/src/main/kotlin/splice/core/config/MgmtKey.kt"


def detect(text: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if text is None:
        return ["MgmtKey.kt missing — refusing to pass vacuously"]
    if "fun ensure(" not in text and "private fun ensure" not in text:
        return ["MgmtKey.ensure not found (shape changed?) — refusing to pass vacuously"]
    problems: list[str] = []
    if 'discard("unreadable/empty key file' in text:
        problems.append("the silent discard fallthrough is still there — an unreadable EXISTING "
                        "key file mints quietly, and every bearer dies with no explanation")
    if "every existing bearer" not in text:
        problems.append("no loud remint line — the operator learns about the rotation from "
                        "unexplained 401s instead of one log line naming the consequence")
    if "mintedAtMs" not in text:
        problems.append("mint time is not recorded — doctor cannot flag a suspiciously fresh key")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


OPEN_FIX = 'private fun ensure(\ndiscard("unreadable/empty key file falls through to regeneration below")'
CLOSED_FIX = ('private fun ensure(\nlog("[mgmt-key] ... every existing bearer ... is now invalid")\n'
              "mintedAtMs = clock()")


def selftest() -> int:
    fails = []
    if not detect(OPEN_FIX):
        fails.append("silent discard fallthrough must be RED")
    if detect(CLOSED_FIX):
        fails.append(f"loud remint + mintedAtMs must be GREEN, got {detect(CLOSED_FIX)}")
    if not detect(CLOSED_FIX.replace("mintedAtMs = clock()", "")):
        fails.append("no mint timestamp must be RED")
    if not detect('private fun ensure(\nmintedAtMs = clock()'):
        fails.append("a quiet remint (no loud line) must be RED")
    if not detect(None):
        fails.append("missing MgmtKey.kt must be RED, never a vacuous pass")
    if not detect("class MgmtKey"):
        fails.append("an unrecognized shape must be RED, never a vacuous pass")
    if fails:
        print("SH-12 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("SH-12 SELFTEST OK — red on silent fallthrough, quiet remint, missing timestamp, "
          "missing file, and shape change; green only on the loud, recorded remint")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(KEY))
    if problems:
        print("SH-12 WALL RED — MgmtKey silently mints a new bearer on ANY read failure:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-12 WALL GREEN: first-run mints stay quiet; a present-but-unreadable key mints loudly and is timestamped.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
