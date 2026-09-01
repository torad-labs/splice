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


def _mask_strings(text: str) -> str:
    """Blank Kotlin string/char literals without moving offsets (sh_10's idiom), so a brace inside
    a log template cannot corrupt the scope stacks below."""
    chars = list(text)
    i = 0
    while i < len(chars):
        if text.startswith('"""', i):
            close = text.find('"""', i + 3)
            end = len(chars) if close < 0 else close + 3
        elif chars[i] in ('"', "'"):
            quote = chars[i]
            end = i + 1
            while end < len(chars):
                if chars[end] == "\\":
                    end += 2
                elif chars[end] == quote:
                    end += 1
                    break
                else:
                    end += 1
        else:
            i += 1
            continue
        for at in range(i, min(end, len(chars))):
            if chars[at] not in "\r\n":
                chars[at] = " "
        i = end
    return "".join(chars)


def _scopes_at(text: str, positions: list[int]) -> dict[int, tuple[int, ...]]:
    """Brace ancestry of each position, one lexical pass over the masked text."""
    targets = sorted(set(positions))
    structure = _mask_strings(text)
    result: dict[int, tuple[int, ...]] = {}
    stack: list[int] = []
    target = 0
    for at, ch in enumerate(structure):
        while target < len(targets) and targets[target] == at:
            result[targets[target]] = tuple(stack)
            target += 1
        if ch == "{":
            stack.append(at)
        elif ch == "}" and stack:
            stack.pop()
    while target < len(targets):
        result[targets[target]] = tuple(stack)
        target += 1
    return result


def detect(watchdog_text: str | None, driver_text: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if watchdog_text is None:
        return ["Watchdog.kt missing — refusing to pass vacuously"]
    if driver_text is None:
        return ["TurnOneDrive.kt missing — refusing to pass vacuously"]
    problems: list[str] = []
    # DR-35e (codex catch #3, 2026-08-31): every token scan below runs on MASKED text — a
    # compilable raw-string decoy carrying the anchored launch line satisfied the unmasked finds/
    # counts/regexes while the live poller was an inert Job(). code_only strips comments but not
    # strings; _scopes_at masked internally, which only hid the gap. Mask once, scan everywhere.
    watchdog_text = _mask_strings(watchdog_text)
    if "fun launchIn(" not in watchdog_text:
        return ["launchIn poller not found in Watchdog.kt (shape changed?) — the idle tiers lost "
                "their enforcer; refusing to pass vacuously"]
    cap_sites = _mask_strings(driver_text).replace("fun launchTotalCap(", "")
    if "fun launchTotalCap(" not in watchdog_text:
        problems.append("no launchTotalCap on TurnWatchdog — totalCap is only sampled while an "
                        "upstream stream is open (launchIn), never during connect/backoff/refresh/"
                        "between-rounds")
    elif "launchTotalCap(" not in cap_sites:
        problems.append("launchTotalCap exists but the turn drive never launches it — the whole-turn "
                        "cap is still stream-scoped")
    else:
        # DR-35a: presence was not placement — the launch could move AFTER roundRun.run (the rounds
        # execution this wall exists to cover) and stay green, re-creating the stream-scoped bug
        # the docstring forbids. The drive is sequential: the cap must be armed BEFORE the rounds.
        run_at = cap_sites.find("roundRun.run(")
        if run_at == -1:
            problems.append("roundRun.run( not found in TurnOneDrive.kt (shape changed?) — cannot "
                            "verify the cap arms before the rounds; refusing to pass vacuously")
        elif cap_sites.find("launchTotalCap(") > run_at:
            problems.append("launchTotalCap launches AFTER roundRun.run — the cap poller is "
                            "rounds-scoped again (the placement half-fix): connect/headers-wait/"
                            "backoff before the first round are uncovered")
        # DR-35c (codex catch, 2026-08-30): order alone accepted CONDITIONAL arming — `val capPoller
        # = if (pingClient) drive.watchdog.launchTotalCap(...) else Job()` keeps the call lexically
        # before the rounds while arming the cap on only one path. The call site must be a direct,
        # unconditionally-executed val assignment (the live TurnOneDrive shape), and there must be
        # exactly ONE site, so a compliant decoy cannot vouch for a conditional real one. A reshaped
        # future call site reds fail-closed rather than passing unexamined.
        # DR-35f (codex catch #4, 2026-08-31): the anchor pinned the CALLEE but not the ARGUMENTS —
        # `launchTotalCap(self, if (pingClient) turnJob else Job())` compiled, matched the prefix,
        # and armed the poller against a THROWAWAY Job on non-ping paths: breach cancelled nothing.
        # The whole argument list is pinned to the live `(self, turnJob)` shape; any reshape reds.
        elif (len(re.findall(r"launchTotalCap\(", cap_sites)) != 1
              or not re.search(r"^[ \t]*val\s+\w+\s*=\s*drive\.watchdog\.launchTotalCap\(self,\s*turnJob\)\s*$",
                               cap_sites, re.M)):
            problems.append("the launchTotalCap call site is not exactly one unconditional "
                            "`val x = drive.watchdog.launchTotalCap(self, turnJob)` statement — a "
                            "conditional/indirect launch, or any TARGET other than the bare turnJob, "
                            "arms the whole-turn cap on only some paths or against a throwaway job")
        else:
            # DR-35d (codex catch #2, 2026-08-31): the line anchor cannot see ENCLOSING control
            # flow — a multi-line `if (pingClient) { val armed = launchTotalCap(...); armed }`
            # puts a perfectly-anchored val at line start inside the branch. Dominance proof:
            # the launch site's brace ancestry must be a PREFIX of the run site's (ancestor or
            # same block) — then launch-before-run in a straight-line body means the cap is armed
            # on every path that reaches the rounds. A launch nested in any block the run is not
            # in (an if arm, a when branch) has a brace the run lacks, and reds.
            launch_at = cap_sites.find("launchTotalCap(")
            scopes = _scopes_at(cap_sites, [launch_at, run_at])
            launch_scope, run_scope = scopes[launch_at], scopes[run_at]
            if not (len(launch_scope) <= len(run_scope)
                    and run_scope[:len(launch_scope)] == launch_scope):
                problems.append("launchTotalCap sits inside a block that roundRun.run is not in "
                                "(a conditional branch) — the whole-turn cap is armed on only "
                                "some paths to the rounds")
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
DRV_CLOSED = DRV_OPEN + "\n val capPoller = drive.watchdog.launchTotalCap(self, turnJob)" + \
    "\n roundRun.run(drive, self, turnJob)"
# DR-35a placement mutants: the launch exists but AFTER the rounds (the half-fix), and a drive
# whose rounds call vanished (must refuse to pass on shape drift, not pass vacuously).
DRV_LATE = DRV_OPEN + "\n roundRun.run(drive, self, turnJob)" + \
    "\n val capPoller = drive.watchdog.launchTotalCap(self, turnJob)"
DRV_NO_RUN = DRV_OPEN + "\n val capPoller = drive.watchdog.launchTotalCap(self, turnJob)"
# DR-35c mutants: codex's exact reproduced false green (conditional arming holds lexical order),
# and a compliant decoy beside a conditional real site (exactly-one must refuse the pair).
DRV_CONDITIONAL = DRV_OPEN + \
    "\n val capPoller = if (pingClient) drive.watchdog.launchTotalCap(self, turnJob) else Job()" + \
    "\n roundRun.run(drive, self, turnJob)"
DRV_DECOY = DRV_OPEN + "\n val decoy = drive.watchdog.launchTotalCap(self, turnJob)" + \
    "\n val capPoller = if (pingClient) drive.watchdog.launchTotalCap(self, turnJob) else Job()" + \
    "\n roundRun.run(drive, self, turnJob)"
# DR-35d: codex's second reproduced false green — the multi-line nested conditional keeps a
# line-anchored val INSIDE the branch, beating the anchor leg; only scope dominance sees it.
DRV_NESTED = DRV_OPEN + "\n val capPoller = if (pingClient) {" + \
    "\n     val armed = drive.watchdog.launchTotalCap(self, turnJob)" + \
    "\n     armed" + \
    "\n } else Job()" + \
    "\n roundRun.run(drive, self, turnJob)"
# Positive control for the dominance leg: run nested DEEPER (a try block) with the launch at the
# ancestor scope is the LIVE shape and must stay green — prefix, not equality.
DRV_TRY_RUN = DRV_OPEN + "\n val capPoller = drive.watchdog.launchTotalCap(self, turnJob)" + \
    "\n try {" + \
    "\n     roundRun.run(drive, self, turnJob)" + \
    "\n } finally { capPoller.cancel() }"
# DR-35f: codex's fourth reproduced false green — an unconditional anchored val whose TARGET is
# conditional: the poller runs on every path but cancels a throwaway Job() on non-ping paths.
DRV_CONDITIONAL_TARGET = DRV_OPEN + \
    "\n val capPoller = drive.watchdog.launchTotalCap(self, if (pingClient) turnJob else Job())" + \
    "\n roundRun.run(drive, self, turnJob)"
# DR-35e: codex's third reproduced false green — a compilable raw string carries the anchored
# launch line while the live poller is an inert Job(). Every scan must run masked.
DRV_STRING_DECOY = DRV_OPEN + '\n val fake = """' + \
    "\n val capPoller = drive.watchdog.launchTotalCap(self, turnJob)" + \
    '\n """' + \
    "\n val capPoller = Job()" + \
    "\n roundRun.run(drive, self, turnJob)"


def selftest() -> int:
    fails = []
    if not detect(WD_OPEN, DRV_OPEN):
        fails.append("open gap (no launchTotalCap anywhere) must be RED")
    if detect(WD_CLOSED, DRV_CLOSED):
        fails.append(f"closed gap must be GREEN, got {detect(WD_CLOSED, DRV_CLOSED)}")
    if not detect(WD_CLOSED, DRV_OPEN):
        fails.append("launchTotalCap declared but never launched by TurnDriver must be RED")
    if not detect(WD_CLOSED, DRV_LATE):
        fails.append("launchTotalCap AFTER roundRun.run (placement half-fix) must be RED")
    if not detect(WD_CLOSED, DRV_NO_RUN):
        fails.append("a drive without roundRun.run (shape drift) must be RED, refusing vacuous pass")
    if not detect(WD_CLOSED, DRV_CONDITIONAL):
        fails.append("CONDITIONAL cap arming (if (pingClient) launchTotalCap(...) else Job()) must "
                     "be RED — lexical order alone is not unconditional arming (DR-35c)")
    if not detect(WD_CLOSED, DRV_DECOY):
        fails.append("a compliant decoy beside a conditional real site must be RED — exactly one "
                     "unconditional call site (DR-35c)")
    if not detect(WD_CLOSED, DRV_NESTED):
        fails.append("a multi-line nested conditional (line-anchored val inside the if branch) "
                     "must be RED — scope dominance, not line shape (DR-35d)")
    if not detect(WD_CLOSED, DRV_STRING_DECOY):
        fails.append("a raw-string decoy carrying the anchored launch line beside an inert Job() "
                     "poller must be RED — scans run on masked text (DR-35e)")
    if not detect(WD_CLOSED, DRV_CONDITIONAL_TARGET):
        fails.append("an unconditional launch whose TARGET is conditional (self, if (pingClient) "
                     "turnJob else Job()) must be RED — the argument list is pinned (DR-35f)")
    if detect(WD_CLOSED, DRV_TRY_RUN):
        fails.append(f"the live shape (launch at ancestor scope, run inside try) must be GREEN — "
                     f"dominance is prefix, not equality; got {detect(WD_CLOSED, DRV_TRY_RUN)}")
    if not detect(None, DRV_CLOSED) or not detect(WD_CLOSED, None):
        fails.append("missing source files must be RED, never a vacuous pass")
    if not detect("class TurnWatchdog {}", DRV_CLOSED):
        fails.append("a Watchdog.kt without launchIn (shape change) must be RED, refusing vacuous pass")
    if fails:
        print("NF-03 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("NF-03 SELFTEST OK — red on missing poller, missing launch site, launch-after-rounds "
          "placement, conditional/decoyed/branch-nested/conditional-target arming, missing "
          "roundRun shape, missing files, and launchIn shape change; green only when exactly one "
          "unconditional `launchTotalCap(self, turnJob)` scope-dominates and precedes the rounds "
          "AND idle keeps launchIn")
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
