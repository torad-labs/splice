#!/usr/bin/env python3
"""WALL for CX-01 — tool-call arguments must be validated as JSON before a turn is Success.

GAP (RED at authoring, 2026-08-08): both OpenAI-family translators stream argument text straight
to input_json_delta and close the block with no parse. A backend that truncates arguments
mid-string but still emits a terminal produces a Success carrying a corrupt tool_use — Claude
Code then hard-errors on parse or dispatches the tool with garbage. An opened tool with zero arg
deltas ships input:{} the same way.

GREEN requires BOTH translators to:
  1. accumulate the argument text (not just stream it) and parse it at block close;
  2. latch a translator-level failure (toolArgsInvalid) that terminalOutcome turns into a
     Failure instead of a Success.

EXIT 0 = validated. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
CHAT = ROOT / "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatStreamTranslator.kt"
# LIST, not a single file (HD-24 decomposition, 2026-08-17): a target may move the validation latch
# and its parser to siblings. Every path must exist or the whole key reads as missing (vacuity
# guard unchanged — see the file-list mechanism note in cx_09/w4_a).
#
# THE LIST NAMES THE IMPLEMENTATION, NOT THE PACKAGE (repair, 2026-08-17). The first cut of this
# list carried ResponsesTurnState.kt and ResponsesToolSearchParse.kt, and BOTH tokens were then
# satisfied by code that is not CX-01: `toolArgsInvalid` matched only the bare field DECLARATION,
# and `parseToJsonElement` matched only the tool_search_call query parser. Measured: deleting the
# latch assignment AND invalidToolArgsReason outright left this wall GREEN. Same lesson w4_a
# recorded in its repair round 2 — a token must be satisfiable only by the file that does the work.
# The four files below are exactly the CX-01 carrier chain: entry point, accumulate+latch, parse,
# convert-to-Failure.
RESP = [
    ROOT / "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesStreamTranslator.kt",
    ROOT / "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesItemFold.kt",
    ROOT / "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesFrameParse.kt",
    ROOT / "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesTerminalDecision.kt",
]


def detect(chat: str | None, resp: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    for name, text in (("ChatStreamTranslator", chat), ("ResponsesStreamTranslator", resp)):
        if text is None:
            return [f"{name}.kt missing — refusing to pass vacuously"]
    problems: list[str] = []
    for name, text in (("chat", chat), ("responses", resp)):
        if "toolArgsInvalid" not in (text or ""):
            problems.append(f"{name} translator never latches toolArgsInvalid — a truncated tool "
                            "call still closes as a Success with corrupt JSON")
        elif "parseToJsonElement" not in (text or ""):
            problems.append(f"{name} translator latches but never parses the accumulated args")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


def _read_all(paths: list[pathlib.Path]) -> str | None:
    """Concatenate a key's file list. ANY missing file makes the whole key None — a deleted file
    must never go quiet by dropping out of the concatenation silently."""
    texts = [_read(p) for p in paths]
    if any(t is None for t in texts):
        return None
    return "\n".join(t for t in texts if t is not None)


OK = "toolArgsInvalid\nJson.parseToJsonElement(args)"
OPEN = "streams args, no validation"


def selftest() -> int:
    fails = []
    if not detect(OPEN, OPEN):
        fails.append("no-validation shape must be RED")
    if detect(OK, OK):
        fails.append(f"both-validated shape must be GREEN, got {detect(OK, OK)}")
    if not detect(OK, OPEN):
        fails.append("responses without validation must be RED")
    if not detect(OPEN, OK):
        fails.append("chat without validation must be RED")
    if not detect("toolArgsInvalid but no parse", OK):
        fails.append("a latch with no parse must be RED")
    if not detect(None, OK):
        fails.append("a missing file must be RED, never a vacuous pass")
    if fails:
        print("CX-01 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("CX-01 SELFTEST OK — red on no-validation, one-sided, latch-without-parse, and missing "
          "files; green only when both translators parse and latch")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(CHAT), _read_all(RESP))
    if problems:
        print("CX-01 WALL RED — tool-call arguments are not validated before Success:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("CX-01 WALL GREEN: both translators parse accumulated tool args and fail a corrupt tool call.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
