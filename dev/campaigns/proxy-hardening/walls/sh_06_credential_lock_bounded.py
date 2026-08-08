#!/usr/bin/env python3
"""WALL for SH-06 — CredentialLock must never park a thread unboundedly on a live peer.

GAP (RED at authoring, 2026-08-07): withFileLock calls channel.lock() with no timeout. The
dead-peer justification in the header is true but incomplete: a LIVE slow peer holds the lock for
up to ~96s (refresh HTTP inside the lock x 3 attempts + backoff), and every credentials() call
that reaches the blocking tier parks on it.

GREEN requires ALL of:
  1. no bare blocking channel.lock() in CredentialLock.kt;
  2. a tryLock()-based bounded wait exists (CREDENTIAL_LOCK_WAIT_MS budget);
  3. the expiry path DEGRADES to running unlocked with the honest log line ("proceeding
     unlocked") — G1's other layers own the residual race — rather than failing the refresh.

EXIT 0 = bounded. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (gate check C6).
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
LOCK = ROOT / "gateway/provider-spi/src/main/kotlin/splice/spi/CredentialLock.kt"


def detect(text: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if text is None:
        return ["CredentialLock.kt missing — refusing to pass vacuously"]
    if "tryLock" not in text and "channel.lock()" not in text:
        return ["neither tryLock nor channel.lock() found (shape changed?) — refusing to pass vacuously"]
    problems: list[str] = []
    if "channel.lock()" in text:
        problems.append("bare channel.lock() still present — a live slow peer parks a real thread "
                        "for its whole ~96s refresh window, no budget")
    if "CREDENTIAL_LOCK_WAIT_MS" not in text:
        problems.append("no CREDENTIAL_LOCK_WAIT_MS budget — the wait is not bounded by a named knob")
    if "proceeding unlocked" not in text:
        problems.append("no unlocked-degrade path — on budget expiry the refresh must run unlocked "
                        "(G1's other layers own the residual race), never hang or fail")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


OPEN_LOCK = "val lock = withContext(Dispatchers.IO) { channel.lock() }"
CLOSED_LOCK = ("val lock = acquireBounded(channel)  // tryLock poll\n"
               "const val CREDENTIAL_LOCK_WAIT_MS = 15_000L\n"
               'log("... proceeding unlocked ...")')


def selftest() -> int:
    fails = []
    if not detect(OPEN_LOCK):
        fails.append("blocking channel.lock() must be RED")
    if detect(CLOSED_LOCK):
        fails.append(f"bounded tryLock + budget + degrade must be GREEN, got {detect(CLOSED_LOCK)}")
    if not detect(CLOSED_LOCK.replace('log("... proceeding unlocked ...")', "")):
        fails.append("a bounded wait WITHOUT the unlocked-degrade path must be RED")
    if not detect(None):
        fails.append("missing CredentialLock.kt must be RED, never a vacuous pass")
    if not detect("object CredentialLock {}"):
        fails.append("an unrecognized shape must be RED, never a vacuous pass")
    if fails:
        print("SH-06 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("SH-06 SELFTEST OK — red on blocking lock, missing budget, missing degrade, missing "
          "file, and shape change; green only on bounded tryLock with an honest unlocked degrade")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(LOCK))
    if problems:
        print("SH-06 WALL RED — CredentialLock can park a thread forever on a live peer:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-06 WALL GREEN: the credential lock waits a bounded budget, then degrades unlocked, honestly logged.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
