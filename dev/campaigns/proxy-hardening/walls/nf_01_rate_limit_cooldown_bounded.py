#!/usr/bin/env python3
"""WALL for NF-01 — the per-head 429 cooldown horizon must be bounded AND clearable.

GAP (RED at authoring, 2026-07-26): UpstreamClient.kt:407-408 armed a head-wide fail-fast horizon
straight from provider pushback with no ceiling —
    val until = clock() + (failed.retryAfterMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS)
    rateLimitedUntilMs.accumulateAndGet(until) { current, candidate -> maxOf(current, candidate) }
`accumulateAndGet(max)` means the LONGEST value ever seen wins permanently, so one malformed or
multi-day `Retry-After` poisons the head for every concurrent and future turn. HeadServer.restart()
rebuilds the Netty engine and calls driver.resetHealth() only — it cannot clear this AtomicLong.

RE-ANCHORED 2026-08-18 (HD-25): the horizon and every one of its touch points moved out of
UpstreamClient.kt into splice/spi/RateLimitCooldown.kt — deliberately together, so the shared
mutable state and its rules could not be split. The wall follows the code and gains a leg rather
than losing one: `clearRateLimitCooldown()` is now a DELEGATE on UpstreamClient, so the chain
HeadServer -> UpstreamClient.clearRateLimitCooldown -> RateLimitCooldown.clear must be checked
link by link. A delegate that no longer reaches the AtomicLong is a restart that silently stops
being an escape hatch, which is exactly the half-fix this wall exists to catch. Not broadened:
still exact files, exact expressions, no module-wide or substring matching.

Three independent conditions (a clamp with no escape hatch is a fraction of the fix):
  1. a MAX_RATE_LIMIT_COOLDOWN_MS clamp exists AND is APPLIED where the horizon is armed
  2. RateLimitCooldown.clear() exists AND actually zeroes the AtomicLong
  3. UpstreamClient.clearRateLimitCooldown() exists AND delegates to it AND HeadServer calls it

EXIT 0 = all closed.  EXIT 1 = any open.
--selftest = the POSITIVE CONTROL (gate check C6): proves this wall separates open from closed,
             including the declared-but-unapplied, exists-but-uncalled and delegates-nowhere
             half-fixes.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
COOLDOWN = ROOT / "gateway/provider-spi/src/main/kotlin/splice/spi/RateLimitCooldown.kt"
UPSTREAM = ROOT / "gateway/provider-spi/src/main/kotlin/splice/spi/UpstreamClient.kt"
HEADSERVER = ROOT / "gateway/gateway/src/main/kotlin/splice/gateway/head/HeadServer.kt"

ARM_RE = re.compile(r"rateLimitedUntilMs\.accumulateAndGet\(.*?\n", re.S)
CLEAR_DEF_RE = re.compile(r"fun\s+clear\s*\(\s*\)")
DELEGATE_RE = re.compile(r"fun\s+clearRateLimitCooldown\s*\([^)]*\)[^{=]*[{=]\s*\n?\s*cooldown\.clear\(\)")
CLAMP = "MAX_RATE_LIMIT_COOLDOWN_MS"


def detect(cooldown: str | None, upstream: str | None, headserver: str | None) -> list[str]:
    """Pure detection — no I/O, so the selftest can feed synthetic sources."""
    if cooldown is None or upstream is None or headserver is None:
        return ["RateLimitCooldown.kt, UpstreamClient.kt or HeadServer.kt missing — "
                "refusing to pass vacuously"]
    problems: list[str] = []

    arm = ARM_RE.search(cooldown)
    if not arm:
        return ["the cooldown arming site (rateLimitedUntilMs.accumulateAndGet) was not found in "
                "RateLimitCooldown.kt — shape changed; refusing to pass vacuously"]

    # The clamp must appear in the EXPRESSION that computes the horizon, not merely somewhere in
    # the preceding bytes. An earlier version scanned a 400-char lookbehind window, which counted a
    # `const val MAX_… = …` declaration sitting just above as "applied" — a false pass its own
    # positive control caught (2026-07-26). Bind to the assignment and the arming call instead.
    arm_expr = "\n".join(
        ln for ln in cooldown.splitlines()
        if ("val until" in ln and ("nowMs" in ln or "clock()" in ln))
        or "rateLimitedUntilMs.accumulateAndGet" in ln
    )

    if CLAMP not in cooldown:
        problems.append(f"no {CLAMP} clamp constant in RateLimitCooldown.kt "
                        "(only DEFAULT_RATE_LIMIT_COOLDOWN_MS exists)")
    elif CLAMP not in arm_expr:
        problems.append(f"{CLAMP} is declared but NOT applied in the horizon expression "
                        "(`val until = … + …` / accumulateAndGet) — a declared-but-unused clamp "
                        "bounds nothing")

    if not CLEAR_DEF_RE.search(cooldown) or "rateLimitedUntilMs.set(0L)" not in cooldown:
        problems.append("RateLimitCooldown has no clear() that zeroes rateLimitedUntilMs — "
                        "restart cannot clear an armed horizon")
    elif not DELEGATE_RE.search(upstream):
        problems.append("UpstreamClient.clearRateLimitCooldown() does not delegate to "
                        "cooldown.clear() — the head-facing escape hatch no longer reaches the "
                        "AtomicLong it is supposed to zero")
    elif "clearRateLimitCooldown(" not in headserver:
        problems.append("clearRateLimitCooldown() exists but HeadServer never calls it — "
                        "restart is still not an escape hatch")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


_ARM_OPEN = ("val until = nowMs + (pushbackMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS)\n"
             "rateLimitedUntilMs.accumulateAndGet(until) { c, cand -> maxOf(c, cand) }\n")
_ARM_CLAMPED = ("val until = nowMs + minOf(pushbackMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS, "
                "MAX_RATE_LIMIT_COOLDOWN_MS)\n"
                "rateLimitedUntilMs.accumulateAndGet(until) { c, cand -> maxOf(c, cand) }\n")
_CLEAR_DEF = "fun clear() { rateLimitedUntilMs.set(0L) }\n"
_UC_DELEGATES = "public fun clearRateLimitCooldown() {\n    cooldown.clear()\n}\n"
_UC_DELEGATES_NOWHERE = "public fun clearRateLimitCooldown() {\n    log(\"cleared\")\n}\n"
_HS_CALLS = "driver.resetHealth(); upstream.clearRateLimitCooldown()\n"
_HS_PLAIN = "driver.resetHealth()\n"


def selftest() -> int:
    fails = []

    def case(name, cd, uc, hs, want_red):
        got = detect(cd, uc, hs)
        if want_red and not got:
            fails.append(f"{name}: must be RED")
        if not want_red and got:
            fails.append(f"{name}: must be GREEN, got {got}")

    case("open (no clamp, no clear)", _ARM_OPEN, _UC_DELEGATES, _HS_PLAIN, True)
    case("half-fix: clamp declared but not applied at arming site",
         "const val MAX_RATE_LIMIT_COOLDOWN_MS = 120_000L\n" + _ARM_OPEN + _CLEAR_DEF,
         _UC_DELEGATES, _HS_CALLS, True)
    case("half-fix: clear defined but HeadServer never calls it",
         _ARM_CLAMPED + _CLEAR_DEF, _UC_DELEGATES, _HS_PLAIN, True)
    case("half-fix: delegate exists but no longer reaches the AtomicLong",
         _ARM_CLAMPED + _CLEAR_DEF, _UC_DELEGATES_NOWHERE, _HS_CALLS, True)
    case("half-fix: clear() declared but does not zero the AtomicLong",
         _ARM_CLAMPED + "fun clear() { }\n", _UC_DELEGATES, _HS_CALLS, True)
    case("closed (clamp applied + clear zeroes + delegate + called)",
         _ARM_CLAMPED + _CLEAR_DEF, _UC_DELEGATES, _HS_CALLS, False)
    case("missing sources", None, None, None, True)
    case("arming site shape changed", "fun unrelated() {}\n", _UC_DELEGATES, _HS_CALLS, True)

    if fails:
        print("NF-01 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("NF-01 SELFTEST OK — red on open, red on FOUR half-fixes (declared-not-applied, "
          "defined-not-called, delegate-reaches-nothing, clear-that-clears-nothing), green only "
          "when clamp, clear, delegate and call are all live")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(COOLDOWN), _read(UPSTREAM), _read(HEADSERVER))
    if problems:
        print("NF-01 WALL RED — the 429 cooldown horizon is unbounded and/or unclearable:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("NF-01 WALL GREEN: cooldown horizon is clamped at the arming site in "
          "RateLimitCooldown.kt, and cleared on head restart through UpstreamClient's delegate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
