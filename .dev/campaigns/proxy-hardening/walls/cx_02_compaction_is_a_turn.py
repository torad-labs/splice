#!/usr/bin/env python3
"""WALL for CX-02 — a compaction is built EXACTLY like a turn, in every dialect.

LAW (operator, 2026-09-05): the compaction request HAS TO USE THE SAME MODEL, THE SAME REASONING,
THE SAME TOOLS, EVERYTHING as the session. The backend's prompt cache is an exact-prefix match, so
every compact-only reshaping a request builder does (a directive appended to instructions/system,
tools or tool_choice stripped, tool results folded, images dropped, an effort pin) moves the prefix
from token zero and the most expensive turn class there is reads the whole transcript cold (perf
rows 2026-09-05: compact cached_tokens=0 on every model, on every dialect).

This wall REPLACES the earlier CX-02 wall, which required the opposite (a shared COMPACT MODE
directive emitted by all three dialects). That doctrine produced the miss.

RED when a request builder's CODE (comments and imports stripped) names any compact-shaping token,
or when a dialect's builder test no longer carries the byte-identity canary — the test that builds
one body as a turn and as a compaction and asserts the request bytes are equal.

EXIT 0 = GREEN. EXIT 1 = RED. --selftest = positive controls.
"""
from __future__ import annotations

import pathlib
import re
import sys
from collections.abc import Mapping

ROOT = pathlib.Path(__file__).resolve().parents[4]

# The request-builder surface per dialect: every file where a compaction turn is shaped for the wire.
PATHS: dict[str, list[str]] = {
    "openai-responses": [
        "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesRequestBuilder.kt",
        "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesInputBuilder.kt",
        "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesInputTools.kt",
        "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesToolPlan.kt",
        "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesLite.kt",
        "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesRequestAssembler.kt",
        "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesReasoningKnobs.kt",
        "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesTurnOptions.kt",
    ],
    "openai-chat": [
        "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatRequestBuilder.kt",
    ],
    "anthropic-passthrough": [
        "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughRequestBuilder.kt",
        "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughThinking.kt",
    ],
}

# Any of these in a builder's CODE is a compact-only reshaping of the request — the exact class
# this wall exists to keep out. `opts.compact` / `meta.compact` are allowed ONLY on the line that
# hands the flag to TurnMeta (the response side); a builder that reads the flag anywhere else is
# shaping the request on it.
FORBIDDEN_TOKENS = (
    "withCompactDirective",
    "compactDirective",
    "CompactInstructions",
    "compactAwareInstructions",
    "compactAwareSystem",
    "compactEffortPin",
    "compactEffort",
    "COMPACT MODE",
)
COMPACT_READ = re.compile(r"\b(?:opts|meta)\.compact\b|\bcompact\b\s*\)|!compact\b|\(compact\)|if \(compact\)")
COMPACT_HANDOFF = re.compile(r"compact\s*=\s*(?:opts\.)?compact\b")

CANARY_TESTS = {
    "openai-responses": "gateway/dialect-openai-responses/src/test/kotlin/ResponsesRequestBuilderTest.kt",
    "openai-chat": "gateway/dialect-openai-chat/src/test/kotlin/ChatRequestBuilderTest.kt",
    "anthropic-passthrough": "gateway/dialect-anthropic-passthrough/src/test/kotlin/PassthroughRequestBuilderTest.kt",
}
CANARY_TOKEN = "compaction is built byte-identical to a turn"

_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)
_STRING = re.compile(r'"(?:\\.|[^"\\])*"')


def code_only(text: str | None) -> str | None:
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _compact_reads(code: str) -> list[str]:
    """Lines that READ the compact flag other than to hand it to TurnMeta."""
    hits = []
    for line in code.splitlines():
        if COMPACT_HANDOFF.search(line):
            continue
        if COMPACT_READ.search(_STRING.sub('""', line)):
            hits.append(line.strip())
    return hits


def detect(sources: Mapping[str, str | None]) -> tuple[list[str], str]:
    absent = [k for k, t in sources.items() if t is None]
    if absent:
        return ([f"file not found: {', '.join(sorted(absent))} — a builder moved; refusing to pass vacuously"],
                "inconclusive")
    problems: list[str] = []
    for dialect in PATHS:
        code = sources[dialect] or ""
        found = sorted(t for t in FORBIDDEN_TOKENS if t in code)
        if found:
            problems.append(f"{dialect} shapes the compaction request ({', '.join(found)}) — a compaction "
                            "must be built byte-identical to a turn or the prompt cache misses the transcript")
        reads = _compact_reads(code)
        if reads:
            problems.append(f"{dialect} reads the compact flag while building the request: "
                            + " | ".join(reads[:3]))
    missing = sorted(k for k in CANARY_TESTS if CANARY_TOKEN not in (sources.get(f"canary:{k}") or ""))
    if missing:
        problems.append(f"the byte-identity canary test is gone for {', '.join(missing)} — without it a "
                        "builder can be re-shaped with nothing red")
    return problems, f"{len(PATHS)} dialects checked"


def _read_source(rels: list[str]) -> str | None:
    texts = [(ROOT / r).read_text(encoding="utf-8") if (ROOT / r).exists() else None for r in rels]
    if any(t is None for t in texts):
        return None
    return code_only("\n".join(t for t in texts if t is not None))


def _load() -> dict[str, str | None]:
    out: dict[str, str | None] = {k: _read_source(v) for k, v in PATHS.items()}
    for key, rel in CANARY_TESTS.items():
        p = ROOT / rel
        out[f"canary:{key}"] = p.read_text(encoding="utf-8") if p.exists() else None
    return out


CLEAN = "val instructions = body.system.orEmpty()\nval meta = TurnMeta(compact = opts.compact)\n"
SHAPED = "val instructions = if (opts.compact) withCompactDirective(system) else system\n"
STRIPPED = "val emitTools = quirks.supportsTools && !compact && body.tools.isNotEmpty()\n"
PINNED = "if (compact) quirks.compactEffort?.let { return it }\n"


def _fixture(**overrides: str | None) -> dict[str, str | None]:
    base: dict[str, str | None] = {d: CLEAN for d in PATHS}
    base.update({f"canary:{k}": f"fun `{CANARY_TOKEN}`()" for k in CANARY_TESTS})
    base.update(overrides)
    return base


def selftest() -> int:
    fails = []
    d0, d1, d2 = PATHS
    if detect(_fixture())[0]:
        fails.append(f"a clean tree must be GREEN: {detect(_fixture())[0]}")
    if not detect(_fixture(**{d0: SHAPED}))[0]:
        fails.append("a directive appended on compact must be RED")
    if not detect(_fixture(**{d1: STRIPPED}))[0]:
        fails.append("tools stripped on compact must be RED")
    if not detect(_fixture(**{d2: PINNED}))[0]:
        fails.append("an effort pin on compact must be RED")
    if detect(_fixture(**{d0: code_only("// " + SHAPED) or ""}))[0]:
        fails.append("a comment naming a token is not a shaping — must stay GREEN")
    if not detect(_fixture(**{f"canary:{d0}": "fun `something else`()"}))[0]:
        fails.append("a missing canary test must be RED")
    if not detect(_fixture(**{d2: None}))[0]:
        fails.append("a missing builder file must be RED, never a vacuous pass")
    live = _load()
    if detect(live)[0]:
        fails.append(f"the real sources must be GREEN: {detect(live)[0]}")
    if fails:
        print("CX-02 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("CX-02 SELFTEST OK — red on a directive, on stripped tools, on an effort pin, on a missing "
          "canary and on a missing builder; green on a comment and on the real sources.")
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
    print("CX-02 WALL GREEN: every dialect builds a compaction byte-identical to a turn.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
