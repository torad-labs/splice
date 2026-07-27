#!/usr/bin/env python3
"""WALL for NF-01 — the per-head 429 cooldown horizon must be bounded AND clearable.

GAP (RED at authoring, 2026-07-26): UpstreamClient.kt:407-408 arms a head-wide fail-fast horizon
straight from provider pushback with no ceiling —
    val until = clock() + (failed.retryAfterMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS)
    rateLimitedUntilMs.accumulateAndGet(until) { current, candidate -> maxOf(current, candidate) }
`accumulateAndGet(max)` means the LONGEST value ever seen wins permanently, so one malformed or
multi-day `Retry-After` poisons the head for every concurrent and future turn. HeadServer.restart()
rebuilds the Netty engine and calls driver.resetHealth() only — it cannot clear this AtomicLong.

Two independent conditions (a clamp with no escape hatch is half the fix):
  1. a MAX_RATE_LIMIT_COOLDOWN_MS clamp exists AND is APPLIED where the horizon is armed
  2. clearRateLimitCooldown() exists AND HeadServer actually calls it

EXIT 0 = both closed.  EXIT 1 = either open.
--selftest = the POSITIVE CONTROL (gate check C6): proves this wall separates open from closed,
             including the declared-but-unapplied and exists-but-uncalled half-fixes.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
UPSTREAM = ROOT / "gateway/provider-spi/src/main/kotlin/splice/spi/UpstreamClient.kt"
HEADSERVER = ROOT / "gateway/gateway/src/main/kotlin/splice/gateway/head/HeadServer.kt"

ARM_RE = re.compile(r"rateLimitedUntilMs\.accumulateAndGet\(.*?\n", re.S)
CLEAR_DEF_RE = re.compile(r"fun\s+clearRateLimitCooldown\s*\(")
CLAMP = "MAX_RATE_LIMIT_COOLDOWN_MS"


def detect(upstream: str | None, headserver: str | None) -> list[str]:
    """Pure detection — no I/O, so the selftest can feed synthetic sources."""
    if upstream is None or headserver is None:
        return ["UpstreamClient.kt or HeadServer.kt missing — refusing to pass vacuously"]
    problems: list[str] = []

    arm = ARM_RE.search(upstream)
    if not arm:
        return ["the cooldown arming site (rateLimitedUntilMs.accumulateAndGet) was not found — "
                "shape changed; refusing to pass vacuously"]

    # The clamp must appear in the EXPRESSION that computes the horizon, not merely somewhere in
    # the preceding bytes. An earlier version scanned a 400-char lookbehind window, which counted a
    # `const val MAX_… = …` declaration sitting just above as "applied" — a false pass its own
    # positive control caught (2026-07-26). Bind to the assignment and the arming call instead.
    arm_expr = "\n".join(
        ln for ln in upstream.splitlines()
        if ("val until" in ln and "clock()" in ln) or "rateLimitedUntilMs.accumulateAndGet" in ln
    )

    if CLAMP not in upstream:
        problems.append(f"no {CLAMP} clamp constant in UpstreamClient.kt "
                        "(only DEFAULT_RATE_LIMIT_COOLDOWN_MS exists)")
    elif CLAMP not in arm_expr:
        problems.append(f"{CLAMP} is declared but NOT applied in the horizon expression "
                        "(`val until = clock() + …` / accumulateAndGet) — a declared-but-unused clamp "
                        "bounds nothing")

    if not CLEAR_DEF_RE.search(upstream):
        problems.append("no clearRateLimitCooldown() on UpstreamClient — restart cannot clear an armed horizon")
    elif "clearRateLimitCooldown(" not in headserver:
        problems.append("clearRateLimitCooldown() exists but HeadServer never calls it — "
                        "restart is still not an escape hatch")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


_ARM_OPEN = ("val until = clock() + (failed.retryAfterMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS)\n"
             "rateLimitedUntilMs.accumulateAndGet(until) { c, cand -> maxOf(c, cand) }\n")
_ARM_CLAMPED = ("val until = clock() + minOf(failed.retryAfterMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS, "
                "MAX_RATE_LIMIT_COOLDOWN_MS)\n"
                "rateLimitedUntilMs.accumulateAndGet(until) { c, cand -> maxOf(c, cand) }\n")
_CLEAR_DEF = "internal fun clearRateLimitCooldown() { rateLimitedUntilMs.set(0L) }\n"
_HS_CALLS = "driver.resetHealth(); upstream.clearRateLimitCooldown()\n"
_HS_PLAIN = "driver.resetHealth()\n"


def selftest() -> int:
    fails = []

    def case(name, up, hs, want_red):
        got = detect(up, hs)
        if want_red and not got:
            fails.append(f"{name}: must be RED")
        if not want_red and got:
            fails.append(f"{name}: must be GREEN, got {got}")

    case("open (no clamp, no clear)", _ARM_OPEN, _HS_PLAIN, True)
    case("half-fix: clamp declared but not applied at arming site",
         "const val MAX_RATE_LIMIT_COOLDOWN_MS = 120_000L\n" + _ARM_OPEN + _CLEAR_DEF, _HS_CALLS, True)
    case("half-fix: clear defined but HeadServer never calls it",
         _ARM_CLAMPED + _CLEAR_DEF, _HS_PLAIN, True)
    case("closed (clamp applied + clear called)", _ARM_CLAMPED + _CLEAR_DEF, _HS_CALLS, False)
    case("missing sources", None, None, True)
    case("arming site shape changed", "fun unrelated() {}\n", _HS_CALLS, True)

    if fails:
        print("NF-01 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("NF-01 SELFTEST OK — red on open, red on BOTH half-fixes (declared-not-applied, "
          "defined-not-called), green only when clamp and clear are both live")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(UPSTREAM), _read(HEADSERVER))
    if problems:
        print("NF-01 WALL RED — the 429 cooldown horizon is unbounded and/or unclearable:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("NF-01 WALL GREEN: cooldown horizon is clamped at the arming site and cleared on head restart.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
