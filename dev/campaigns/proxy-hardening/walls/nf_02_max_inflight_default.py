#!/usr/bin/env python3
"""WALL for NF-02 — the shipped maxInflight default must not exceed the measured-good ceiling.

GAP (RED at authoring, 2026-07-26): Knob.kt:139 ships
    MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), 100L)
while splice's OWN committed measurement at config/splice.example.toml:202 reads
    "0.3% turn failure at inflight<=14, 11% at 38, 67% at 100".
The shipped default IS the value measured as catastrophic. Observed live 2026-07-26 on
claude-kimi-perf.jsonl: 92% of turns ran at inflight>=2, peaking at 32, 32% errors in the 14:00 hour.

The ceiling is READ FROM the example config's own measurement line, never hardcoded here — so
re-measuring is the only sanctioned way to move this wall, and editing the wall cannot buy
headroom (qgre zero_ratchet NO-SAVED-TRUTH).

EXIT 0 = gap closed (default <= ceiling).  EXIT 1 = gap open.
--selftest = the POSITIVE CONTROL: proves this wall can distinguish an open gap from a closed one,
             so a do-nothing `exit(1)` cannot masquerade as enforcement (gate check C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
KNOB = ROOT / "gateway/core/src/main/kotlin/splice/core/config/Knob.kt"
EXAMPLE = ROOT / "config/splice.example.toml"

KNOB_RE = re.compile(r'MAX_INFLIGHT\(\s*"maxInflight".*?,\s*(\d+)L\s*\)', re.S)
MEASURE_RE = re.compile(r"([\d.]+)%\s+turn failure at inflight\s*<=\s*(\d+)")
FALLBACK_CEILING = 14


def detect(knob_text: str | None, example_text: str | None) -> tuple[list[str], str]:
    """Pure detection. Returns (problems, human summary). No I/O — the selftest feeds it directly."""
    if knob_text is None:
        return ["Knob.kt missing — refusing to pass vacuously"], "inconclusive"
    m = KNOB_RE.search(knob_text)
    if not m:
        return (["MAX_INFLIGHT knob declaration not found (shape changed?) — refusing to pass vacuously"],
                "inconclusive")
    default = int(m.group(1))

    ceiling, basis = FALLBACK_CEILING, "fallback (measurement line not found)"
    if example_text:
        mm = MEASURE_RE.search(example_text)
        if mm:
            ceiling = int(mm.group(2))
            basis = f"config/splice.example.toml — {mm.group(1)}% turn failure at inflight<={ceiling}"

    summary = f"maxInflight default = {default}; measured-good ceiling = {ceiling} ({basis})"
    if default > ceiling:
        return ([f"shipped default {default} exceeds the measured-good ceiling {ceiling}. "
                 "Reconcile Knob.kt with splice's own measurement."], summary)
    return [], summary


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
    this wall after the real declaration is deleted. Same stripper cx_02/cx_09/cx_18 already carry.

    Applied to the Knob.kt reader ONLY, and the asymmetry is the point. The knob default is a
    REQUIRED token — commenting the declaration out must not keep handing this wall a number to
    approve, and the shape guard must fire instead. The example-config reader stays RAW because the
    measurement it parses IS a comment BY DESIGN (`# ...0.3% turn failure at inflight<=14...`);
    stripping there would not harden anything, it would blind the wall to its own ceiling and drop
    it onto FALLBACK_CEILING — a weaker check whenever the committed measurement is stricter."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


def _read_code(p: pathlib.Path) -> str | None:
    return code_only(_read(p))


OPEN_KNOB = 'MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), 100L),'
CLOSED_KNOB = 'MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), 12L),'
EXAMPLE_FIXTURE = "# Measured on this box: 0.3% turn failure at inflight<=14, 11% at 38, 67% at 100.\n"


def selftest() -> int:
    fails = []
    open_p, _ = detect(OPEN_KNOB, EXAMPLE_FIXTURE)
    if not open_p:
        fails.append("open-gap fixture (default 100, ceiling 14) must be RED")
    closed_p, _ = detect(CLOSED_KNOB, EXAMPLE_FIXTURE)
    if closed_p:
        fails.append(f"closed-gap fixture (default 12, ceiling 14) must be GREEN, got {closed_p}")
    # the ceiling must come from the measurement, not a literal: same knob, stricter measurement -> red
    strict_p, _ = detect(CLOSED_KNOB, "0.3% turn failure at inflight<=8, 67% at 100\n")
    if not strict_p:
        fails.append("ceiling must derive from the measurement line (default 12 vs ceiling 8 should be RED)")
    missing_p, _ = detect(None, EXAMPLE_FIXTURE)
    if not missing_p:
        fails.append("a missing Knob.kt must be RED, never a vacuous pass")
    shape_p, _ = detect("val SOMETHING_ELSE = 1", EXAMPLE_FIXTURE)
    if not shape_p:
        fails.append("an unrecognised knob shape must be RED, never a vacuous pass")
    if fails:
        print("NF-02 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("NF-02 SELFTEST OK — red on open gap, green on closed gap, ceiling tracks the measurement, "
          "inconclusive inputs stay red")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems, summary = detect(_read_code(KNOB), _read(EXAMPLE))
    print(f"NF-02: {summary}")
    if problems:
        print("NF-02 WALL RED:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("NF-02 WALL GREEN: shipped default is within the measured-good ceiling.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
