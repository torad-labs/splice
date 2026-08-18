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
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
LOCK = ROOT / "gateway/provider-spi/src/main/kotlin/splice/spi/CredentialLock.kt"


def detect(text: str | None, *, raw: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly.

    `text` is the CODE view (comments and imports stripped) and carries the REQUIRED tokens plus the
    shape-change guard; `raw` is the untouched file text and carries the blocking-lock BAN. The two
    directions want opposite treatment — see code_only. The shape guard reads the CODE view on
    purpose: a `tryLock` that survives only in a comment is a changed shape, which is a RED.
    """
    if text is None:
        return ["CredentialLock.kt missing — refusing to pass vacuously"]
    if "tryLock" not in text and "channel.lock()" not in text:
        return ["neither tryLock nor channel.lock() found (shape changed?) — refusing to pass vacuously"]
    problems: list[str] = []
    if "channel.lock()" in (raw or ""):
        problems.append("bare channel.lock() still present — a live slow peer parks a real thread "
                        "for its whole ~96s refresh window, no budget")
    if "CREDENTIAL_LOCK_WAIT_MS" not in text:
        problems.append("no CREDENTIAL_LOCK_WAIT_MS budget — the wait is not bounded by a named knob")
    if "proceeding unlocked" not in text:
        problems.append("no unlocked-degrade path — on budget expiry the refresh must run unlocked "
                        "(G1's other layers own the residual race), never hang or fail")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy a
    REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
    Proven against this wall's own source: with the CREDENTIAL_LOCK_WAIT_MS constant and the
    "proceeding unlocked" degrade log deleted and their literal text left in TODOs, the raw-matching
    wall printed WALL GREEN while the bounded wait was gone. CredentialLock.kt's SH-06 header is an
    8-line comment naming both tokens, so this file was one deletion away from a free pass.

    Applied to _read (the required tokens and the shape guard) and deliberately NOT to _read_raw,
    which feeds the blocking-lock BAN. The two directions want opposite treatment: stripping makes a
    required token harder to satisfy, but would make a banned string easier to hide. Both stay
    strict — a `channel.lock()` moved into a comment is still RED, now via the shape guard too."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read_raw(p: pathlib.Path) -> str | None:
    """Untouched file text — the view the BAN is matched against (see code_only)."""
    return p.read_text(encoding="utf-8") if p.exists() else None


def _read(p: pathlib.Path) -> str | None:
    return code_only(_read_raw(p))


OPEN_LOCK = "val lock = withContext(Dispatchers.IO) { channel.lock() }"
# HD-26: `tryLock` used to sit in a trailing `// tryLock poll` comment here. Once the reader strips
# comments that fixture stopped modelling anything the reader can produce, so the poll call is code.
CLOSED_LOCK = ("val lock = acquireBounded(channel)\n"
               "channel.tryLock()\n"
               "const val CREDENTIAL_LOCK_WAIT_MS = 15_000L\n"
               'log("... proceeding unlocked ...")')
BOUNDED_COMMENTED = ("val lock = acquireBounded(channel)\nchannel.tryLock()\n"
                     "// TODO(SH-06): restore const val CREDENTIAL_LOCK_WAIT_MS = 15_000L and the "
                     'log("... proceeding unlocked ...") degrade')
HIDDEN_BLOCKING_LOCK = (CLOSED_LOCK +
                        "\n// legacy: val lock = withContext(Dispatchers.IO) { channel.lock() }")


def selftest() -> int:
    fails = []
    if not detect(OPEN_LOCK, raw=OPEN_LOCK):
        fails.append("blocking channel.lock() must be RED")
    if detect(CLOSED_LOCK, raw=CLOSED_LOCK):
        fails.append(f"bounded tryLock + budget + degrade must be GREEN, got "
                     f"{detect(CLOSED_LOCK, raw=CLOSED_LOCK)}")
    if not detect(CLOSED_LOCK.replace('log("... proceeding unlocked ...")', ""), raw=CLOSED_LOCK):
        fails.append("a bounded wait WITHOUT the unlocked-degrade path must be RED")
    if not detect(None, raw=None):
        fails.append("missing CredentialLock.kt must be RED, never a vacuous pass")
    if not detect("object CredentialLock {}", raw="object CredentialLock {}"):
        fails.append("an unrecognized shape must be RED, never a vacuous pass")
    # HD-26 comment-satisfiability controls. Both directions, so a later blind sweep that strips the
    # ban too (or stops stripping the required tokens) breaks the selftest instead of the invariant.
    if detect(BOUNDED_COMMENTED, raw=BOUNDED_COMMENTED):
        fails.append("the raw shape must read GREEN — otherwise this fixture is not the bug and "
                     "the control below proves nothing")
    if not detect(code_only(BOUNDED_COMMENTED), raw=BOUNDED_COMMENTED):
        fails.append("a budget and degrade log that survive only as comment text must be RED — "
                     "required tokens are matched against code, never raw file text")
    if not detect(code_only(HIDDEN_BLOCKING_LOCK), raw=HIDDEN_BLOCKING_LOCK):
        fails.append("a blocking channel.lock() commented out of the code view must still be RED — "
                     "the ban reads RAW so a comment cannot hide it")
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
    problems = detect(_read(LOCK), raw=_read_raw(LOCK))
    if problems:
        print("SH-06 WALL RED — CredentialLock can park a thread forever on a live peer:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-06 WALL GREEN: the credential lock waits a bounded budget, then degrades unlocked, honestly logged.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
