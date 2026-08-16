#!/usr/bin/env python3
"""WALL for SH-10 — the kimi credential file is MERGED on refresh, never rewritten from scratch.

GAP (RED at authoring, 2026-08-07): KimiAuthProvider writes kimiAuthJson(...) — a fixed six-key
object — over whatever was on disk, dropping every field kimi-cli/kimi-code stores beside ours.
This is the exact shape the 2026-07-18 audit condemned and grok/codex already fixed with merges.

GREEN requires BOTH:
  1. a shared merge primitive exists (mergedCredentialJson in splice.core) — "merge, never
     rewrite" as a property of the primitive, not a habit three files independently remember
     and one already forgot;
  2. the kimi refresh write routes through it (no bare writeSecure(authPath, kimiAuthJson(...))).

EXIT 0 = merged. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
# 2026-08-16 — HD-M8, the :core style slice. `mergedCredentialJson` was a top-level function; the
# Kotlin style law bans those, and both of its arguments are kotlinx JsonObjects (a foreign receiver
# that cannot host a member), so it became a member of `object CredentialJson`. detekt then forces
# two things at once: MatchingDeclarationName wants the file named after its single declaration, and
# MemberNameEqualsClassName forbids `object MergedCredentialJson { fun mergedCredentialJson }` — so
# the FILE moved, MergedCredentialJson.kt -> CredentialJson.kt. Same primitive, same function name,
# same merge order; the token below is unchanged and the "core source missing" arm still refuses a
# vacuous pass if this path ever goes stale again.
CORE = ROOT / "gateway/core/src/main/kotlin/splice/core/auth/CredentialJson.kt"
KIMI = ROOT / "gateway/provider-kimi/src/main/kotlin/splice/provider/kimi/KimiAuthProvider.kt"


def detect(core: str | None, kimi: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if kimi is None:
        return ["KimiAuthProvider.kt missing — refusing to pass vacuously"]
    if "writeSecure(" not in kimi:
        return ["the kimi persist site disappeared (shape changed?) — refusing to pass vacuously"]
    problems: list[str] = []
    if core is None or "fun mergedCredentialJson(" not in core:
        problems.append("no shared mergedCredentialJson primitive — merge-never-rewrite is still "
                        "a per-provider habit")
    if "writeSecure(authPath, kimiAuthJson(" in kimi:
        problems.append("kimi still rewrites the credential file from scratch — every foreign "
                        "field (device_id, vendor keys) is dropped on each refresh (the 2026-07-18 "
                        "audit shape grok/codex already fixed)")
    elif "mergedCredentialJson(" not in kimi:
        problems.append("kimi's write no longer routes through the shared merge primitive")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


CORE_OK = "public fun mergedCredentialJson(onDisk: JsonObject?, replacements: JsonObject): JsonObject"
KIMI_OPEN = "writeSecure(authPath, kimiAuthJson(attempt.tokens, clock()).toString())"
KIMI_OK = "writeSecure(authPath, mergedCredentialJson(onDisk, kimiAuthJson(attempt.tokens, clock())).toString())"


def selftest() -> int:
    fails = []
    if not detect(None, KIMI_OPEN):
        fails.append("from-scratch rewrite with no primitive must be RED")
    if detect(CORE_OK, KIMI_OK):
        fails.append(f"primitive + merged write must be GREEN, got {detect(CORE_OK, KIMI_OK)}")
    if not detect(CORE_OK, KIMI_OPEN):
        fails.append("primitive present but kimi still rewriting must be RED")
    if not detect(None, KIMI_OK):
        fails.append("kimi merged but no shared primitive must be RED (a private fork can drift)")
    if not detect(CORE_OK, None):
        fails.append("missing KimiAuthProvider.kt must be RED, never a vacuous pass")
    if not detect(CORE_OK, "class KimiAuthProvider"):
        fails.append("an unrecognized persist shape must be RED, never a vacuous pass")
    if fails:
        print("SH-10 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("SH-10 SELFTEST OK — red on from-scratch rewrite, missing primitive, private fork, "
          "missing file, and shape change; green only on the shared merge")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(CORE), _read(KIMI))
    if problems:
        print("SH-10 WALL RED — the kimi credential file is rewritten from scratch on refresh:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-10 WALL GREEN: kimi merges rotations onto the on-disk object via the shared primitive.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
