#!/usr/bin/env python3
"""WALL for NF-03 — the whole-turn wall clock must be enforced for the WHOLE turn.

GAP (RED at authoring, 2026-08-07): TurnWatchdog.totalCap is only sampled by the poller that
launchIn() starts INSIDE the successful-response block (TurnDriver) and cancels in that block's
finally — so during connect, headers-wait, retry backoff, refresh, and between fold/re-anchor
rounds NOTHING enforces the cap. An N-round turn gets N x upstreamTimeoutMs of budget against a
single totalCap, holding its InflightGate slot the whole time.

GREEN requires BOTH:
  1. TurnWatchdog exposes a turn-scoped total-cap poller (fun launchTotalCap) that samples
     elapsed >= totalCap independent of any open stream, setting the typed sentinel BEFORE
     cancelling — identical breach semantics to launchIn;
  2. TurnDriver launches it (launchTotalCap( call site) alongside the whole-turn client pinger,
     NOT inside the response block that launchIn already owns.
The idle tiers stay with launchIn (they need the slot) — this wall also refuses to pass if
launchIn disappears, so the cap poller cannot silently REPLACE idle enforcement.

EXIT 0 = the cap is armed for the whole turn.  EXIT 1 = the gap is open.
--selftest = the POSITIVE CONTROL (gate check C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
WATCHDOG = ROOT / "gateway/provider-spi/src/main/kotlin/splice/spi/Watchdog.kt"
# 2026-08-23: the launchTotalCap call site lives in TurnOneDrive.kt after the
# drive split. Watchdog.kt still owns the declaration.
DRIVER = ROOT / "gateway/gateway/src/main/kotlin/splice/gateway/head/TurnOneDrive.kt"


def detect(watchdog_text: str | None, driver_text: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if watchdog_text is None:
        return ["Watchdog.kt missing — refusing to pass vacuously"]
    if driver_text is None:
        return ["TurnOneDrive.kt missing — refusing to pass vacuously"]
    problems: list[str] = []
    if "fun launchIn(" not in watchdog_text:
        return ["launchIn poller not found in Watchdog.kt (shape changed?) — the idle tiers lost "
                "their enforcer; refusing to pass vacuously"]
    if "fun launchTotalCap(" not in watchdog_text:
        problems.append("no launchTotalCap on TurnWatchdog — totalCap is only sampled while an "
                        "upstream stream is open (launchIn), never during connect/backoff/refresh/"
                        "between-rounds")
    elif "launchTotalCap(" not in driver_text.replace("fun launchTotalCap(", ""):
        problems.append("launchTotalCap exists but the turn drive never launches it — the whole-turn "
                        "cap is still stream-scoped")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
    this wall after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 already carry.

    Both readers are stripped: every leg here is a REQUIRED token (launchIn, launchTotalCap, the
    TurnDriver launch site) and this wall carries no banned string, which is the only direction
    that would have to stay raw. It matters most for the driver leg — Watchdog's KDoc already
    names [launchIn] and launchTotalCap in prose, so a commented-out launch site would read as a
    live one."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read(p: pathlib.Path) -> str | None:
    return code_only(p.read_text(encoding="utf-8")) if p.exists() else None


WD_OPEN = "public fun launchIn(scope: CoroutineScope, slot: InflightGate.Slot, target: Job): Job ="
WD_CLOSED = WD_OPEN + "\n    public fun launchTotalCap(scope: CoroutineScope, target: Job): Job ="
DRV_OPEN = "val pinger = if (pingClient) self.launchClientPinger(drive, turnJob) else null"
DRV_CLOSED = DRV_OPEN + "\n val capPoller = drive.watchdog.launchTotalCap(self, turnJob)"


def selftest() -> int:
    fails = []
    if not detect(WD_OPEN, DRV_OPEN):
        fails.append("open gap (no launchTotalCap anywhere) must be RED")
    if detect(WD_CLOSED, DRV_CLOSED):
        fails.append(f"closed gap must be GREEN, got {detect(WD_CLOSED, DRV_CLOSED)}")
    if not detect(WD_CLOSED, DRV_OPEN):
        fails.append("launchTotalCap declared but never launched by TurnDriver must be RED")
    if not detect(None, DRV_CLOSED) or not detect(WD_CLOSED, None):
        fails.append("missing source files must be RED, never a vacuous pass")
    if not detect("class TurnWatchdog {}", DRV_CLOSED):
        fails.append("a Watchdog.kt without launchIn (shape change) must be RED, refusing vacuous pass")
    if fails:
        print("NF-03 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("NF-03 SELFTEST OK — red on missing poller, missing launch site, missing files, and "
          "launchIn shape change; green only when the cap is armed turn-wide AND idle keeps launchIn")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(WATCHDOG), _read(DRIVER))
    if problems:
        print("NF-03 WALL RED — the whole-turn wall clock is unenforced outside an open stream:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("NF-03 WALL GREEN: totalCap is armed for the whole turn; idle tiers keep their stream-scoped poller.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
