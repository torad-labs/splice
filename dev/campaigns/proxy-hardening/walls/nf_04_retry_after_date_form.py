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
    r"\n\s*(?:(?:public|private|internal|protected|override|suspend|inline)\s+)*fun\s+\w+\s*\("
)


def function_source(text: str, name: str) -> str | None:
    """One top-level/member function, through (but not including) the next function declaration."""
    start = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", text)
    if not start:
        return None
    following = _NEXT_FUNCTION_RE.search(text, start.end())
    end = len(text) if following is None else following.start()
    return text[start.start():end]


def detect(client_text: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if client_text is None:
        return ["RetryAfter.kt missing — refusing to pass vacuously"]
    retry = function_source(client_text, "retryAfterMs")
    if retry is None:
        return ["retryAfterMs not found (shape changed?) — refusing to pass vacuously"]

    problems: list[str] = []
    seconds_call = retry.find("secondsFormMs(")
    seconds_inline = retry.find("toLongOrNull")
    seconds_at = seconds_call if seconds_call >= 0 else seconds_inline
    seconds_helper = function_source(client_text, "secondsFormMs")
    if seconds_at < 0 or (seconds_call >= 0 and
                          (seconds_helper is None or "toLongOrNull" not in seconds_helper)):
        problems.append("retryAfterMs no longer reaches the strict seconds-form parse "
                        "(toLongOrNull) — numeric-first ordering is the pinned behavior")

    date_call = retry.find("httpDateMs(")
    date_inline = retry.find("RFC_1123_DATE_TIME")
    date_at = date_call if date_call >= 0 else date_inline
    date_helper = function_source(client_text, "httpDateMs")
    if date_at < 0 or (date_call >= 0 and
                       (date_helper is None or "RFC_1123_DATE_TIME" not in date_helper)):
        problems.append("retryAfterMs does not call an HTTP-date parser backed by "
                        "RFC_1123_DATE_TIME — a dead helper does not honour date-form pushback")
    elif seconds_at >= 0 and date_at < seconds_at:
        problems.append("the HTTP-date branch is attempted BEFORE the strict seconds parse — "
                        "numeric-first is the spec, and the date parser must only be a fallback")
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
            "fun httpDateMs(value: String): Long? = "
            "parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)")
CLOSED_FIX = ("fun retryAfterMs(header: String): Long? =\n"
              "    secondsFormMs(header) ?: httpDateMs(header)\n" + _HELPERS)
BROKEN_FIX = "fun retryAfterMs(h: String?): Long? = DateTimeFormatter.RFC_1123_DATE_TIME_only"
INVERTED_FIX = ("fun retryAfterMs(header: String): Long? =\n"
                "    httpDateMs(header) ?: secondsFormMs(header)\n" + _HELPERS)
UNWIRED_DATE_FIX = ("fun retryAfterMs(header: String): Long? = secondsFormMs(header)\n" +
                    _HELPERS)


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
    if not detect(None):
        fails.append("missing RetryAfter.kt must be RED, never a vacuous pass")
    if not detect("class RetryAfterHeader"):
        fails.append("a tree without retryAfterMs (shape change) must be RED, refusing vacuous pass")
    if fails:
        print("NF-04 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("NF-04 SELFTEST OK — red on seconds-only, date-only, date-FIRST, missing file, and shape "
          "change; green only on seconds-first with a date fallback")
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
