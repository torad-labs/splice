#!/usr/bin/env python3
"""WALL for SH-10 — the kimi credential file is MERGED on refresh, never rewritten from scratch.

GAP (RED at authoring, 2026-08-07): KimiAuthProvider writes kimiAuthJson(...) — a fixed six-key
object — over whatever was on disk, dropping every field kimi-cli/kimi-code stores beside ours.
This is the exact shape the 2026-07-18 audit condemned and grok/codex already fixed with merges.

GREEN requires BOTH:
  1. a shared merge primitive exists (mergedCredentialJson in splice.core) — "merge, never
     rewrite" as a property of the primitive, not a habit three files independently remember
     and one already forgot;
  2. the kimi refresh persists the MERGED object through the atomic 0600 credential write
     (SecureFile.writeAtomic0600), not a from-scratch kimiAuthJson(...) rebuild.

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

# 2026-08-18 — HD-28, the re-anchor. Every arm below used to key on `writeSecure(`, the name of a
# one-line PRIVATE forward (`private fun writeSecure(p, c) { SecureFile.writeAtomic0600(p, c) }`)
# that each provider happened to carry. That is an incidental spelling, not the invariant, and it
# failed in BOTH directions: renaming or deleting the forward reddened a wall whose behaviour was
# intact, while routing the persist through a differently-named helper that skipped the atomic 0600
# write stayed GREEN — the exact world-readable-window regression #924 extracted SecureFile to make
# inexpressible. The anchor is now the call that CARRIES the invariant, SecureFile.writeAtomic0600,
# so the wall is blind to what any wrapper is called and red the instant the atomic write is not
# what persists the credential. Selftest arms 7 and 8 hold both directions down permanently.
ATOMIC_WRITE = "SecureFile.writeAtomic0600("

# The regression shape, spelled at the same call site: the atomic write handed the freshly-built
# six-key object instead of the merge. Both receivers are listed because kimiAuthJson moved onto the
# KimiOAuth collaborator (HD-M5) and may move again; a from-scratch rewrite that evades both
# spellings still falls to the `mergedCredentialJson(` arm below.
FROM_SCRATCH = (
    ATOMIC_WRITE + "authPath, kimiAuthJson(",
    ATOMIC_WRITE + "authPath, oauth.kimiAuthJson(",
)


def detect(core: str | None, kimi: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if kimi is None:
        return ["KimiAuthProvider.kt missing — refusing to pass vacuously"]
    if ATOMIC_WRITE not in kimi:
        return ["the kimi credential write no longer reaches SecureFile.writeAtomic0600 — the "
                "atomic 0600 primitive is what closes the world-readable window; refusing to pass "
                "vacuously"]
    problems: list[str] = []
    if core is None or "fun mergedCredentialJson(" not in core:
        problems.append("no shared mergedCredentialJson primitive — merge-never-rewrite is still "
                        "a per-provider habit")
    if any(shape in kimi for shape in FROM_SCRATCH):
        problems.append("kimi still rewrites the credential file from scratch — every foreign "
                        "field (device_id, vendor keys) is dropped on each refresh (the 2026-07-18 "
                        "audit shape grok/codex already fixed)")
    elif "mergedCredentialJson(" not in kimi:
        problems.append("kimi's write no longer routes through the shared merge primitive")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


CORE_OK = "public fun mergedCredentialJson(onDisk: JsonObject?, replacements: JsonObject): JsonObject"
_MERGE = "val merged = CredentialJson.mergedCredentialJson(onDisk, oauth.kimiAuthJson(attempt.tokens, clock()))"
KIMI_OPEN = ATOMIC_WRITE + "authPath, oauth.kimiAuthJson(attempt.tokens, clock()).toString())"
KIMI_OK = _MERGE + "\n" + ATOMIC_WRITE + "authPath, merged.toString())"
# Direction (b): the persist may go through a private forward under ANY name. The wall must be blind
# to that name — it was `writeSecure` for a year and reddening on the rename is the defect this
# re-anchor removes.
KIMI_WRAPPED = (
    _MERGE + "\n"
    "persistCredentialFile(authPath, merged.toString())\n"
    "private fun persistCredentialFile(path: Path, content: String) { "
    + ATOMIC_WRITE + "path, content) }"
)
# Direction (a): the hole the old `writeSecure(` anchor left wide open — a correctly MERGED write
# handed to a helper that never reaches the atomic 0600 primitive. Merge intact, world-readable
# window back. The old anchor passed this; this one must not.
KIMI_UNSAFE = (
    _MERGE + "\n"
    "writeSecure(authPath, merged.toString())\n"
    "private fun writeSecure(path: Path, content: String) { Files.writeString(path, content) }"
)


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
    if detect(CORE_OK, KIMI_WRAPPED):
        fails.append("a merged write through a RENAMED private forward that still reaches the "
                     f"atomic write must stay GREEN, got {detect(CORE_OK, KIMI_WRAPPED)}")
    if not detect(CORE_OK, KIMI_UNSAFE):
        fails.append("a merged write through a helper that SKIPS SecureFile.writeAtomic0600 must "
                     "be RED — the merge is not the only invariant")
    if fails:
        print("SH-10 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("SH-10 SELFTEST OK — red on from-scratch rewrite, missing primitive, private fork, "
          "missing file, shape change, and a write that skips SecureFile.writeAtomic0600; green on "
          "the shared merge under any wrapper name")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(CORE), _read(KIMI))
    if problems:
        print("SH-10 WALL RED — the kimi credential refresh does not merge-and-atomically-persist:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-10 WALL GREEN: kimi merges rotations onto the on-disk object via the shared primitive "
          "and persists them through SecureFile.writeAtomic0600.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
