#!/usr/bin/env python3
"""WALL for JW-01 — a daemon that dies at boot must leave its stack trace somewhere tailable.

GAP (RED at authoring, 2026-08-07): both cold-start paths launch the JVM with output discarded
(>/dev/null 2>&1), and Main.runDaemon parses the topology BEFORE the log sink exists — broken
TOML, an unwritable state dir, bad SPLICE_JVM_OPTS, a stolen port all die invisibly; the operator
sees only "daemon failed version handshake (got <none>)".

GREEN requires ALL of:
  1. Main.kt installs a boot-failure net (bootFailureHandler) BEFORE TopologyLoader.loadOrMaterialize;
  2. bin/splice-launch redirects the spawned JVM to daemon-boot.log (with a /dev/null fallback
     for an unwritable logs dir) and prints the boot-log tail on handshake failure;
  3. AdminSupport.spawnDaemon does the same redirect, and ensureDaemon prints the tail when the
     daemon never comes up.

EXIT 0 = boot failures visible. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
MAIN = ROOT / "gateway/app/src/main/kotlin/splice/app/Main.kt"
SHIM = ROOT / "bin/splice-launch"
ADMIN = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/AdminSupport.kt"


def detect(main: str | None, shim: str | None, admin: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    for name, text in (("Main.kt", main), ("bin/splice-launch", shim), ("AdminSupport.kt", admin)):
        if text is None:
            return [f"{name} missing — refusing to pass vacuously"]
    problems: list[str] = []
    handler = (main or "").find("bootFailureHandler")
    topo = (main or "").find("TopologyLoader.loadOrMaterialize")
    if handler < 0:
        problems.append("Main.kt has no bootFailureHandler — a pre-logger boot throwable "
                        "(broken TOML, unwritable state dir) leaves no trace anywhere")
    elif 0 <= topo < handler and "Thread.setDefaultUncaughtExceptionHandler" not in (main or "")[:topo]:
        problems.append("the boot-failure net is installed AFTER the topology parse — exactly the "
                        "throw it exists to catch happens before it")
    if "daemon >/dev/null 2>&1" in (shim or "") and "daemon-boot.log" not in (shim or ""):
        problems.append("bin/splice-launch still discards the spawned JVM's output — a boot stack "
                        "trace dies in /dev/null")
    elif "daemon-boot.log" not in (shim or ""):
        problems.append("bin/splice-launch never mentions daemon-boot.log — no tailable boot lane")
    if "tail" not in (shim or ""):
        problems.append("splice-launch does not print the boot-log tail on handshake failure — "
                        "the operator still has to know to hand-run the jar")
    if "daemon-boot.log" not in (admin or ""):
        problems.append("AdminSupport.spawnDaemon still discards the spawned JVM's output")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


MAIN_OPEN = "val topology = TopologyLoader.loadOrMaterialize(topologyPath)\nval log = persistentLogger"
MAIN_OK = ("Thread.setDefaultUncaughtExceptionHandler(bootFailureHandler(statePaths))\n"
           "val topology = TopologyLoader.loadOrMaterialize(topologyPath)")
SHIM_OPEN = 'nohup java $SPLICE_JVM_OPTS -jar "$JAR" daemon >/dev/null 2>&1 &'
SHIM_OK = ('nohup java $SPLICE_JVM_OPTS -jar "$JAR" daemon >>"$BOOT_LOG" 2>&1 &\n'
           'tail -n 15 "$BOOT_LOG" >&2\ndaemon-boot.log')
ADMIN_OPEN = "daemon >/dev/null 2>&1 &"
ADMIN_OK = 'daemon >>\\"$B\\" 2>&1 & daemon-boot.log'


def selftest() -> int:
    fails = []
    if not detect(MAIN_OPEN, SHIM_OPEN, ADMIN_OPEN):
        fails.append("today's discard-everything shape must be RED")
    if detect(MAIN_OK, SHIM_OK, ADMIN_OK):
        fails.append(f"netted+redirected shape must be GREEN, got {detect(MAIN_OK, SHIM_OK, ADMIN_OK)}")
    if not detect(MAIN_OPEN, SHIM_OK, ADMIN_OK):
        fails.append("a missing Main.kt boot net must be RED")
    if not detect("val topology = TopologyLoader.loadOrMaterialize(p)\nfun bootFailureHandler() {}", SHIM_OK, ADMIN_OK):
        fails.append("a net installed AFTER the topology parse must be RED")
    if not detect(MAIN_OK, SHIM_OPEN, ADMIN_OK):
        fails.append("a still-discarding shim must be RED")
    if not detect(MAIN_OK, SHIM_OK, ADMIN_OPEN):
        fails.append("a still-discarding AdminSupport must be RED")
    if not detect(None, SHIM_OK, ADMIN_OK):
        fails.append("missing files must be RED, never a vacuous pass")
    if fails:
        print("JW-01 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("JW-01 SELFTEST OK — red on discard-everything, late net, discarding shim/AdminSupport, "
          "and missing files; green only when every boot lane is tailable")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(MAIN), _read(SHIM), _read(ADMIN))
    if problems:
        print("JW-01 WALL RED — a boot-dead daemon leaves no trace:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("JW-01 WALL GREEN: boot failures land in daemon.log/daemon-boot.log and the shim shows the tail.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
