#!/usr/bin/env python3
"""WALL for CX-09 — a turn nothing will put content on the wire for must END AS AN ERROR.

GAP (RED at authoring, 2026-08-10): three floors guarded the empty-turn case and they did not
tile. For a non-compact Success with no text and no tool use:
  · promote-to-text needs the trimmed thinking >= PROMOTE_MIN_CHARS (40);
  · the empty-model honesty error fired only when thinking < HONESTY_MIN_CHARS (20);
  · so thinking in [20, 40) satisfied NEITHER, and the only thing left that could emit anything
    was the reasoning mirror at TurnPipeline.kt:90.
The mirror is gated twice over — on the operator knob `mirror_reasoning` AND on
showReasoning == TEXT — and neither gate was consulted by the honesty check. With either shut, the
turn reached the client as a clean, EMPTY success: the L3 violation ("a turn that did not complete
normally must never reach the client as clean success") in its purest form, on a turn that
completed normally and carried nothing.

GREEN requires BOTH halves:
  1. ONE PREDICATE — Mirror.kt exports `willMirror` and `mirrorInto` DELEGATES to it. Two copies
     of a gate cascade is how the band re-opens: someone tightens the mirror, the honesty check
     keeps the old answer, and the hole is back with every test still green. Pinning the
     delegation (`if (!willMirror(`) is what makes drift impossible rather than merely unlikely.
  2. THE HONESTY GATE ASKS IT, KNOB INCLUDED — TurnPipeline computes `willMirrorHere` as
     `mirrorReasoning && willMirror(...)` and branches on it. Asking `willMirror` alone would
     still leave the operator-disabled case silently empty, which is one of the two live
     reproductions, so the wall pins the conjunction with the knob and not just the call.

DELIBERATELY NOT ENFORCED: the numeric values of the three thresholds. The item's own analysis
rejected both "lower PROMOTE_MIN_CHARS to 20" (double-emits the same text via promote AND mirror)
and "gate on picked.text.isEmpty() alone" (that branch returns BEFORE the mirror line, converting
every currently-mirrored [20,40) turn into a hard API error — the regression the original
candidate fix would have caused). The invariant is coverage, not any particular number, so the
wall pins the predicate wiring and lets the constants move.

Tokens measured at 0 occurrences in HEAD d0da545, >=1 after the fix.

EXIT 0 = closed. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6), with the half-fixes
DERIVED FROM THE REAL SOURCES one at a time.
"""
from __future__ import annotations

import pathlib
import re
import sys
from collections.abc import Mapping

ROOT = pathlib.Path(__file__).resolve().parents[4]

PATHS = {
    "mirror": "gateway/gateway/src/main/kotlin/splice/gateway/reasoning/Mirror.kt",
    "pipeline": "gateway/gateway/src/main/kotlin/splice/gateway/pipeline/TurnPipeline.kt",
    "passthrough": "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughStreamTranslator.kt",
    "chat": "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatStreamTranslator.kt",
    "responses": "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesStreamTranslator.kt",
    "test": "gateway/gateway/src/test/kotlin/TurnPipelineTest.kt",
}

REQUIRED = {
    "mirror": [
        ("public fun willMirror(thinkingText: String?, showReasoning: ReasoningDisplay, compact: Boolean)",
         "the mirror's gate cascade is not exposed as a predicate, so the honesty check cannot ask "
         "whether the mirror will cover this turn without copying the cascade"),
        ("if (!willMirror(thinkingText, showReasoning, compact)) return false",
         "mirrorInto does not DELEGATE to the predicate — a second copy of the cascade exists and "
         "the two can drift apart, silently re-opening the uncovered band"),
    ],
    "pipeline": [
        # 2026-08-16 — the head-decoupling style migration (HD-M4) moved `willMirror` from a
        # top-level function in Mirror.kt onto `class Mirror`, so the pipeline holds the collaborator
        # (`private val mirror = Mirror()`) and the call gains a receiver. Same predicate, same
        # arguments, same conjunction with the operator knob — behaviour did not change. The
        # invariant either spelling satisfies is that the pipeline's mirror question is
        # `mirrorReasoning && <the one willMirror predicate>`; dropping the knob, or dropping the
        # call, still satisfies neither. This is the same remedy the W4-A wall records for its
        # isNotEmpty/isNotBlank entry: an entry may be a TUPLE of equivalent spellings.
        (("mirrorReasoning && mirror.willMirror(thinkingText, meta.showReasoning, meta.compact)",
          "mirrorReasoning && willMirror(thinkingText, meta.showReasoning, meta.compact)"),
         "the pipeline's mirror question ignores the operator knob, so a turn with the mirror "
         "switched off is still graded as covered and ends clean and empty"),
        ("!outcome.emittedThinking && !willMirrorHere(outcome.thinkingText, meta)",
         "the empty-turn honesty branch does not ask whether a THINKING BLOCK already reached the "
         "client — asking only the text-mirror predicate turns every thinking-only passthrough "
         "turn into an API_ERROR after its content was streamed (the regression caught in review)"),
    ],
    # The signal itself must be produced, or the gate above reads a permanently-false flag and the
    # regression returns silently. One site per dialect that actually opens a thinking block.
    "passthrough": [
        ("if (t.isNotBlank()) emittedThinking = true",
         "passthrough does not record the flag ON CONTENT. Pinning the block-OPEN site instead was "
         "the 2026-08-11 review finding: kimi can open a thinking block and close it having sent "
         "nothing, and counting that as delivered content short-circuits the empty-turn gate, so a "
         "turn carrying zero characters ends as a clean terminal — the L3 hole CX-09 exists to close"),
        ("emittedThinking = emittedThinking,",
         "the recorded flag never reaches the outcome the pipeline reads"),
    ],
    "chat": [
        ("emittedThinking = true", "the chat translator opens a thinking block without recording it"),
        ("emittedThinking = emittedThinking,", "the recorded flag never reaches the outcome"),
    ],
    "responses": [
        ("emittedThinking = true", "the responses translator opens a thinking block without recording it"),
        ("emittedThinking = reducer.emittedThinking,", "the recorded flag never reaches the outcome"),
    ],
    # The BEHAVIOURAL proof. A substring wall cannot execute the pipeline, so it guards the
    # existence of the cells that do: adversarial review showed four mutants that re-open this
    # defect while every wiring token stayed present, and the TESTS are what caught them. Deleting
    # a cell is therefore as much a regression as deleting the code, and must be equally red.
    "test": [
        ("fun `a native thinking block covers the turn even though the text mirror will not fire`",
         "the regression cell is gone — the thinking-only passthrough turn is unproven"),
        ("fun `the harvest-fallback shape is still an honest error - buffer full, wire empty`",
         "the genuinely-empty case is unproven, so the gate could be disabled outright and pass"),
    ],
}


# --- CODE, NOT MENTIONS -------------------------------------------------------------------------
# Adversarial review (2026-08-10) proved every wall in this campaign that matched raw file text was
# satisfiable by a COMMENT or an IMPORT naming the token. Concretely: the CX-02 wall graded a tree
# GREEN where the Responses call body had been replaced by `return system.orEmpty()`, because the
# KDoc above it still said "withCompactDirective"; and the CX-11 wall graded GREEN with its required
# expression moved into a `// TODO(next):` comment and the pre-fix branch restored. Both are exactly
# the regression these walls exist to catch. Tokens are therefore matched against code with comments
# and imports removed — a mention is not a wiring.
_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _alts(token: str | tuple[str, ...]) -> tuple[str, ...]:
    """Equivalent spellings of ONE call site. A bare string is its own only spelling.

    Not a relaxation: every entry must still be matched by SOMETHING in the file, each spelling
    still names a whole call site rather than a bare identifier, and deleting the wiring removes
    every spelling at once. See the dated note on the pipeline entry above.
    """
    return (token,) if isinstance(token, str) else token


def detect(sources: Mapping[str, str | None]) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it derived sources directly."""
    problems: list[str] = []
    for key in PATHS:
        text = sources.get(key)
        if text is None:
            problems.append(f"{key} source missing — refusing to pass vacuously")
            continue
        for token, why in REQUIRED[key]:
            alts = _alts(token)
            if not any(alt in text for alt in alts):
                problems.append(f"{key}: {why} (missing `{alts[0]}`)")
    return problems


def _load() -> dict[str, str | None]:
    out: dict[str, str | None] = {}
    for key, rel in PATHS.items():
        p = ROOT / rel
        out[key] = code_only(p.read_text(encoding="utf-8")) if p.exists() else None
    return out


# The pre-fix shape — literally true of both files at HEAD d0da545.
PREFIX_SHAPE = {
    "mirror": "if (showReasoning != ReasoningDisplay.TEXT || t.length < MIRROR_MIN_CHARS) return false",
    "pipeline": "} else if (outcome.thinkingText.trim().length < HONESTY_MIN_CHARS) {",
    "passthrough": '"thinking" -> Block(Kind.THINKING, sink.openThinking())',
    "chat": "val idx = thinkingBlock ?: sink.openThinking()",
    "responses": "val idx = sink.openThinking()",
    "test": "// no pipeline test existed at HEAD",
}


def selftest() -> int:
    fails: list[str] = []
    live = _load()

    if detect(live):
        fails.append(f"the real sources must be GREEN before half-fixes can be derived: {detect(live)}")
    else:
        for key, checks in REQUIRED.items():
            for token, _why in checks:
                alts = _alts(token)
                text = live[key] or ""
                # ANY-OF entries hold equivalent spellings, so only the spelling actually PRESENT can
                # be deleted to derive the half-fix; requiring every spelling to exist would make the
                # control fail the moment a legitimate refactor changed one.
                present = [alt for alt in alts if alt in text]
                if not present:
                    fails.append(f"cannot derive a {key} half-fix: none of {alts!r} is in the real source")
                    continue
                for alt in present:
                    text = text.replace(alt, "")
                mutant = dict(live)
                mutant[key] = text
                problems = detect(mutant)
                if not any(p.startswith(f"{key}:") and alts[0] in p for p in problems):
                    fails.append(f"deleting `{alts[0]}` from {key} must be RED for its own reason, got {problems}")

    if not detect(dict(PREFIX_SHAPE)):
        fails.append("the pre-fix shape must be RED")

    for key in PATHS:
        partial = dict(live)
        partial[key] = PREFIX_SHAPE[key]
        if not detect(partial):
            fails.append(f"a gap left open in {key} alone must be RED")
        missing = dict(live)
        missing[key] = None
        if not detect(missing):
            fails.append(f"a missing {key} file must be RED, never a vacuous pass")

    if fails:
        print("CX-09 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("CX-09 SELFTEST OK — red on the pre-fix shape, on either file left open, on a missing "
          "file, and — derived from the REAL sources, one token at a time — on a tree that keeps "
          "the predicate but stops asking it, or asks it without the operator knob.")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_load())
    if problems:
        print("CX-09 WALL RED — an empty turn can still reach the client as a clean success:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("CX-09 WALL GREEN: the honesty gate consults the one mirror predicate, operator knob "
          "included, so no band of thinking length ends clean and empty.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
