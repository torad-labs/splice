#!/usr/bin/env python3
"""WALL for CX-02 — the COMPACT MODE directive must exist in EVERY dialect (all three, not two).

GAP (RED at authoring, 2026-07-26): the compaction directive is emitted only by the Responses
dialect — `grep -rn "COMPACT MODE" gateway --include=*.kt` finds exactly one site,
ResponsesRequestBuilder.kt:376. On kimi (anthropic-passthrough) and every openai-chat head a
compaction turn is therefore an ordinary tool-stripped turn: the backend is never told it is
summarizing, so a chatty non-summary reply is stored SILENTLY as the session's summary.

splice recognises compaction in ONE shared place (gateway/compact/Compact.kt's markers, tools-
agnostic, used by all heads), so any per-dialect divergence in how that recognition is ACTED ON is
drift by construction.

EXIT 0 = every dialect request builder emits the directive.  EXIT 1 = asymmetric.
--selftest = the POSITIVE CONTROL (gate check C6).
"""
from __future__ import annotations

import pathlib
import sys
from collections.abc import Mapping

ROOT = pathlib.Path(__file__).resolve().parents[4]
MARKER = "COMPACT MODE"

# one request-builder surface per dialect — where a compaction turn is shaped for the wire
BUILDERS = {
    "openai-responses": "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesRequestBuilder.kt",
    "openai-chat": "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatRequestBuilder.kt",
    "anthropic-passthrough": "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughRequestBuilder.kt",
}


def detect(sources: Mapping[str, str | None]) -> tuple[list[str], str]:
    """sources: dialect -> file text (None = file absent). Pure; the selftest feeds it directly."""
    absent = [d for d, t in sources.items() if t is None]
    if absent:
        return ([f"dialect builder not found: {', '.join(sorted(absent))} — a builder moved; "
                 "refusing to pass vacuously"], "inconclusive")
    present = sorted(d for d, t in sources.items() if t and MARKER in t)
    missing = sorted(d for d, t in sources.items() if t is not None and MARKER not in t)
    summary = f"'{MARKER}' present in [{', '.join(present) or 'none'}]; missing from [{', '.join(missing) or 'none'}]"
    if missing:
        return ([f"the compaction directive is dialect-asymmetric — missing from {', '.join(missing)}. "
                 "On those dialects a compaction turn is an ordinary turn, and a chatty reply is "
                 "stored silently as the summary."], summary)
    return [], summary


def _load() -> dict[str, str | None]:
    out: dict[str, str | None] = {}
    for dialect, rel in BUILDERS.items():
        p = ROOT / rel
        out[dialect] = p.read_text(encoding="utf-8") if p.exists() else None
    return out


WITH = 'append("COMPACT MODE (critical): You are summarizing a coding session.")'
WITHOUT = 'append("ordinary instructions")'


def selftest() -> int:
    fails = []
    keys = list(BUILDERS)

    asym = {keys[0]: WITH, keys[1]: WITHOUT, keys[2]: WITHOUT}
    if not detect(asym)[0]:
        fails.append("asymmetric fixture (1 of 3 dialects) must be RED")

    partial = {keys[0]: WITH, keys[1]: WITH, keys[2]: WITHOUT}
    if not detect(partial)[0]:
        fails.append("partial fixture (2 of 3 dialects) must be RED — CX-02 requires ALL three")

    full = {k: WITH for k in keys}
    if detect(full)[0]:
        fails.append("all-dialects fixture must be GREEN")

    # "or in none" was my own imprecision when first writing this wall (caught by this control,
    # 2026-07-26). CX-02's done-condition is that ALL THREE builders emit the directive; a tree
    # where none do is CX-02 un-done AND a regression, so it must stay RED.
    none = {k: WITHOUT for k in keys}
    if not detect(none)[0]:
        fails.append("no-dialect fixture must be RED — CX-02 requires ALL three builders to emit it")

    gone = {keys[0]: WITH, keys[1]: WITH, keys[2]: None}
    if not detect(gone)[0]:
        fails.append("a missing builder file must be RED, never a vacuous pass")

    if fails:
        print("CX-02 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("CX-02 SELFTEST OK — red on 1-of-3 and 2-of-3 asymmetry, green only on all-three ("
          "none-of-three is RED too), red on a missing builder")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems, summary = detect(_load())
    print(f"CX-02: {summary}")
    if problems:
        print("CX-02 WALL RED:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("CX-02 WALL GREEN: the COMPACT MODE directive is dialect-symmetric.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
