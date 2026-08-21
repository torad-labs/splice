#!/usr/bin/env python3
"""WALL for CX-11 — the loop guard must read Anthropic's STRUCTURED failure verdict, not only a
Claude Code formatting string.

GAP (RED at authoring, 2026-08-10): `ToolResultBlock` did not declare `is_error`, so Anthropic's
documented boolean was discarded on every dialect — a repo-wide grep for `is_error`/`isError`
across `gateway/` returned zero hits. The only failure signal LoopGuard had was the literal
`<tool_use_error>`, a Claude Code internal formatting detail with NO canary and NO drift alarm
(contrast Compact.kt's markers, which have both). A wording change upstream silently disarms the
circuit breaker for the 89-101x identical-failed-call pathology it exists to stop, and nothing
fails — the guard just never arms again.

GREEN requires BOTH halves, and they are separate failures:
  1. THE FIELD IS PARSED — ToolResultBlock declares @SerialName("is_error"). Without this the
     structured signal never reaches any consumer, whatever LoopGuard does.
  2. THE GUARD PREFERS IT, WITH THE STRING AS FALLBACK — one expression, `isError ?: (marker)`.
     Both directions are wrong on their own:
       · reading is_error and DROPPING the marker fallback breaks every client that omits the
         field (today's Claude Code sends the marker), silently disarming the guard for them;
       · keeping the marker as an OR rather than a fallback re-arms on `is_error: false` results
         whose OUTPUT merely quotes the marker (a grep hit, a test log) — a false circuit-break
         that tells the model to stop doing something that worked.
     The elvis is what encodes "the client's structured verdict is authoritative, the string only
     answers when the client said nothing", so the wall pins the elvis, not the two tokens apart.

Every token below was measured at 0 occurrences in HEAD d0da545 and >=1 after the fix, so none can
be satisfied by code that was already there for another reason.

EXIT 0 = closed. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6): the half-fixes are
DERIVED FROM THE REAL SOURCES, deleting exactly one half at a time and asserting the mutant is red
for THAT half's reason.
"""
from __future__ import annotations

import pathlib
import re
import sys
from collections.abc import Mapping

ROOT = pathlib.Path(__file__).resolve().parents[4]

PATHS = {
    "wire": "gateway/core/src/main/kotlin/splice/core/wire/ContentBlock.kt",
    "guard": "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/LoopGuard.kt",
}

# key -> (token, why it is the thing that must exist)
REQUIRED = {
    "wire": [
        ('@SerialName("is_error")',
         "ToolResultBlock never declares Anthropic's structured failure field, so it is discarded "
         "on every dialect and the guard has only a formatting string to go on"),
    ],
    "guard": [
        ("block.isError ?: (ERROR_MARKER in text)",
         "the guard does not prefer the structured verdict with the marker as FALLBACK — either "
         "the string is still the only signal, or it now overrides an explicit is_error:false"),
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


def detect(sources: Mapping[str, str | None]) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it derived sources directly."""
    problems: list[str] = []
    for key, checks in REQUIRED.items():
        text = sources.get(key)
        if text is None:
            problems.append(f"{key} source missing — refusing to pass vacuously")
            continue
        for token, why in checks:
            if token not in text:
                problems.append(f"{key}: {why} (missing `{token}`)")
    return problems


def _load() -> dict[str, str | None]:
    out: dict[str, str | None] = {}
    for key, rel in PATHS.items():
        p = ROOT / rel
        out[key] = code_only(p.read_text(encoding="utf-8")) if p.exists() else None
    return out


# The pre-fix shape, kept as a cheap synthetic floor: literally true of both files at HEAD d0da545.
PREFIX_SHAPE = {
    "wire": '@SerialName("tool_use_id") val toolUseId: String = ""',
    "guard": "if (ERROR_MARKER in text) {",
}


def selftest() -> int:
    fails: list[str] = []
    live = _load()

    if detect(live):
        fails.append(f"the real sources must be GREEN before half-fixes can be derived: {detect(live)}")
    else:
        # Derived controls: delete exactly one required token from the REAL file, keeping every
        # other line — comments, helpers and the pre-existing marker constant all stay.
        for key, checks in REQUIRED.items():
            for token, _why in checks:
                mutant = dict(live)
                mutant[key] = (live[key] or "").replace(token, "")
                problems = detect(mutant)
                if not any(p.startswith(f"{key}:") and token in p for p in problems):
                    fails.append(f"deleting `{token}` from {key} must be RED for its own reason, got {problems}")

    if not detect(dict(PREFIX_SHAPE)):
        fails.append("the pre-fix shape must be RED")

    for key in REQUIRED:
        partial = dict(live)
        partial[key] = PREFIX_SHAPE[key]
        if not detect(partial):
            fails.append(f"a gap left open in {key} alone must be RED")
        missing = dict(live)
        missing[key] = None
        if not detect(missing):
            fails.append(f"a missing {key} file must be RED, never a vacuous pass")

    if fails:
        print("CX-11 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("CX-11 SELFTEST OK — red on the pre-fix shape, on either half left open, on a missing "
          "file, and — derived from the REAL sources, one token at a time — on a tree that keeps "
          "every comment, constant and helper but drops one half of the structured-verdict read.")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_load())
    if problems:
        print("CX-11 WALL RED — the loop guard depends on an uncanaried formatting string:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("CX-11 WALL GREEN: is_error is parsed and the guard prefers it, keeping the marker as "
          "the fallback for clients that omit the field.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
