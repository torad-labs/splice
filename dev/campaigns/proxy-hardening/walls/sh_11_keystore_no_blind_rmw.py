#!/usr/bin/env python3
"""WALL for SH-11 — KeyStore mutations must never rebuild the file from a failed read.

GAP (RED at authoring, 2026-08-07): write()/unset() are read-modify-write over entries(), and
entries() collapses ANY read failure to an empty map (getOrDefault(emptyMap())). A transient
EINTR/permissions blip makes write() persist a file containing ONLY the key being written —
silently deleting every other stored API key. No lock exists, so two concurrent `splice key set`
invocations lose one write even on healthy reads.

GREEN requires ALL of:
  1. write() AND unset() each read STRICTLY (absent file = legitimately empty and safe; unreadable
     file ABORTS with "refusing to write" — existing keys preserved); tolerant display reads do not
     earn either mutation site's strict-read leg;
  2. mutations run under a cross-process file lock (the G1 lesson, applied to keys.toml);
  3. the tolerant getOrDefault(emptyMap()) no longer feeds persist().

EXIT 0 = mutations safe. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
STORE = ROOT / "gateway/core/src/main/kotlin/splice/core/config/KeyStore.kt"
_NEXT_FUNCTION_RE = re.compile(
    r"\n\s*(?:(?:public|private|internal|protected|override|suspend|inline)\s+)*fun\s+\w+\s*\("
)


def function_source(text: str, name: str) -> str | None:
    """One member function, through (but not including) the next function declaration."""
    start = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", text)
    if not start:
        return None
    following = _NEXT_FUNCTION_RE.search(text, start.end())
    end = len(text) if following is None else following.start()
    return text[start.start():end]


def detect(text: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if text is None:
        return ["KeyStore.kt missing — refusing to pass vacuously"]
    mutations = {name: function_source(text, name) for name in ("write", "unset")}
    if any(source is None for source in mutations.values()):
        return ["KeyStore mutation surface not found (shape changed?) — refusing to pass vacuously"]

    problems: list[str] = []
    for name, source in mutations.items():
        if "entriesStrict(" not in (source or ""):
            problems.append(f"{name}() does not perform its own strict read-modify-write — a strict "
                            "helper elsewhere cannot stop this mutation from rebuilding from a "
                            "tolerant empty-map fallback")
    if "refusing to write" not in text:
        problems.append("an unreadable store does not abort loudly — the operator learns about "
                        "key loss from the next 401, not from the failed command")
    if "tryLock" not in text and "FileLock" not in text:
        problems.append("no cross-process lock on mutations — two concurrent `splice key set` "
                        "invocations lose one write (the G1 lesson, unapplied to keys.toml)")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring. Without this the wall is satisfiable by a COMMENT: put the locked
    mutation body back to `entries().toMutableMap()`, leave
    `// TODO: restore entriesStrict() ... error("... refusing to write") ... channel.tryLock()`
    behind, and all four required tokens still match while the blind read-modify-write is live
    again. Proven against this file's own source before the stripper landed. Same stripper
    cx_01/cx_02/cx_09/cx_18/jw_08 carry.

    Every assertion here is a REQUIRED token — this wall carries no banned string — so stripping is
    the strict direction throughout: it can only make a requirement harder to satisfy, never hide a
    violation (the split jw_08 has to make between its two readers does not arise)."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read(p: pathlib.Path) -> str | None:
    return code_only(p.read_text(encoding="utf-8")) if p.exists() else None


OPEN_FIX = ("fun write() { entries().toMutableMap() }\n"
            "fun unset() { entries().toMutableMap() }\n"
            ".getOrDefault(emptyMap())")
_STRICT_SUPPORT = ('fun entriesStrict() = error("keys.toml unreadable — refusing to write")\n'
                   "fun acquireBounded() { channel.tryLock() }\n")
CLOSED_FIX = ("fun write() { entriesStrict().toMutableMap() }\n"
              "fun unset() { entriesStrict().toMutableMap() }\n" + _STRICT_SUPPORT)
WRITE_ONLY_STRICT = ("fun write() { entriesStrict().toMutableMap() }\n"
                     "fun unset() { entries().toMutableMap() }\n" + _STRICT_SUPPORT)
UNSET_ONLY_STRICT = ("fun write() { entries().toMutableMap() }\n"
                     "fun unset() { entriesStrict().toMutableMap() }\n" + _STRICT_SUPPORT)


def selftest() -> int:
    fails = []
    if not detect(OPEN_FIX):
        fails.append("blind RMW with tolerant read must be RED")
    if detect(CLOSED_FIX):
        fails.append(f"strict read + abort + lock must be GREEN, got {detect(CLOSED_FIX)}")
    if not detect(CLOSED_FIX.replace("channel.tryLock()", "")):
        fails.append("strict read without the lock must be RED")
    if not detect(CLOSED_FIX.replace("entriesStrict()", "entries()")):
        fails.append("a lock without the strict read must be RED")
    if not detect(WRITE_ONLY_STRICT):
        fails.append("write() strict while unset() still reads tolerantly must be RED")
    if not detect(UNSET_ONLY_STRICT):
        fails.append("unset() strict while write() still reads tolerantly must be RED")
    if not detect(None):
        fails.append("missing KeyStore.kt must be RED, never a vacuous pass")
    if not detect("class KeyStore"):
        fails.append("an unrecognized shape must be RED, never a vacuous pass")
    if fails:
        print("SH-11 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("SH-11 SELFTEST OK — red on blind RMW, either mutation site lacking its strict read, "
          "missing lock, missing file, and shape change; green only on strict + loud + locked "
          "mutations")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(STORE))
    if problems:
        print("SH-11 WALL RED — KeyStore mutations can destroy sibling keys:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-11 WALL GREEN: mutations read strictly, abort loudly on unreadable state, and hold the file lock.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
