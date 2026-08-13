#!/usr/bin/env python3
"""WALL for NF-04 — Retry-After's HTTP-date form must be honoured, not silently discarded.

GAP (RED at authoring, 2026-08-07): retryAfterMs parses only integer seconds; RFC 7231's
HTTP-date form returns null, so the server's pushback is not a backoff floor, the absurd-pushback
give-up cannot fire, and the 429 cooldown falls back to the 20s guess. Cloudflare and gateway
fronts emit the date form.

GREEN requires: retryAfterMs (UpstreamClient.kt) parses the date form via RFC_1123_DATE_TIME as a
fallback AFTER strict seconds, clamping past dates to 0 and keeping garbage → null. The seconds
path must remain first (numeric-first ordering pins the existing behavior).

EXIT 0 = date form honoured. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (gate check C6).
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
CLIENT = ROOT / "gateway/provider-spi/src/main/kotlin/splice/spi/UpstreamClient.kt"


def detect(client_text: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if client_text is None:
        return ["UpstreamClient.kt missing — refusing to pass vacuously"]
    if "fun retryAfterMs(" not in client_text:
        return ["retryAfterMs not found (shape changed?) — refusing to pass vacuously"]
    problems: list[str] = []
    if "RFC_1123_DATE_TIME" not in client_text:
        problems.append("retryAfterMs has no HTTP-date branch (RFC_1123_DATE_TIME) — a date-form "
                        "Retry-After is silently discarded: no backoff floor, no absurd-pushback "
                        "give-up, cooldown falls back to the 20s guess")
    elif "toLongOrNull" not in client_text:
        problems.append("the strict seconds-form parse (toLongOrNull) disappeared — numeric-first "
                        "ordering is the pinned behavior; the date branch may only be a FALLBACK")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


OPEN_FIX = "private fun retryAfterMs(header: String?): Long? =\n    header?.trim()?.toLongOrNull()"
CLOSED_FIX = OPEN_FIX + "\n    // fallback\n    DateTimeFormatter.RFC_1123_DATE_TIME"
BROKEN_FIX = "private fun retryAfterMs(h: String?): Long? = DateTimeFormatter.RFC_1123_DATE_TIME_only"


def selftest() -> int:
    fails = []
    if not detect(OPEN_FIX):
        fails.append("seconds-only parser must be RED")
    if detect(CLOSED_FIX):
        fails.append(f"seconds-first + date-fallback must be GREEN, got {detect(CLOSED_FIX)}")
    if not detect(BROKEN_FIX):
        fails.append("a date-only parser that dropped the strict seconds path must be RED")
    if not detect(None):
        fails.append("missing UpstreamClient.kt must be RED, never a vacuous pass")
    if not detect("class UpstreamClient"):
        fails.append("a tree without retryAfterMs (shape change) must be RED, refusing vacuous pass")
    if fails:
        print("NF-04 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("NF-04 SELFTEST OK — red on seconds-only, date-only, missing file, and shape change; "
          "green only on seconds-first with a date fallback")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(CLIENT))
    if problems:
        print("NF-04 WALL RED — Retry-After HTTP-date form is discarded:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("NF-04 WALL GREEN: Retry-After honours both RFC 7231 forms, seconds-first.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
