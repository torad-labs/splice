#!/usr/bin/env python3
"""WALL for JW-05 — doctor must read what actually HAPPENED, not just what is configured.

GAP (RED at authoring, 2026-08-07): every doctor section is static (binaries, symlinks, TOML,
credential presence, /health version). A fully-configured install whose head has been 429-cooled
for an hour or whose last twenty turns died upstream still prints "Everything checks out." —
while both runtime instruments (HeadHealthCounters on /api/heads, the per-head perf JSONL
outcome field) already exist and persist.

GREEN requires ALL of:
  1. doctor has a runtime section (INFO-skipped when the daemon is stopped);
  2. it reads the per-head health counters from /api/heads (providerErrors split);
  3. it reads the perf JSONL outcome tail (recency framing, not lifetime totals alone);
  4. warnings carry the actionable fix (splice logs --head ...).

EXIT 0 = runtime visible. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
DOCTOR = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/DoctorCommand.kt"
# The runtime section lives in its own file (DoctorCommand.kt sits at detekt's file function
# budget); the wall reads the whole doctor surface so a legitimate split cannot read as a gap.
RUNTIME = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/DoctorRuntime.kt"


def detect(doctor: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if doctor is None:
        return ["DoctorCommand.kt missing — refusing to pass vacuously"]
    if "daemonChecks" not in doctor:
        return ["doctor section table not found (shape changed?) — refusing to pass vacuously"]
    problems: list[str] = []
    if '"runtime"' not in doctor:
        problems.append("no runtime section — doctor blesses an install whose turns are dying")
    if "providerErrors" not in doctor:
        problems.append("the per-head health counters are never read — the provider-vs-local "
                        "split exists precisely for this diagnosis")
    if "perf" not in doctor and "outcome" not in doctor:
        problems.append("the perf JSONL outcome tail is never read — 'last failure: Nm ago' is "
                        "the answer doctor exists to give")
    if "splice logs --head" not in doctor:
        problems.append("runtime warnings carry no actionable fix")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
    a REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.

    Both readers strip because every check here is a REQUIRED token; this wall asserts no BANNED
    string, which is the one direction that must stay raw (the jw_08 split) so a violation cannot
    hide inside a comment. This surface is unusually comment-dense — DoctorRuntime.kt's own header
    narrates the `perf` JSONL `outcome` tail in prose — so before this, the perf/outcome check was
    already satisfied by the file's description of a section that could have been deleted."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read(p: pathlib.Path) -> str | None:
    return code_only(p.read_text(encoding="utf-8")) if p.exists() else None


OPEN_FIX = "daemonChecks static only"
CLOSED_FIX = ('daemonChecks\n"runtime" to guarded { runtimeChecks }\nproviderErrors\n'
              'perf outcome tail\nfix = "splice logs --head x --tail 50"')


def derived_mutants() -> list[str]:
    """DR-35b: the gate's polarity law sees vacuity only on TODO items — a DONE item's wall that
    can no longer fail is invisible (neutered-but-present rot). Derive mutants from the LIVE
    sources, cx_02's derived-selftest idiom: deleting each required token from today's tree must
    turn detect red, or that token has rotted into always-green furniture."""
    live = (_read(DOCTOR) or "") + "\n" + (_read(RUNTIME) or "")
    if detect(live):
        return ["derived mutants need the live tree green; the wall is RED right now"]
    fails = []
    for tokens in (['"runtime"'], ["providerErrors"], ["perf", "outcome"], ["splice logs --head"]):
        mutant = live
        for t in tokens:
            mutant = mutant.replace(t, "")
        if not detect(mutant):
            fails.append(f"live tree with {'/'.join(tokens)} deleted must be RED — furniture token")
    return fails


def selftest() -> int:
    fails = []
    if not detect(OPEN_FIX):
        fails.append("static-only doctor must be RED")
    if detect(CLOSED_FIX):
        fails.append(f"runtime-reading doctor must be GREEN, got {detect(CLOSED_FIX)}")
    if not detect(CLOSED_FIX.replace("providerErrors\n", "")):
        fails.append("a runtime section that skips the counters must be RED")
    if not detect(CLOSED_FIX.replace('fix = "splice logs --head x --tail 50"', "")):
        fails.append("warnings without the fix must be RED")
    if not detect(None):
        fails.append("a missing DoctorCommand.kt must be RED, never a vacuous pass")
    fails.extend(derived_mutants())
    if fails:
        print("JW-05 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("JW-05 SELFTEST OK — red on static-only, counter-blind, fix-less shapes and missing "
          "file, and on the LIVE tree with each required token deleted; green only when doctor "
          "reads the runtime instruments")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect((_read(DOCTOR) or "") + "\n" + (_read(RUNTIME) or "") if _read(DOCTOR) else None)
    if problems:
        print("JW-05 WALL RED — doctor is a static-config checker only:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("JW-05 WALL GREEN: doctor reads the health counters and the perf outcome tail, with fixes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
