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

THE THREE REQUIREMENTS ANCHOR ON CALL SITES, NOT ON BARE IDENTIFIERS (repair, 2026-08-17). Both
halves of this wall were once keyed on the two bare tokens `toolArgsInvalid` and
`parseToJsonElement`, and both halves were satisfiable by code that is not the invariant: a field
DECLARATION and an unrelated parser each matched, so deleting the work left the wall GREEN. The
responses half was repaired first by narrowing its file list to the carrier chain; the note on that
fix recorded what the list alone could NOT close, and this is it — with the tokens still bare,
deleting only the latch assignment stayed green, because the terminal branch's own read of the
field kept the identifier alive.

MEASURED on the chat half at 1f77412, which is what forced this repair: deleting BOTH the latch
assignment (ChatStreamTranslator.kt) and the `toolArgsInvalid != null -> TurnOutcome.Failure` arm
(ChatTerminalState.kt) — the entire CX-01 L3 invariant, so a truncated tool call closes as a clean
Success carrying a malformed tool_use — left this wall GREEN, because both bare tokens were still
satisfied inside ChatToolCalls.kt (the field declaration, and the parse inside invalidArgsReason).

So each of the three requirements is now a LITERAL CALL SITE that exists only because that step is
wired: the parse inside the reason helper, the latch assignment at terminal, and the branch that
turns the latch into a provider-reported Failure. Deleting any one of them takes the wall red for
that step's own reason. This mirrors w4_a's repair round 2, whose lesson was the same one: a token
must be satisfiable only by the file that does the work.

Both halves match LITERAL SOURCE SUBSTRINGS, so a pure-style migration can break a token while the
invariant is intact — the remedy, as in w4_a, is that an entry is a TUPLE of equivalent spellings
of the SAME call site, satisfied by any one of them. That is not a relaxation: every step still has
to be matched by something, each spelling still names a whole call site, and deleting the step
removes every spelling at once.

EXIT 0 = validated. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6): synthetic floors for
the pre-fix shape and the vacuity guard, PLUS the control that matters — mutate the REAL sources,
one requirement at a time, and assert the mutant is red for that requirement's own reason.
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
# LIST, not a single file (HD-24 decomposition, 2026-08-17; repointed to the carrier chain by the
# repair above): the CX-01 chat chain is entry point + latch (ChatStreamTranslator), accumulate +
# parse (ChatToolCalls) and convert-to-Failure (ChatTerminalState). The single-file repoint that
# preceded this named only ChatToolCalls.kt, which left both other steps unread — and moved the
# vacuity guard off the translator, so deleting ChatStreamTranslator.kt no longer made the key None.
CHAT = [
    ROOT / "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatStreamTranslator.kt",
    ROOT / "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatToolCalls.kt",
    ROOT / "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatTerminalState.kt",
]
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
PATHS: dict[str, list[pathlib.Path]] = {"chat": CHAT, "responses": RESP}

# Per dialect, the three steps of the CX-01 chain, in wire order. The value is the call site that
# exists ONLY because that step is wired — or a TUPLE of equivalent spellings of that one call site
# (ANY-OF, see `_alts`). The step list itself stays ALL-OF: no step is optional.
REQUIRED: dict[str, dict[str, str | tuple[str, ...]]] = {
    "chat": {
        "parses the accumulated args":
            "Json.parseToJsonElement(text)",
        "latches toolArgsInvalid at terminal":
            "if (toolCalls.toolArgsInvalid == null) toolCalls.toolArgsInvalid = toolCalls.firstInvalidToolArgs()",
        "turns the latch into a provider-reported Failure":
            "toolCalls.toolArgsInvalid != null -> TurnOutcome.Failure",
    },
    "responses": {
        "parses the accumulated args":
            "Json.parseToJsonElement(text)",
        "latches toolArgsInvalid at terminal":
            "if (state.toolArgsInvalid == null) state.toolArgsInvalid = frames.invalidToolArgsReason(",
        "turns the latch into a provider-reported Failure":
            "?: state.toolArgsInvalid?.let {",
    },
}

MISSING = "translator missing — refusing to pass vacuously"


def _alts(entry: str | tuple[str, ...]) -> tuple[str, ...]:
    """Equivalent spellings of ONE call site. A bare string is its own only spelling."""
    return (entry,) if isinstance(entry, str) else entry


def detect(sources: dict[str, str | None]) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it derived sources directly."""
    problems: list[str] = []
    for name, steps in REQUIRED.items():
        text = sources.get(name)
        if text is None:
            problems.append(f"{name} {MISSING}")
            continue
        for step, entry in steps.items():
            if not any(a in text for a in _alts(entry)):
                problems.append(
                    f"{name} translator never {step} ({' | '.join(_alts(entry))}) — a truncated "
                    "tool call still closes as a Success with corrupt JSON",
                )
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


def _live() -> dict[str, str | None]:
    return {name: _read_all(paths) for name, paths in PATHS.items()}


# The pre-fix shape, kept as a cheap synthetic floor alongside the derived cases below: none of the
# three call sites is present, which is literally true of both translators at the authoring HEAD.
OPEN = "streams args to input_json_delta, closes the block, no parse"


def _selftest_synthetic(fails: list[str]) -> None:
    both_open = {"chat": OPEN, "responses": OPEN}
    if not detect(both_open):
        fails.append("no-validation shape must be RED")
    for one in REQUIRED:
        lopsided = dict(both_open)
        lopsided[one] = "\n".join(a[0] for a in map(_alts, REQUIRED[one].values()))
        if not detect(lopsided):
            fails.append(f"only {one} validated must still be RED")
        vacuous = dict(lopsided)
        vacuous[one] = None
        if not any(MISSING in p for p in detect(vacuous)):
            fails.append(f"a missing {one} file must be RED, never a vacuous pass")


def _selftest_derived(fails: list[str], live: dict[str, str | None]) -> None:
    """THE control that matters: mutate the REAL sources, one dialect's step at a time. A
    hand-written fixture is what let both halves of this wall report OK while the tree they guarded
    had the invariant deleted."""
    if detect(live):
        fails.append(
            "the real sources must be GREEN before a mutant can be derived from them; "
            f"got {detect(live)}",
        )
        return
    for one, steps in REQUIRED.items():
        for step, entry in steps.items():
            mutant = dict(live)
            text = live[one] or ""
            for spelling in _alts(entry):
                text = text.replace(spelling, "")
            mutant[one] = text
            problems = detect(mutant)
            if not any(one in p and step in p for p in problems):
                fails.append(
                    f"deleting {one}'s '{step}' call site must be RED for that step; got {problems}",
                )


def selftest() -> int:
    fails: list[str] = []
    _selftest_synthetic(fails)
    _selftest_derived(fails, _live())
    if fails:
        print("CX-01 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("CX-01 SELFTEST OK — red on the no-validation shape, on a one-sided fix, on a missing "
          "file, and on the REAL sources with any one of the six call sites deleted")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_live())
    if problems:
        print("CX-01 WALL RED — tool-call arguments are not validated before Success:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("CX-01 WALL GREEN: both translators parse accumulated tool args and fail a corrupt tool call.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
