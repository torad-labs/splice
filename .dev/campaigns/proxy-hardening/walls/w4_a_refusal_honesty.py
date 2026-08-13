#!/usr/bin/env python3
"""WALL for W4-A (collapses CX-07 + CX-08) — a backend-sent refusal/pause/hard-truncation signal
must never reach the client as a clean success.

GAP (RED at authoring, 2026-08-09): every dialect dispatches on a backend-supplied discriminator
and drops the remainder.
  · anthropic-passthrough `onMessageDelta` branched on TWO stop_reason values (`tool_use`,
    `max_tokens`) and sent the other FIVE to `else -> Unit`. Three of those five are not clean
    completions: `refusal`, `pause_turn`, `model_context_window_exceeded`.
  · openai-chat never read OpenAI's dedicated `refusal` field on either carrier
    (`delta.refusal`, `message.refusal`) — `grep -rn refusal` over the module returned nothing.
  · openai-responses (WIDENED into scope — the item named only the two dialects above) likewise
    read no `refusal`: its contentFiltered gate fires only on `status: incomplete`, but an OpenAI
    refusal arrives with `status: completed`, so the turn ended `finished` with zero text. Its
    three carriers are `response.refusal.delta`, `response.refusal.done` (ResponseRefusalDoneEvent,
    which carries the COMPLETE refusal string) and a `refusal`-typed content part.

GREEN requires, per dialect, BOTH halves — reading the carrier AND converting it into a
provider-reported Failure. A read with no conversion is the exact half-fix this wall exists to
reject, because it looks like coverage forever.

TWO CORRECTIONS made in repair round 2, both because the first cut of this wall could be satisfied
by a tree with the L3 violation fully restored:

  1. THE CONVERSION HALF ANCHORS ON CALL SITES, not on generic tokens. `providerReported = true`,
     `TurnOutcome.Failure`, `failureType` and `refusalFailure` (the declaration) all already exist
     in these files for PRE-EXISTING reasons, so deleting the verdict wiring — the chat terminal
     branch, passthrough's `else ->` arm, the responses elvis link — left every one of them behind
     and the wall stayed GREEN with all three dialects re-broken. The conversion tokens are now the
     one-per-file strings that exist ONLY because the verdict is wired: measured 0 occurrences at
     HEAD 5840979, 1 after the fix. Detekt compensates for two of the three (an orphaned
     `refusalFailure` / `stopReasonFailure` is UnusedPrivateMember) but not for chat, whose helper
     stays called; that arm contributed zero detection power no other tool supplied.
  2. THE READ HALF ANCHORS ON CODE, NOT ON BARE FIELD NAMES — for the same reason. Every carrier
     name also appears in this fix's own KDoc, so `response.refusal.done` as a bare token stayed
     satisfied by a comment after its dispatch arm was deleted (measured: WALL GREEN with `.done`
     unhandled). The read tokens are now the dispatch arms, the harvest call and the guarded read
     itself — one per carrier, each of which stops existing if that carrier stops being handled.
  3. THE READ HALF PINS THE TYPE GUARD (`strIfString`), not merely the field name. Reading
     `refusal` with `strOrEmpty` returns the `.content` of ANY JsonPrimitive, so a vendor shipping
     the field as a flag (`"refusal": false`) yields the non-blank string "false" and fails 100% of
     that vendor's WORKING turns as a provider-reported refusal — a dishonest verdict in the other
     direction, and a G20 inversion blaming a backend that denied refusing.

Deliberately NOT enforced: an OPEN remainder on `stop_reason`. The safe four stay safe and an
unrecognized vendor value keeps end_turn semantics — a false Failure on working traffic is worse
than the silent success it replaces (see the ledger note on W4-A for the rejected alternative).
Also not enforced: `response.output_item.done` carrying a refusal message item — a real carrier,
measured Success today, filed as its own item rather than folded in behind this wall.

EXIT 0 = closed. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6): its half-fix fixtures
are DERIVED FROM THE REAL SOURCES (read the file, delete only that dialect's conversion call site,
keeping every buffer, helper, comment and pre-existing Failure token) and it asserts the mutant is
red FOR THE CONVERSION REASON. A hand-written fixture is what let the first cut report OK while the
tree it was guarding had no verdict at all.
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
PASS = ROOT / ("gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/"
               "PassthroughStreamTranslator.kt")
CHAT = ROOT / "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatStreamTranslator.kt"
RESP = ROOT / ("gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/"
               "ResponsesStreamTranslator.kt")
PATHS = {"passthrough": PASS, "chat": CHAT, "responses": RESP}

# Per dialect: ([carrier tokens that must be READ], [call sites proving the honest conversion]).
# Every conversion token was measured at 0 occurrences in HEAD 5840979 and 1 after the fix, so it
# cannot be satisfied by code that was already there.
REQUIRED = {
    "passthrough": (
        ['"refusal" -> ErrorType.API_ERROR to',
         '"pause_turn" -> ErrorType.OVERLOADED to',
         '"model_context_window_exceeded" -> ErrorType.API_ERROR to'],
        ["else -> stopReasonFailure(reason)"],
    ),
    "chat": (
        ['strIfString(obj["refusal"])',
         "appendRefusal(refusalBuf, delta, isDelta = true)",
         "appendRefusal(refusalBuf, msg, isDelta = false)"],
        # The conversion half must pin the INVARIANT (the refusal buffer produces a Failure), not
        # the incidental spelling of the emptiness predicate. Round-2 review required isNotBlank
        # here (a whitespace-only buffer is not a refusal once fragments append verbatim), and a
        # wall keyed to the literal isNotEmpty token went red on that correct fix. Either
        # predicate satisfies the invariant; DELETING the arm still does not.
        ["refusalBuf.isNotBlank() -> TurnOutcome.Failure", "refusalBuf.isNotEmpty() -> TurnOutcome.Failure"],
    ),
    "responses": (
        ['"response.refusal.delta", "response.refusal.done" -> addRefusal(evt)',
         'strIfString(if (isDelta) obj["delta"] else obj["refusal"])',
         'if (strOrEmpty(obj["type"]) == "refusal") reducer.addRefusal(obj)'],
        ["?: refusalFailure(reducer)"],
    ),
}

NO_VERDICT = "reads the signal but never turns it into a provider-reported Failure"


def detect(sources: dict[str, str | None]) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it derived sources directly."""
    problems: list[str] = []
    for name, (carriers, conversion) in REQUIRED.items():
        text = sources.get(name)
        if text is None:
            problems.append(f"{name} translator missing — refusing to pass vacuously")
            continue
        missing = [c for c in carriers if c not in text]
        if missing:
            problems.append(
                f"{name} never reads the backend's refusal/non-clean signal ({', '.join(missing)}) "
                "— the turn ends as a clean success",
            )
            continue
        # ANY-OF: the conversion list holds equivalent spellings of the SAME invariant (see the
        # chat entry). Satisfied when one is present; deleting the arm removes them all.
        unwired = [] if any(c in text for c in conversion) else list(conversion)
        if unwired:
            problems.append(
                f"{name} {NO_VERDICT} ({', '.join(unwired)}) — a read with no verdict is not a gate",
            )
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


def _live() -> dict[str, str | None]:
    return {name: _read(p) for name, p in PATHS.items()}


# The pre-fix shape, kept as a cheap synthetic floor alongside the derived cases below: none of the
# carrier tokens is present, which is literally true of all three files at HEAD 5840979.
PREFIX_SHAPE = {
    "passthrough": "else -> Unit // stop_sequence / end_turn / other",
    "chat": "content reasoning_content tool_calls",
    "responses": "else -> Unit",
}


def _selftest_derived(fails: list[str], live: dict[str, str | None]) -> None:
    """THE control that matters: mutate the REAL sources, one dialect's verdict at a time."""
    if detect(live):
        fails.append(
            "the real sources must be GREEN before a half-fix can be derived from them; "
            f"got {detect(live)}",
        )
        return
    for one, (_carriers, conversion) in REQUIRED.items():
        mutant = dict(live)
        text = live[one]
        assert text is not None  # detect(live) above was empty, so every file was readable
        # ANY-OF conversion lists hold equivalent spellings, so only the spelling actually PRESENT
        # can be deleted to derive the half-fix. Requiring every spelling to exist made the control
        # fail the moment a legitimate refactor changed one (isNotEmpty -> isNotBlank).
        present = [token for token in conversion if token in text]
        if not present:
            fails.append(f"cannot derive a {one} half-fix: none of {conversion!r} is in the real source")
            return
        for token in present:
            text = text.replace(token, "")
        mutant[one] = text
        problems = detect(mutant)
        if not problems:
            fails.append(f"{one} with ONLY its verdict call site deleted must be RED")
        elif not any(p.startswith(one) and NO_VERDICT in p for p in problems):
            # Red for the wrong reason proves nothing about the conversion half.
            fails.append(f"{one} half-fix must be red for the NO-VERDICT reason, got {problems}")


def selftest() -> int:
    fails: list[str] = []
    live = _live()
    _selftest_derived(fails, live)

    if not detect(dict(PREFIX_SHAPE)):
        fails.append("the pre-fix shape must be RED")
    for one in REQUIRED:
        partial = dict(live)
        partial[one] = PREFIX_SHAPE[one]
        if not detect(partial):
            fails.append(f"a gap left open in {one} alone must be RED")
        missing = dict(live)
        missing[one] = None
        if not detect(missing):
            fails.append(f"a missing {one} file must be RED, never a vacuous pass")

    if fails:
        print("W4-A SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("W4-A SELFTEST OK — red on the pre-fix shape, on any single dialect left open, on a "
          "missing file, and — derived from the REAL sources, one dialect at a time — on a tree "
          "that keeps every buffer, helper and comment but deletes the verdict call site.")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_live())
    if problems:
        print("W4-A WALL RED — a backend-sent refusal can still reach the client as a clean success:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("W4-A WALL GREEN: all three dialects read the backend's refusal/non-clean terminal signal "
          "and convert it to a providerReported Failure.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
