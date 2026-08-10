#!/usr/bin/env python3
"""WALL for CX-02 — the COMPACT MODE directive must reach EVERY dialect, from ONE definition.

GAP (RED at authoring, 2026-07-26): the compaction directive was emitted only by the Responses
dialect — `grep -rn "COMPACT MODE" gateway --include=*.kt` found exactly one site,
ResponsesRequestBuilder.kt:376. On kimi (anthropic-passthrough) and every openai-chat head a
compaction turn was therefore an ordinary tool-stripped turn: the backend was never told it was
summarizing, so a chatty non-summary reply was stored SILENTLY as the session's summary.

splice recognises compaction in ONE shared place (gateway/compact/Compact.kt's markers, tools-
agnostic, used by all heads), so any per-dialect divergence in how that recognition is ACTED ON is
drift by construction.

REPAIRED 2026-08-10, and STRENGTHENED in the same edit. The first cut required the literal string
"COMPACT MODE" in each of the three builders. That is an incidental SPELLING, not the invariant,
and it was actively wrong in both directions:

  · FALSE RED on the correct fix. CX-02's own proposal is to lift the text into :core as ONE
    definition and have the builders reference it. Doing exactly that leaves the literal in no
    builder at all, and the old wall reported all three dialects broken on a tree where all three
    work. (Measured: with the fix applied it printed "present in [none]".)
  · FALSE GREEN on the drift it exists to prevent. Three builders each carrying their own pasted
    copy of the sentence satisfied it perfectly — which is the copies-drift failure this campaign
    keeps paying for, and the reason the text was centralized in the first place.

So the invariant is pinned in three parts instead: the shared definition CARRIES the directive
text, every builder REFERENCES it, and no builder RE-SPELLS it locally. Deleting a builder's
wiring is still red; so is pasting the sentence back in.

EXIT 0 = every dialect emits the directive from the one shared definition.  EXIT 1 = otherwise.
--selftest = the POSITIVE CONTROL (gate check C6), including a case derived from the REAL sources.
"""
from __future__ import annotations

import pathlib
import re
import sys
from collections.abc import Mapping

ROOT = pathlib.Path(__file__).resolve().parents[4]
MARKER = "COMPACT MODE"
SHARED = "shared-definition"

# The one definition, and one request-builder surface per dialect — where a compaction turn is
# shaped for the wire.
PATHS = {
    SHARED: "gateway/core/src/main/kotlin/splice/core/turn/CompactInstructions.kt",
    "openai-responses": "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesRequestBuilder.kt",
    "openai-chat": "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatRequestBuilder.kt",
    "anthropic-passthrough": "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughRequestBuilder.kt",
}
DIALECTS = [d for d in PATHS if d != SHARED]

# A builder is wired iff it names the shared directive. Both spellings are the same wiring: the
# text composer and the raw block. These identifiers exist ONLY because the directive is emitted —
# measured 0 occurrences at HEAD c125766, >=1 in every builder after the fix.
WIRING_TOKENS = ("withCompactDirective", "compactDirective")


# Naming the shared helper is not enough: review 2026-08-10 showed a mutant that changed ONE token
# per builder — the caller's `compact` argument to `false` — restoring the original CX-02 bug in all
# three dialects while every wiring token, import and helper stayed in place and the tree compiled
# clean. So each builder must ALSO be shown passing THE TURN'S OWN compact flag into its
# compact-aware path. This is a call-shaped pin and will false-RED on a rename; that is the correct
# direction to fail, and the rename is then a one-line update here.
CALL_SITES = {
    "openai-responses": "compactAwareInstructions(body.system, opts.compact)",
    "openai-chat": "compactAwareSystem(body.system, compact)",
    "anthropic-passthrough": "compactAwareSystem(raw[SYSTEM], compact)",
}

# The canary: each dialect's own test must assert the directive text lands. Deleting the tests and
# neutering the builders was GREEN before this.
CANARY_TESTS = {
    "openai-chat": "gateway/dialect-openai-chat/src/test/kotlin/ChatRequestBuilderTest.kt",
    "anthropic-passthrough": "gateway/dialect-anthropic-passthrough/src/test/kotlin/PassthroughRequestBuilderTest.kt",
}
CANARY_TOKEN = "COMPACT_DIRECTIVE_HEAD"


def _wired(text: str) -> bool:
    return any(token in text for token in WIRING_TOKENS)


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


def detect(sources: Mapping[str, str | None]) -> tuple[list[str], str]:
    """sources: key -> file text (None = file absent). Pure; the selftest feeds it directly."""
    absent = [k for k, t in sources.items() if t is None]
    if absent:
        return ([f"file not found: {', '.join(sorted(absent))} — a builder or the shared "
                 "definition moved; refusing to pass vacuously"], "inconclusive")

    problems: list[str] = []
    shared = sources[SHARED] or ""
    if MARKER not in shared:
        problems.append(
            f"the shared definition does not carry the directive text ('{MARKER}') — the one "
            "place every dialect reads from is empty, so wiring to it proves nothing",
        )

    wired = sorted(d for d in DIALECTS if _wired(sources[d] or ""))
    unwired = sorted(d for d in DIALECTS if not _wired(sources[d] or ""))
    if unwired:
        problems.append(
            f"the compaction directive is dialect-asymmetric — not wired in {', '.join(unwired)}. "
            "On those dialects a compaction turn is an ordinary turn, and a chatty reply is "
            "stored silently as the summary.",
        )

    # The centralization half: a builder that spells the sentence itself is a drifting copy, even
    # though it "has" the directive. This is the failure the old literal-based wall could not see.
    local_copies = sorted(d for d in DIALECTS if MARKER in (sources[d] or ""))
    if local_copies:
        problems.append(
            f"{', '.join(local_copies)} re-spells the directive locally instead of reading the "
            "shared definition — that is the copies-drift class CX-02 centralized the text to end",
        )

    unflagged = sorted(d for d, call in CALL_SITES.items() if call not in (sources.get(d) or ""))
    if unflagged:
        problems.append(
            f"{', '.join(unflagged)} never passes the TURN'S compact flag into its compact-aware "
            "path, so the directive is wired but can never fire — the original CX-02 bug with the "
            "plumbing left in place",
        )

    missing_canary = sorted(k for k in CANARY_TESTS if CANARY_TOKEN not in (sources.get(f"canary:{k}") or ""))
    if missing_canary:
        problems.append(
            f"the directive canary test is gone for {', '.join(missing_canary)} — without it the "
            "builders can be neutered with nothing red",
        )

    summary = f"wired to the shared definition: [{', '.join(wired) or 'none'}]; not wired: [{', '.join(unwired) or 'none'}]"
    return problems, summary


def _load() -> dict[str, str | None]:
    out: dict[str, str | None] = {}
    for key, rel in PATHS.items():
        p = ROOT / rel
        out[key] = code_only(p.read_text(encoding="utf-8")) if p.exists() else None
    for key, rel in CANARY_TESTS.items():
        p = ROOT / rel
        out[f"canary:{key}"] = code_only(p.read_text(encoding="utf-8")) if p.exists() else None
    return out


GOOD_SHARED = f'public val compactDirective: String = "{MARKER} (critical): You are summarizing."'
WIRED = "return withCompactDirective(system, compact = true)"
# A fixture builder is only fully wired when it also forwards the turn's flag; the per-dialect call
# strings are appended in _fixture below.
UNWIRED = 'append("ordinary instructions")'
LOCAL_COPY = f'append("{MARKER} (critical): You are summarizing a coding session.")'


def _fixture(**overrides: str | None) -> dict[str, str | None]:
    base: dict[str, str | None] = {SHARED: GOOD_SHARED}
    base.update({d: WIRED + "\n" + CALL_SITES[d] for d in DIALECTS})
    base.update({f"canary:{k}": CANARY_TOKEN for k in CANARY_TESTS})
    base.update(overrides)
    return base


def selftest() -> int:
    fails = []
    d0, d1, d2 = DIALECTS

    if detect(_fixture())[0]:
        fails.append("all-dialects-wired fixture must be GREEN")

    if not detect(_fixture(**{d1: UNWIRED, d2: UNWIRED}))[0]:
        fails.append("asymmetric fixture (1 of 3 dialects) must be RED")

    if not detect(_fixture(**{d2: UNWIRED}))[0]:
        fails.append("partial fixture (2 of 3 dialects) must be RED — CX-02 requires ALL three")

    # "or in none" was my own imprecision when first writing this wall (caught by this control,
    # 2026-07-26). CX-02's done-condition is that ALL THREE builders emit the directive; a tree
    # where none do is CX-02 un-done AND a regression, so it must stay RED.
    if not detect(_fixture(**{d: UNWIRED for d in DIALECTS}))[0]:
        fails.append("no-dialect fixture must be RED — CX-02 requires ALL three builders wired")

    if not detect(_fixture(**{d2: None}))[0]:
        fails.append("a missing builder file must be RED, never a vacuous pass")

    if not detect(_fixture(**{SHARED: None}))[0]:
        fails.append("a missing shared definition must be RED, never a vacuous pass")

    # The two controls the literal-based wall failed. Both describe trees the old wall graded wrong.
    if not detect(_fixture(**{SHARED: "public val compactDirective: String = \"summarize please\""}))[0]:
        fails.append("a shared definition that lost the directive text must be RED")

    if not detect(_fixture(**{d0: LOCAL_COPY}))[0]:
        fails.append("a builder carrying its OWN pasted copy of the sentence must be RED (drift)")

    # Derived from the REAL sources: delete one builder's wiring and nothing else. A hand-written
    # fixture cannot prove the token is absent for a reason no other code supplies.
    live = _load()
    if detect(live)[0]:
        fails.append(f"the real sources must be GREEN before a half-fix can be derived: {detect(live)[0]}")
    else:
        for one in DIALECTS:
            mutant = dict(live)
            text = live[one] or ""
            for token in WIRING_TOKENS:
                text = text.replace(token, "")
            mutant[one] = text
            problems = detect(mutant)[0]
            if not any("not wired in" in p and one in p for p in problems):
                fails.append(f"{one} with ONLY its wiring deleted must be RED for the unwired reason, got {problems}")

    if fails:
        print("CX-02 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("CX-02 SELFTEST OK — red on 1-of-3 and 2-of-3 asymmetry, on none-of-three, on a missing "
          "builder or shared definition, on a shared definition that lost the text, on a builder "
          "that re-spells the directive locally, and — derived from the REAL sources, one builder "
          "at a time — on a tree with exactly one builder's wiring removed.")
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
    print("CX-02 WALL GREEN: all three dialects emit the compaction directive from one definition.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
