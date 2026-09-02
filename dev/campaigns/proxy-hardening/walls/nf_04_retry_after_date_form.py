#!/usr/bin/env python3
"""WALL for NF-04 — Retry-After's HTTP-date form must be honoured, not silently discarded.

GAP (RED at authoring, 2026-08-07): retryAfterMs parsed only integer seconds; RFC 7231's
HTTP-date form returned null, so the server's pushback was not a backoff floor, the absurd-pushback
give-up could not fire, and the 429 cooldown fell back to the 20s guess. Cloudflare and gateway
fronts emit the date form.

RE-ANCHORED 2026-08-18 (HD-25): the parse moved out of UpstreamClient.kt into its own file,
splice/spi/RetryAfter.kt, and split into `secondsFormMs` + `httpDateMs` behind an elvis chain. The
wall follows it and gains a leg it should always have had: it asserted "numeric-first ordering is
the pinned behavior" in prose while only checking that `toLongOrNull` existed ANYWHERE in the file.
Under a two-parser split that is no longer enough — a chain that tries the date form first would
have passed the old check. The ordering is now checked. Not broadened: still one exact file, still
exact tokens, no module-wide or substring matching.

GREEN requires, in RetryAfter.kt: a `retryAfterMs` entry point that reaches both a strict seconds
parse (`toLongOrNull`) and an RFC_1123_DATE_TIME parser; and the seconds call textually AHEAD of the
date call, which is the numeric-first spec. A dead date helper elsewhere in the file earns nothing.

EXIT 0 = date form honoured, seconds first. EXIT 1 = gap open.
--selftest = the POSITIVE CONTROL (gate check C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
CLIENT = ROOT / "gateway/provider-spi/src/main/kotlin/splice/spi/RetryAfter.kt"
_NEXT_FUNCTION_RE = re.compile(
    r"^[ \t]*(?:(?:public|private|internal|protected|override|suspend|inline)[ \t]+)*"
    r"fun[ \t]+\w+[ \t]*\(",
    re.M,
)
_RETURN_CHAIN_RE = re.compile(
    r"\breturn[ \t\n]+secondsFormMs\s*\([^)]*\)\s*\?:\s*httpDateMs\s*\([^)]*\)"
)
_SECONDS_HELPER_RE = re.compile(
    r"\bfun\s+secondsFormMs\s*\([^)]*\)[^=]*=\s*\w+\.toLongOrNull\s*\("
)
_DATE_HELPER_RE = re.compile(
    r"\bfun\s+httpDateMs\s*\([^)]*\)[^=]*=\s*try\s*\{.*RFC_1123_DATE_TIME", re.S
)


def function_sources(text: str, name: str) -> list[str]:
    """Every function with this name, each bounded by the next function declaration."""
    starts = list(re.finditer(r"\bfun[ \t]+" + re.escape(name) + r"[ \t]*\(", text))
    sources: list[str] = []
    for start in starts:
        following = _NEXT_FUNCTION_RE.search(text, start.end())
        end = len(text) if following is None else following.start()
        sources.append(text[start.start():end])
    return sources


def detect(client_text: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if client_text is None:
        return ["RetryAfter.kt missing — refusing to pass vacuously"]
    retries = function_sources(client_text, "retryAfterMs")
    if len(retries) != 1:
        return ["RetryAfter.kt must contain exactly one retryAfterMs entry point — refusing to "
                "credit a missing or decoy declaration"]

    problems: list[str] = []
    if not _RETURN_CHAIN_RE.search(retries[0]):
        problems.append("retryAfterMs must return the direct secondsFormMs(...) ?: "
                        "httpDateMs(...) chain — calls that are discarded, deferred, or date-first "
                        "do not implement numeric-first fallback")

    seconds = function_sources(client_text, "secondsFormMs")
    if len(seconds) != 1 or not _SECONDS_HELPER_RE.search(seconds[0]):
        problems.append("RetryAfter.kt must contain exactly one strict secondsFormMs helper whose "
                        "returned expression starts at toLongOrNull — same-name decoys earn nothing")
    dates = function_sources(client_text, "httpDateMs")
    if len(dates) != 1 or not _DATE_HELPER_RE.search(dates[0]):
        problems.append("RetryAfter.kt must contain exactly one httpDateMs try-parser backed by "
                        "RFC_1123_DATE_TIME — dead helpers and same-name decoys earn nothing")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
    this wall after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 already carry.

    The one reader is stripped: every leg is a REQUIRED token and this wall carries no banned
    string, which is the only direction that would have to stay raw. The ORDERING leg needs it
    twice over — RetryAfter.kt's KDoc discusses both parsers in prose, so a comment could reorder
    the two `.index()` reads without a line of code moving."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read(p: pathlib.Path) -> str | None:
    return code_only(p.read_text(encoding="utf-8")) if p.exists() else None


OPEN_FIX = "fun retryAfterMs(header: String?): Long? =\n    header?.trim()?.toLongOrNull()"
_HELPERS = ("fun secondsFormMs(value: String): Long? = value.toLongOrNull()\n"
            "fun httpDateMs(value: String): Long? = try { "
            "parse(value, DateTimeFormatter.RFC_1123_DATE_TIME) } catch (ignored: Exception) { null }")
CLOSED_FIX = ("fun retryAfterMs(header: String): Long? {\n"
              "    return secondsFormMs(header) ?: httpDateMs(header)\n"
              "}\n" + _HELPERS)
BROKEN_FIX = "fun retryAfterMs(h: String?): Long? = DateTimeFormatter.RFC_1123_DATE_TIME_only"
INVERTED_FIX = ("fun retryAfterMs(header: String): Long? {\n"
                "    return httpDateMs(header) ?: secondsFormMs(header)\n"
                "}\n" + _HELPERS)
UNWIRED_DATE_FIX = ("fun retryAfterMs(header: String): Long? { return secondsFormMs(header) }\n" +
                    _HELPERS)
DISCARDED_SECONDS_FIX = ("fun retryAfterMs(header: String): Long? {\n"
                         "    secondsFormMs(header)\n"
                         "    return httpDateMs(header)\n"
                         "}\n" + _HELPERS)
DECOY_HELPERS_FIX = (
    "class Decoy {\n"
    "    fun secondsFormMs(value: String): Long? = value.toLongOrNull()\n"
    "    fun httpDateMs(value: String): Long? = parse(value, RFC_1123_DATE_TIME)\n"
    "}\n"
    "fun retryAfterMs(header: String): Long? {\n"
    "    return secondsFormMs(header) ?: httpDateMs(header)\n"
    "}\n"
    "fun secondsFormMs(value: String): Long? = null\n"
    "fun httpDateMs(value: String): Long? = null"
)


def selftest() -> int:
    fails = []
    if not detect(OPEN_FIX):
        fails.append("seconds-only parser must be RED")
    if detect(CLOSED_FIX):
        fails.append(f"seconds-first + date-fallback must be GREEN, got {detect(CLOSED_FIX)}")
    if not detect(BROKEN_FIX):
        fails.append("a date-only parser that dropped the strict seconds path must be RED")
    if not detect(INVERTED_FIX):
        fails.append("a chain that tries the HTTP-date form FIRST must be RED — that is the "
                     "ordering this wall pins")
    if not detect(UNWIRED_DATE_FIX):
        fails.append("an HTTP-date helper that retryAfterMs never calls must be RED")
    if not detect(DISCARDED_SECONDS_FIX):
        fails.append("a discarded seconds result followed by an unconditional date return must be RED")
    if not detect(DECOY_HELPERS_FIX):
        fails.append("same-name decoy helpers must not mask the called broken helpers")
    if not detect(None):
        fails.append("missing RetryAfter.kt must be RED, never a vacuous pass")
    if not detect("class RetryAfterHeader"):
        fails.append("a tree without retryAfterMs (shape change) must be RED, refusing vacuous pass")
    if fails:
        print("NF-04 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("NF-04 SELFTEST OK — red on seconds/date-only, date-first, unwired/discarded parser "
          "results, same-name decoys, missing file, and shape change; green only on the direct "
          "seconds-first return chain")
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
    print("NF-04 WALL GREEN: RetryAfter.kt honours both RFC 7231 forms, seconds-first.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
