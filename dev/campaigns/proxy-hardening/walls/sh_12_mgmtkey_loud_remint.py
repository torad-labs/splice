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
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
KEY = ROOT / "gateway/core/src/main/kotlin/splice/core/config/MgmtKey.kt"


def detect(code: str | None, raw: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly.

    TWO VIEWS OF ONE FILE, on purpose (see [code_only]). `code` is MgmtKey.kt with comments and
    imports stripped and carries every REQUIRED token, so a token left behind in a `// TODO` cannot
    stand in for a deleted call site. `raw` is the file as written and carries the BAN, so the
    silent fallthrough cannot be hidden from the wall by commenting it out. Stripping the ban too
    would weaken it; not stripping the requirements leaves the wall comment-satisfiable. Both stay
    strict this way — the split jw_08 makes across two readers, made here across two views because
    both directions are asserted against a single file."""
    if code is None or raw is None:
        return ["MgmtKey.kt missing — refusing to pass vacuously"]
    if "fun ensure(" not in code and "private fun ensure" not in code:
        return ["MgmtKey.ensure not found (shape changed?) — refusing to pass vacuously"]
    problems: list[str] = []
    if 'discard("unreadable/empty key file' in raw:
        problems.append("the silent discard fallthrough is still there — an unreadable EXISTING "
                        "key file mints quietly, and every bearer dies with no explanation")
    if "every existing bearer" not in code:
        problems.append("no loud remint line — the operator learns about the rotation from "
                        "unexplained 401s instead of one log line naming the consequence")
    if "mintedAtMs" not in code:
        problems.append("mint time is not recorded — doctor cannot flag a suspiciously fresh key")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring. Without this the wall is satisfiable by a COMMENT: delete the
    loud `log("... every existing bearer ...")` block and `mintedAtMs = clock()`, leave them behind
    as `// SH-12: restore ...`, and both required tokens still match while the remint is silent
    again. Proven against this file's own source before the stripper landed. Same stripper
    cx_01/cx_02/cx_09/cx_18/jw_08 carry.

    Applied ONLY to the required-token view — never to the ban, which reads raw text. Stripping
    makes a required token harder to satisfy but would make a banned string easier to hide, so the
    two directions get opposite treatment and each stays strict."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


OPEN_FIX = 'private fun ensure(\ndiscard("unreadable/empty key file falls through to regeneration below")'
CLOSED_FIX = ('private fun ensure(\nlog("[mgmt-key] ... every existing bearer ... is now invalid")\n'
              "mintedAtMs = clock()")
# The comment-satisfiable shape: the remint is deleted, its text survives as a TODO. Every required
# token is present in the file and none of it runs.
COMMENTED_FIX = ('private fun ensure(\n'
                 '// SH-12: restore log("[mgmt-key] ... every existing bearer ... is now invalid")\n'
                 "// SH-12: restore mintedAtMs = clock()")
# The mirror case: the banned fallthrough commented out rather than removed. The ban reads RAW, so
# this must stay RED — stripping it would let a violation hide behind a `//`.
HIDDEN_BAN = CLOSED_FIX + '\n// discard("unreadable/empty key file falls through to regeneration below")'


def _both(text: str | None) -> tuple[str | None, str | None]:
    """One synthetic source, viewed the two ways [detect] takes it — exactly as main() does."""
    return code_only(text), text


def selftest() -> int:
    fails = []
    if not detect(*_both(OPEN_FIX)):
        fails.append("silent discard fallthrough must be RED")
    if detect(*_both(CLOSED_FIX)):
        fails.append(f"loud remint + mintedAtMs must be GREEN, got {detect(*_both(CLOSED_FIX))}")
    if not detect(*_both(CLOSED_FIX.replace("mintedAtMs = clock()", ""))):
        fails.append("no mint timestamp must be RED")
    if not detect(*_both('private fun ensure(\nmintedAtMs = clock()')):
        fails.append("a quiet remint (no loud line) must be RED")
    if not detect(*_both(COMMENTED_FIX)):
        fails.append("a remint that survives only as a comment must be RED — a mention is not a wiring")
    if not detect(*_both(HIDDEN_BAN)):
        fails.append("the banned fallthrough commented out must still be RED — a ban must never be "
                     "hideable behind a `//`")
    if not detect(*_both(None)):
        fails.append("missing MgmtKey.kt must be RED, never a vacuous pass")
    if not detect(*_both("class MgmtKey")):
        fails.append("an unrecognized shape must be RED, never a vacuous pass")
    if fails:
        print("SH-12 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("SH-12 SELFTEST OK — red on silent fallthrough, quiet remint, missing timestamp, "
          "commented-out remint, commented-out fallthrough, missing file, and shape change; "
          "green only on the loud, recorded remint")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    raw = _read(KEY)
    problems = detect(code_only(raw), raw)
    if problems:
        print("SH-12 WALL RED — MgmtKey silently mints a new bearer on ANY read failure:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-12 WALL GREEN: first-run mints stay quiet; a present-but-unreadable key mints loudly and is timestamped.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
