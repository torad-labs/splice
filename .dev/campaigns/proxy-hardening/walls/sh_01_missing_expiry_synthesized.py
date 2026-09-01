#!/usr/bin/env python3
"""WALL for SH-01 — ONE missing-expiry policy: no provider may treat a credential as never-expiring.

GAP (RED at authoring, 2026-08-07): G18 fixed the class on grok (mtime+4h synthesis); codex has
the identical hole (a non-JWT / exp-less access token yields expiresAtMs=null => cached forever,
no proactive refresh, first signal is a mid-turn 401) and kimi's `?: 0L` produces a refresh PER
CALL below the hard floor instead of one.

GREEN requires ALL of:
  1. a shared core helper (synthesizedExpiryMs) exists in splice.core.auth;
  2. codex synthesizes: its exp-derived expiry expression falls back to synthesizedExpiryMs;
  3. kimi synthesizes: no `expires_at") ?: 0L` remains;
  4. grok consumes the SHARED helper (its private SYNTHETIC_EXPIRY_TTL_MS copy is gone) — one
     rule, not three implementations that drift.

EXIT 0 = one policy everywhere. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
CORE = ROOT / "gateway/core/src/main/kotlin/splice/core/auth/SynthesizedExpiry.kt"
CODEX = ROOT / "gateway/provider-codex/src/main/kotlin/splice/provider/codex/CodexAuthProvider.kt"
KIMI = ROOT / "gateway/provider-kimi/src/main/kotlin/splice/provider/kimi/KimiAuthProvider.kt"
GROK = ROOT / "gateway/provider-grok/src/main/kotlin/splice/provider/grok/GrokAuthProvider.kt"


def detect(
    core: str | None,
    codex: str | None,
    kimi: str | None,
    grok: str | None,
    *,
    kimi_raw: str | None,
    grok_raw: str | None,
) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly.

    `core`/`codex`/`kimi`/`grok` are the CODE views (comments and imports stripped) and carry every
    REQUIRED token; `kimi_raw`/`grok_raw` are the untouched texts and carry the two BANs (kimi's
    `?: 0L` floor, grok's private TTL copy). The two directions want opposite treatment — see
    code_only.
    """
    for name, text in (("CodexAuthProvider", codex), ("KimiAuthProvider", kimi), ("GrokAuthProvider", grok)):
        if text is None:
            return [f"{name}.kt missing — refusing to pass vacuously"]
    problems: list[str] = []
    if core is None or "fun synthesizedExpiryMs(" not in core:
        problems.append("no shared splice.core.auth.synthesizedExpiryMs — the missing-expiry rule "
                        "has no single source")
    if "synthesizedExpiryMs(" not in (codex or ""):
        problems.append("codex does not synthesize a missing expiry — a non-JWT/exp-less access "
                        "token is cached FOREVER (no proactive refresh, 401-only recovery)")
    if 'expires_at") ?: 0L' in (kimi_raw or ""):
        problems.append("kimi still floors a missing expires_at to 0 — one refresh per call below "
                        "the hard floor instead of one per synthesized ceiling")
    elif "synthesizedExpiryMs(" not in (kimi or ""):
        problems.append("kimi does not use the shared synthesis for a missing expires_at")
    if "SYNTHETIC_EXPIRY_TTL_MS = " in (grok_raw or ""):
        problems.append("grok still declares its private SYNTHETIC_EXPIRY_TTL_MS — the rule must "
                        "have ONE source (core), not a copy that can drift")
    elif "synthesizedExpiryMs(" not in (grok or ""):
        problems.append("grok no longer synthesizes at all (G18 regression) — refusing to pass")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy a
    REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
    Proven against this wall's own sources: with codex's one `CredentialExpiry.synthesizedExpiryMs(
    mtimeMs, clock())` call deleted and its literal text left in a TODO, the raw-matching wall
    printed WALL GREEN while a non-JWT codex token was cached forever again. Grok already carries a
    review note naming `synthesizedExpiryMs(` in a comment, so its required token was one deletion
    away from the same free pass.

    Applied to _read (the required tokens) and deliberately NOT to _read_raw, which feeds the two
    BANs. The two directions want opposite treatment: stripping makes a required token harder to
    satisfy, but would make a banned string easier to hide. Both stay strict this way."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read_raw(p: pathlib.Path) -> str | None:
    """Untouched file text — the view the BANs are matched against (see code_only)."""
    return p.read_text(encoding="utf-8") if p.exists() else None


def _read(p: pathlib.Path) -> str | None:
    return code_only(_read_raw(p))


CORE_OK = "public fun synthesizedExpiryMs(mtimeMs: Long): Long"
GOOD = "synthesizedExpiryMs(mtime)"
CODEX_OPEN = "val expiresAtMs = decodeJwtClaims(access).long(FIELD_EXP)?.let { it * MS_PER_S }"
KIMI_OPEN = 'expiresAtS = obj.long("expires_at") ?: 0L,'
GROK_OPEN = "const val SYNTHETIC_EXPIRY_TTL_MS = 4L\n(mtime + SYNTHETIC_EXPIRY_TTL_MS)"


CODEX_COMMENTED = ("val expiresAtMs = null\n"
                   "// TODO(SH-01): restore CredentialExpiry.synthesizedExpiryMs(mtimeMs, clock())")
GROK_HIDDEN_FORK = "synthesizedExpiryMs(mtime, now)\n// const val SYNTHETIC_EXPIRY_TTL_MS = 4L"


def selftest() -> int:
    fails = []
    if not detect(None, CODEX_OPEN, KIMI_OPEN, GROK_OPEN, kimi_raw=KIMI_OPEN, grok_raw=GROK_OPEN):
        fails.append("today's tree shape (no helper, codex nullable, kimi 0L, grok private) must be RED")
    if detect(CORE_OK, GOOD, GOOD, GOOD, kimi_raw=GOOD, grok_raw=GOOD):
        fails.append(f"one-policy-everywhere must be GREEN, got "
                     f"{detect(CORE_OK, GOOD, GOOD, GOOD, kimi_raw=GOOD, grok_raw=GOOD)}")
    if not detect(CORE_OK, CODEX_OPEN, GOOD, GOOD, kimi_raw=GOOD, grok_raw=GOOD):
        fails.append("codex without synthesis must be RED")
    if not detect(CORE_OK, GOOD, KIMI_OPEN, GOOD, kimi_raw=KIMI_OPEN, grok_raw=GOOD):
        fails.append("kimi with ?: 0L must be RED")
    if not detect(CORE_OK, GOOD, GOOD, GROK_OPEN, kimi_raw=GOOD, grok_raw=GROK_OPEN):
        fails.append("grok with a private TTL copy must be RED")
    if not detect(CORE_OK, None, GOOD, GOOD, kimi_raw=GOOD, grok_raw=GOOD):
        fails.append("a missing provider file must be RED, never a vacuous pass")
    # HD-26 comment-satisfiability controls. Both directions, so a later blind sweep that strips the
    # bans too (or stops stripping the required tokens) breaks the selftest instead of the invariant.
    if detect(CORE_OK, CODEX_COMMENTED, GOOD, GOOD, kimi_raw=GOOD, grok_raw=GOOD):
        fails.append("the raw shape must read GREEN — otherwise this fixture is not the bug and "
                     "the control below proves nothing")
    if not detect(CORE_OK, code_only(CODEX_COMMENTED), GOOD, GOOD, kimi_raw=GOOD, grok_raw=GOOD):
        fails.append("a codex synthesis that survives only as comment text must be RED — required "
                     "tokens are matched against code, never raw file text")
    if not detect(CORE_OK, GOOD, GOOD, code_only(GROK_HIDDEN_FORK),
                  kimi_raw=GOOD, grok_raw=GROK_HIDDEN_FORK):
        fails.append("a private grok TTL commented out of the code view must still be RED — the "
                     "bans read RAW so a comment cannot hide one")
    if fails:
        print("SH-01 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("SH-01 SELFTEST OK — red on missing helper, codex-forever, kimi-0L, grok private copy, "
          "and missing files; green only on one shared policy in all three providers")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(CORE), _read(CODEX), _read(KIMI), _read(GROK),
                      kimi_raw=_read_raw(KIMI), grok_raw=_read_raw(GROK))
    if problems:
        print("SH-01 WALL RED — the missing-expiry policy is not one-source-three-providers:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-01 WALL GREEN: synthesizedExpiryMs is the one missing-expiry policy, all providers on it.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
