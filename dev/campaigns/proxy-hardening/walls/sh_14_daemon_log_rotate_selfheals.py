#!/usr/bin/env python3
"""WALL for SH-14 — a failed daemon.log rotate must self-correct, never wedge the logger forever.

GAP (RED at authoring, 2026-08-07): persistentLogger tracks `written` in memory; when the rotate
Files.move throws (external logrotate removed the file, read-only dir, permissions), onFailure
resets only `writer` and leaves `written` >= MAX_LOG_BYTES — every later line re-enters the
rotate branch, throws BEFORE reaching newBufferedWriter, and daemon.log goes silent permanently
with no error surfaced anywhere.

GREEN requires BOTH:
  1. the onFailure branch RECONCILES `written` from the file's real size (absent = 0), restoring
     forward progress on the very next line;
  2. the failure is announced on stderr (a wedged logger must not be silent about being wedged).

EXIT 0 = self-healing. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
# 2026-08-23: persistentLogger moved to DaemonBoundary.kt. Main.kt is a one-line delegate.
MAIN = ROOT / "gateway/app/src/main/kotlin/splice/app/DaemonBoundary.kt"


def detect(text: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    if text is None:
        return ["DaemonBoundary.kt missing — refusing to pass vacuously"]
    if "persistentLogger" not in text:
        return ["persistentLogger not found (shape changed?) — refusing to pass vacuously"]
    m = re.search(r"\.onFailure \{.*?\n\s+\}", text, re.S)
    if m is None:
        return ["persistentLogger's onFailure branch not found (shape changed?) — refusing to "
                "pass vacuously"]
    branch = m.group(0)
    problems: list[str] = []
    if "written =" not in branch:
        problems.append("onFailure never reconciles `written` — after one failed rotate every "
                        "later line re-enters the throwing rotate branch and daemon.log is "
                        "silent for the daemon's lifetime")
    if "System.err" not in branch:
        problems.append("the rotate/write failure is not announced — a wedged logger is silent "
                        "about being wedged")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring. Without this the wall is satisfiable by a COMMENT: delete the
    reconcile and the announce from the onFailure branch, leave
    `// SH-14: restore written = ... / System.err.print("[daemon-log] ...")` in their place, and
    both required tokens still match INSIDE the matched branch while the logger wedges on the first
    failed rotate again. Proven against this file's own source before the stripper landed. Same
    stripper cx_01/cx_02/cx_09/cx_18/jw_08 carry.

    Both assertions here are REQUIRED tokens — this wall carries no banned string — so stripping is
    the strict direction throughout: it can only make a requirement harder to satisfy, never hide a
    violation (the split jw_08 has to make between its two readers does not arise). Line comments
    strip to empty lines, so the branch's indentation — which the onFailure regex anchors on —
    survives the strip unchanged."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read(p: pathlib.Path) -> str | None:
    return code_only(p.read_text(encoding="utf-8")) if p.exists() else None


OPEN_FIX = """persistentLogger
            }.onFailure {
                runCatchingCancellable { writer?.close() }
                writer = null
            }"""
CLOSED_FIX = """persistentLogger
            }.onFailure { failure ->
                runCatchingCancellable { writer?.close() }
                writer = null
                written = reconcile()
                System.err.print("rotate failed")
            }"""


def selftest() -> int:
    fails = []
    if not detect(OPEN_FIX):
        fails.append("writer-only reset must be RED")
    if detect(CLOSED_FIX):
        fails.append(f"reconcile + announce must be GREEN, got {detect(CLOSED_FIX)}")
    if not detect(CLOSED_FIX.replace("                written = reconcile()\n", "")):
        fails.append("announce without reconcile must be RED")
    if not detect(CLOSED_FIX.replace('                System.err.print("rotate failed")\n', "")):
        fails.append("reconcile without announce must be RED")
    if not detect(None):
        fails.append("missing DaemonBoundary.kt must be RED, never a vacuous pass")
    if not detect("fun main() {}"):
        fails.append("an unrecognized shape must be RED, never a vacuous pass")
    if fails:
        print("SH-14 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("SH-14 SELFTEST OK — red on writer-only reset, missing reconcile, missing announce, "
          "missing file, and shape change; green only on the self-healing, announcing rotate")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(MAIN))
    if problems:
        print("SH-14 WALL RED — a failed daemon.log rotate wedges the logger permanently:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("SH-14 WALL GREEN: a failed rotate reconciles the size from disk and announces itself.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
