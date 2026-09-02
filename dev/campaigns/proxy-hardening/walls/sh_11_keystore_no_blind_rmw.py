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
def mutation_transaction(text: str, name: str) -> str | None:
    """The unique withStoreLock lambda reached directly by this public mutation."""
    if len(re.findall(r"\bfun\s+" + re.escape(name) + r"\s*\(", text)) != 1:
        return None
    match = re.search(
        r"\bfun\s+" + re.escape(name) +
        r"\s*\([^)]*\)(?:(?!\bfun\s).)*?\bwithStoreLock\s*\{(?P<body>.*?)\}",
        text,
        re.S,
    )
    return None if match is None else match.group("body")


def strict_persist(transaction: str | None) -> bool:
    if transaction is None or "entries()" in transaction:
        return False
    strict = re.search(
        r"\bval\s+(\w+)\s*=\s*entriesStrict\(\)\.toMutableMap\(\)", transaction
    )
    return bool(
        strict
        and len(re.findall(r"\bpersist\s*\(", transaction)) == 1
        and re.search(r"\bpersist\s*\(\s*" + re.escape(strict.group(1)) + r"\s*\)", transaction)
    )


def detect(text: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if text is None:
        return ["KeyStore.kt missing — refusing to pass vacuously"]
    mutations = {name: mutation_transaction(text, name) for name in ("write", "unset")}

    problems: list[str] = []
    for name, transaction in mutations.items():
        if not strict_persist(transaction):
            problems.append(f"{name}() does not derive its persisted map directly from "
                            "entriesStrict().toMutableMap() inside withStoreLock — discarded strict "
                            "reads, tolerant maps, or disconnected locks earn nothing")
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


OPEN_FIX = ("fun write() { val next = entries().toMutableMap(); persist(next) }\n"
            "fun unset() { val next = entries().toMutableMap(); persist(next) }\n"
            ".getOrDefault(emptyMap())")
_STRICT_SUPPORT = ('fun entriesStrict() = error("keys.toml unreadable — refusing to write")\n'
                   "fun acquireBounded() { channel.tryLock() }\n")
_STRICT_WRITE = "fun write() { withStoreLock { val next = entriesStrict().toMutableMap(); persist(next) } }\n"
_STRICT_UNSET = "fun unset() { withStoreLock { val next = entriesStrict().toMutableMap(); persist(next) } }\n"
_TOLERANT_WRITE = "fun write() { withStoreLock { val next = entries().toMutableMap(); persist(next) } }\n"
_TOLERANT_UNSET = "fun unset() { withStoreLock { val next = entries().toMutableMap(); persist(next) } }\n"
CLOSED_FIX = _STRICT_WRITE + _STRICT_UNSET + _STRICT_SUPPORT
WRITE_ONLY_STRICT = _STRICT_WRITE + _TOLERANT_UNSET + _STRICT_SUPPORT
UNSET_ONLY_STRICT = _TOLERANT_WRITE + _STRICT_UNSET + _STRICT_SUPPORT
DISCARDED_STRICT = (
    "fun write() { withStoreLock { entriesStrict(); val next = entries().toMutableMap(); persist(next) } }\n"
    "fun unset() { withStoreLock { entriesStrict(); val next = entries().toMutableMap(); persist(next) } }\n"
    + _STRICT_SUPPORT
)
UNLOCKED_STRICT = (
    "fun write() { val next = entriesStrict().toMutableMap(); persist(next) }\n"
    "fun unset() { val next = entriesStrict().toMutableMap(); persist(next) }\n"
    + _STRICT_SUPPORT
)
DECOY_MUTATIONS = (
    "class Decoy { " + _STRICT_WRITE + _STRICT_UNSET + "}\n"
    + _TOLERANT_WRITE + _TOLERANT_UNSET + _STRICT_SUPPORT
)


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
    if not detect(DISCARDED_STRICT):
        fails.append("discarded strict reads followed by tolerant persisted maps must be RED")
    if not detect(UNLOCKED_STRICT):
        fails.append("strict read-modify-write outside withStoreLock must be RED")
    if not detect(DECOY_MUTATIONS):
        fails.append("same-name decoy mutations must not mask unsafe write()/unset()")
    if not detect(None):
        fails.append("missing KeyStore.kt must be RED, never a vacuous pass")
    if not detect("class KeyStore"):
        fails.append("an unrecognized shape must be RED, never a vacuous pass")
    if fails:
        print("SH-11 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("SH-11 SELFTEST OK — red on blind/discarded strict reads, either unsafe mutation, "
          "disconnected locks, same-name decoys, missing file, and shape change; green only when "
          "both persisted maps derive from strict reads inside withStoreLock")
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
