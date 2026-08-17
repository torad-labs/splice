#!/usr/bin/env python3
"""WALL for CX-18 — usage must be read through ONE alias chain, and the real-world alias shapes
must be in it.

GAP (RED at authoring, 2026-08-10): usage parsing was three hand-rolled readers that disagreed.
  · openai-chat read ONLY `prompt_tokens` / `completion_tokens` for the two main buckets. Several
    OpenAI-compatible backends and OpenRouter's Responses-shaped routes emit `input_tokens` /
    `output_tokens`, so those turns landed with ZERO usage.
  · anthropic-passthrough read only the FLAT `cache_creation_input_tokens` and missed Anthropic's
    newer nested `cache_creation: {ephemeral_5m_input_tokens, ephemeral_1h_input_tokens}`.
    successOutcome folds cacheCreation back into inputTokens, so missing it understated the whole
    context-window percentage on every cache-writing turn.
  · chat's own `num(vararg keys)` helper was a fourth reader, used for the cached bucket only.

Wrong usage is not cosmetic: `used_percentage` drives Claude Code's auto-compaction trigger, so a
blind head either never compacts or compacts constantly.

GREEN requires all three, and they are independent failures:
  1. ONE SHARED CHAIN EXISTS — `firstLong` in :core, next to the other JsonNull-safe scalar reads.
  2. EACH TRANSLATOR USES IT for the shapes it missed — chat for the input_/output_ aliases (with
     the CANONICAL spelling first, so a backend emitting both is read by the standard field), and
     passthrough for the nested cache_creation SUM.
  3. NO TRANSLATOR KEEPS A PRIVATE ALIAS READER — chat's `num(obj: JsonObject, vararg keys` is
     gone. Leaving it is how three readers became four; a wall that only checked the new behavior
     would stay green while the drift it exists to end quietly regrew.

The nested read is pinned as a SUM (`parts.sum()`), not merely as a mention of `cache_creation`:
a first-of read over two TTL buckets silently drops one of them, which is the same undercount in a
smaller costume.

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

# The CARRIER files — the one file per key that must hold that key's REQUIRED call sites. These are
# positive tokens only; the FORBIDDEN_READER ban is NOT scoped to this map (see BAN_DIRS).
PATHS = {
    "core": "gateway/core/src/main/kotlin/splice/core/util/JsonScalars.kt",
    "harvest": "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/Harvested.kt",
    # HD-24 (2026-08-17): UsageHud decomposed; firstNum (the delegating alias-chain call) moved to
    # UsageJson.kt (the usage-accounting owner).
    "hud": "gateway/gateway/src/main/kotlin/splice/gateway/usage/UsageJson.kt",
    # HD-24 (2026-08-17): ChatStreamTranslator decomposed; both usage-alias reads moved to
    # ChatUsage.kt (the usage-accounting owner).
    "chat": "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatUsage.kt",
    "passthrough": "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughStreamTranslator.kt",
}

# The NEIGHBOURHOODS the forbidden-reader ban sweeps: the package directory of every carrier above,
# derived from PATHS rather than hand-listed so a future repoint can never leave the ban behind.
#
# REPAIR (2026-08-17, review of a08a438). The ban below reads "a private multi-key reader in ANY
# file that reads usage ... every file, no exemptions", and that was literally true while each key
# named a god file holding the WHOLE usage surface. The HD-24 decompositions silently turned "every
# file" into "five of ninety": UsageHud.kt became eleven siblings and the key was repointed at the
# 48-line UsageJson.kt, leaving UsageRing.kt and RateLimitHeaders.kt — which both read usage scalars
# via usageJson.num — outside the ban entirely. MEASURED before this repair: appending
# `internal fun num(x: JsonObject, vararg keys: String): Long = 0L` to RateLimitHeaders.kt printed
# WALL GREEN exit 0, while the SAME injection into the pre-split UsageHud.kt was RED. ChatUsage.kt
# had the same hole from 7cc7fa0. So a fourth alias reader with divergent numeric parsing could
# regrow anywhere in these packages and ship green — the precise regression this ban exists to
# prevent. cx_01's ed2ba76 carrier-chain remedy fixes the POSITIVE half of this class by naming
# every file that does the work; a NEGATIVE invariant cannot be fixed that way, because the file
# that would carry the violation does not exist yet. It is scoped to the neighbourhood instead, so
# a NEW sibling is covered the moment it is written, with no wall edit and nothing to repoint.
BAN_DIRS = sorted({pathlib.PurePosixPath(rel).parent.as_posix() for rel in PATHS.values()})

# Every token below matches a LITERAL SOURCE SUBSTRING, so a pure-style migration can break one
# while the invariant is fully intact — the class the W4-A wall's isNotEmpty/isNotBlank note
# records. The remedy is that wall's, applied per token: an entry may be a TUPLE of equivalent
# spellings, satisfied by any one of them (see `_alts`). This is not a relaxation — every required
# call site still has to be matched by something in the file, each spelling still names a whole
# call site rather than a bare identifier, and deleting the call site removes every spelling at once.
#
# 2026-08-16 — HD-M8, the :core style slice: `firstLong` (and its `str` siblings) were top-level
# EXTENSIONS on kotlinx's JsonObject. That receiver is a foreign type, so the members could not move
# onto it; they moved onto `object JsonScalars` and the receiver became the FIRST ARGUMENT —
# `u.firstLong("a", "b")` reads `JsonScalars.firstLong(u, "a", "b")`, and the declaration reads
# `public fun firstLong(obj: JsonObject?, vararg keys: String)`. Same function, same precedence
# order, same `toDoubleOrNull()?.toLong()` parsing. The `obj` parameter is nullable only so the
# former safe-call sites (`details?.firstLong(…)`) stay a receiver-to-argument move; a null obj
# reads null exactly as the safe call did. The passthrough entries are untouched by the migration
# (they name a raw `u["cache_creation"]` read and a `parts.sum()`) and stay single spellings.
#
# 2026-08-17 — HD-20, the extension-declaration ban. UsageHud's thin local wrapper was the LAST
# extension in the chain: `private fun JsonObject.firstNum(vararg keys: String) =
# JsonScalars.firstLong(this, *keys)` became `private fun firstNum(obj: JsonObject, vararg keys:
# String) = JsonScalars.firstLong(obj, *keys)`. It still delegates to the ONE :core chain — the
# invariant this wall guards — so the `this` spelling gains `obj` as an equivalent alternative
# rather than the wall being loosened: the hud file must still contain a whole delegating call
# site, and deleting it is still RED (the selftest derives that half-fix from the real source).
REQUIRED = {
    "core": [
        (("public fun firstLong(obj: JsonObject?, vararg keys: String)",
          "public fun JsonObject.firstLong(vararg keys: String)"),
         "the shared alias chain does not exist, so every translator still hand-rolls its own"),
    ],
    "chat": [
        (('JsonScalars.firstLong(u, "prompt_tokens", "input_tokens")',
          'u.firstLong("prompt_tokens", "input_tokens")'),
         "the input bucket has no alias chain — a backend emitting input_tokens lands at zero usage"),
        (('JsonScalars.firstLong(u, "completion_tokens", "output_tokens")',
          'u.firstLong("completion_tokens", "output_tokens")'),
         "the output bucket has no alias chain — a backend emitting output_tokens lands at zero"),
    ],
    "harvest": [
        (('JsonScalars.firstLong(usage, "input_tokens", "prompt_tokens")',
          'usage.firstLong("input_tokens", "prompt_tokens")'),
         "the Responses harvest still hand-rolls its own alias reader — the very 'fourth reader' "
         "the item forbade, and with the OPPOSITE precedence to the chat chain"),
    ],
    "hud": [
        (("JsonScalars.firstLong(obj, *keys)", "JsonScalars.firstLong(this, *keys)", "firstLong(*keys)"),
         "the HUD payload builder keeps a SECOND shared chain with different numeric parsing "
         "(toDouble vs toLong), so the same bytes yield different usage depending on the reader"),
    ],
    "passthrough": [
        ('u["cache_creation"] as? JsonObject',
         "the nested per-TTL cache_creation object is never read, understating inputTokens and "
         "therefore the context-window percentage on every cache-writing turn"),
        ("parts.sum()",
         "the nested TTL buckets are not SUMMED — reading one of them drops the other, the same "
         "undercount this item exists to fix"),
    ],
}

# Shapes that must NOT survive: a private multi-key reader in ANY file that reads usage. A literal
# ban was defeated by renaming one parameter (review 2026-08-10: `obj` -> `o` walked straight past
# it), and it was only ever applied to the chat file while Harvested.kt carried the byte-identical
# signature untouched. Regex, every file in every BAN_DIRS neighbourhood, no exemptions.
FORBIDDEN_READER = re.compile(r"fun\s+num\s*\([^)]*vararg\s+keys")
FORBIDDEN_WHY = ("keeps a private multi-key alias reader alongside the shared chain — a fourth "
                 "reader is exactly what the item forbade, and they drift apart on numeric parsing")


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


def _alts(entry: str | tuple[str, ...]) -> tuple[str, ...]:
    """Equivalent spellings of ONE call site. A bare string is its own only spelling."""
    return (entry,) if isinstance(entry, str) else entry


def detect(sources: Mapping[str, str | None]) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it derived sources directly.

    `sources` carries the five CARRIER keys plus one key per neighbourhood file (keyed by its
    repo-relative path). Required tokens are checked on carriers; the forbidden reader is checked on
    EVERY key, so a sibling written after this wall was last touched is banned too.
    """
    problems: list[str] = []
    for key in PATHS:
        text = sources.get(key)
        if text is None:
            problems.append(f"{key} source missing — refusing to pass vacuously")
            continue
        for entry, why in REQUIRED.get(key, []):
            alts = _alts(entry)
            if not any(token in text for token in alts):
                problems.append(f"{key}: {why} (missing `{' | '.join(alts)}`)")
    for key, text in sources.items():
        if text is None:
            if key not in PATHS:
                problems.append(f"{key} missing — refusing to sweep a neighbourhood vacuously")
            continue
        found = FORBIDDEN_READER.search(text)
        if found:
            problems.append(f"{key}: {FORBIDDEN_WHY} (found `{found.group(0)}`)")
    return problems


def _neighbours() -> dict[str, str | None]:
    """Every .kt in every BAN_DIRS package that is not already a carrier. A missing package reads as
    a None key, so deleting or moving a whole package is RED rather than a silently empty sweep."""
    carriers = set(PATHS.values())
    out: dict[str, str | None] = {}
    for rel in BAN_DIRS:
        d = ROOT / rel
        if not d.is_dir():
            out[f"{rel}/*.kt"] = None
            continue
        for p in sorted(d.glob("*.kt")):
            key = p.relative_to(ROOT).as_posix()
            if key not in carriers:
                out[key] = code_only(p.read_text(encoding="utf-8"))
    return out


def _load() -> dict[str, str | None]:
    out: dict[str, str | None] = {}
    for key, rel in PATHS.items():
        p = ROOT / rel
        out[key] = code_only(p.read_text(encoding="utf-8")) if p.exists() else None
    out.update(_neighbours())
    return out


# The pre-fix shape — literally true of all three files at HEAD d0da545.
PREFIX_SHAPE = {
    "harvest": 'fun num(obj: JsonObject, vararg keys: String): Long = 0L',
    "hud": "private fun JsonObject.firstNum(vararg k: String): Long? = null",
    "core": "public fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()",
    "chat": '(u["prompt_tokens"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { inputTokens = it }',
    "passthrough": 'num(u, "cache_creation_input_tokens")?.let { cacheCreation = it }',
}


def selftest() -> int:
    fails: list[str] = []
    live = _load()

    if detect(live):
        fails.append(f"the real sources must be GREEN before half-fixes can be derived: {detect(live)}")
    else:
        for key, checks in REQUIRED.items():
            for entry, _why in checks:
                # ANY-OF entries hold equivalent spellings, so only the spelling actually PRESENT can
                # be deleted to derive the half-fix — and every present spelling must go, or the
                # remaining one keeps the wall green and proves nothing.
                alts = _alts(entry)
                text = live[key] or ""
                present = [token for token in alts if token in text]
                if not present:
                    fails.append(f"cannot derive a {key} half-fix: none of {alts!r} is in the real source")
                    continue
                for token in present:
                    text = text.replace(token, "")
                mutant = dict(live)
                mutant[key] = text
                problems = detect(mutant)
                if not any(p.startswith(f"{key}:") and any(t in p for t in alts) for p in problems):
                    fails.append(
                        f"deleting `{' | '.join(present)}` from {key} must be RED for its own reason, got {problems}",
                    )

        # The forbidden-shape control: re-introducing the private reader must go RED even though
        # every required token is still present. This is the half a behavior-only wall misses.
        for victim, spelling in (("chat", "fun num(obj: JsonObject, vararg keys: String): Long = 0L"),
                                 ("chat", "fun num(o: JsonObject, vararg keys: String): Long = 0L"),
                                 ("harvest", "fun num(x: JsonObject, vararg keys: String): Long = 0L")):
            regrown = dict(live)
            regrown[victim] = (live[victim] or "") + "\n    " + spelling + "\n"
            if not any("private multi-key alias reader" in p for p in detect(regrown)):
                fails.append(f"a re-introduced private reader in {victim} ({spelling}) must be RED")

        # The NEIGHBOURHOOD control (repair, 2026-08-17): the same regrowth in a SIBLING must be RED
        # too, or a decomposition quietly moves the surface out from under the ban. Every non-carrier
        # file in every banned package, one at a time — this is the instrument that caught a08a438.
        neighbours = [k for k in live if k not in PATHS]
        if not neighbours:
            fails.append("no neighbourhood files were swept — the ban would be scoped to carriers again")
        for key in neighbours:
            regrown = dict(live)
            regrown[key] = (live[key] or "") + "\n    " + \
                "internal fun num(x: JsonObject, vararg keys: String): Long = 0L\n"
            if not any(p.startswith(f"{key}:") and "private multi-key alias reader" in p
                       for p in detect(regrown)):
                fails.append(f"a private reader regrown in the sibling {key} must be RED")

        # And a whole package going missing must be RED, never an empty sweep reading as clean.
        for rel in BAN_DIRS:
            gone = {k: v for k, v in live.items() if not k.startswith(f"{rel}/")}
            gone[f"{rel}/*.kt"] = None
            if not any("refusing to sweep a neighbourhood vacuously" in p for p in detect(gone)):
                fails.append(f"a missing {rel} package must be RED, never a vacuous sweep")

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
        print("CX-18 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("CX-18 SELFTEST OK — red on the pre-fix shape, on any single source left open, on a "
          "missing file, on a missing banned package, on a re-introduced private alias reader in a "
          "carrier AND in every swept sibling, and — derived from the REAL sources, one token at a "
          "time — on a tree missing any one half of the shared chain.")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_load())
    if problems:
        print("CX-18 WALL RED — usage parsing can still land a real backend at zero or partial:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("CX-18 WALL GREEN: one shared alias chain, the real-world alias shapes are in it, and no "
          "translator keeps a private reader.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
