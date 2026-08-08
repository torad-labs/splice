#!/usr/bin/env python3
"""WALL for SH-04 — the auth probe loop must be supervised: a tick failure or loop death may
never silently end per-head auth probing for the daemon's lifetime.

GAP (RED at authoring, 2026-08-07): the per-tick guard (runCatchingCancellable) catches only
IOException/SerializationException/IllegalArgumentException; any other RuntimeException escapes
the while-loop, the coroutine dies, nothing logs it, nothing restarts it, and start() refuses to
re-arm (job != null). The daemon then runs unprobed on that head until restart.

GREEN requires ALL of (supervision-only — the proposal's widened broad catch is FORBIDDEN by
the repo's own walls (kt-no-quality-suppress + ForbiddenSuppress on TooGenericExceptionCaught;
zero broad catches exist in main sources), and the supervisor covers the same throwable class
with bounded churn):
  1. the per-tick guard remains (runCatchingCancellable) for the known transient classes;
  2. the loop job carries an invokeOnCompletion supervisor that RESTARTS on a non-cancel death;
  3. the restart budget is bounded (systemd StartLimitBurst shape) and exhaustion logs a
     "permanently down" line for SH-08 to surface.

EXIT 0 = supervised. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
LOOP = ROOT / "gateway/app/src/main/kotlin/splice/app/AuthProbeLoop.kt"


def detect(text: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if text is None:
        return ["AuthProbeLoop.kt missing — refusing to pass vacuously"]
    if "class AuthProbeLoop" not in text:
        return ["AuthProbeLoop class not found (shape changed?) — refusing to pass vacuously"]
    problems: list[str] = []
    if "runCatchingCancellable { probeOnce() }" not in text and "probeOnce()" in text:
        problems.append("the per-tick guard disappeared — known transient classes should still be "
                        "logged ticks without a restart cycle")
    if "invokeOnCompletion" not in text:
        problems.append("no invokeOnCompletion supervisor — a dead loop stays dead for the "
                        "daemon's lifetime and start() refuses to re-arm")
    if "MAX_RESTARTS" not in text:
        problems.append("no bounded restart budget — a supervisor without StartLimitBurst is a "
                        "hot restart loop waiting to happen")
    if "permanently down" not in text:
        problems.append("budget exhaustion is not announced — SH-08 has no line to surface")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


OPEN_FIX = "class AuthProbeLoop\nrunCatchingCancellable { probeOnce() }"
CLOSED_FIX = ("class AuthProbeLoop\nrunCatchingCancellable { probeOnce() }\n"
              "invokeOnCompletion\nMAX_RESTARTS\nlog(\"permanently down\")")


def selftest() -> int:
    fails = []
    if not detect(OPEN_FIX):
        fails.append("unsupervised loop must be RED")
    if not detect(CLOSED_FIX.replace("runCatchingCancellable { probeOnce() }\n", "probeOnce()\n")):
        fails.append("a supervisor that dropped the per-tick guard must be RED")
    if detect(CLOSED_FIX):
        fails.append(f"broad tick catch + supervisor + budget must be GREEN, got {detect(CLOSED_FIX)}")
    if not detect(CLOSED_FIX.replace("invokeOnCompletion\n", "")):
        fails.append("no supervisor must be RED")
    if not detect(CLOSED_FIX.replace("MAX_RESTARTS\n", "")):
        fails.append("no restart budget must be RED")
    if not detect(None):
        fails.append("missing AuthProbeLoop.kt must be RED, never a vacuous pass")
    if fails:
        print("SH-04 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("SH-04 SELFTEST OK — red on narrow catch, missing supervisor, missing budget, missing "
          "announce, and missing file; green only on the fully supervised loop")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(LOOP))
    if problems:
        print("SH-04 WALL RED — the auth probe loop is unsupervised:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-04 WALL GREEN: tick failures log, loop deaths restart under a bounded budget, exhaustion announces.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
