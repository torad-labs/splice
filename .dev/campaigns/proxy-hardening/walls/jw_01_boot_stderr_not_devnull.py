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
  3. DaemonLaunch.spawnDaemon does the same redirect, and ensureDaemon prints the tail when the
     daemon never comes up. The cold-start cluster left AdminSupport.kt (concentration HIGH,
     2026-08-19); this wall follows the body, not the facade.

EXIT 0 = boot failures visible. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
MAIN = ROOT / "gateway/app/src/main/kotlin/splice/app/Main.kt"
SHIM = ROOT / "bin/splice-launch"
ADMIN = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/DaemonLaunch.kt"


def detect(main: str | None, shim: str | None, admin: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    for name, text in (("Main.kt", main), ("bin/splice-launch", shim), ("DaemonLaunch.kt", admin)):
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
        problems.append("DaemonLaunch.spawnDaemon still discards the spawned JVM's output")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)
# bin/splice-launch is SHELL, not Kotlin: its comment marker is `#`. Running the Kotlin stripper
# over it would miss every `# TODO:` (leaving the hole open on the very reader that carries two of
# this wall's four required tokens) AND eat the `//` in `http://127.0.0.1`. Same law, own marker.
_SHELL_COMMENT = re.compile(r"(?:(?<=\s)|^)#.*?$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
    a REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.

    Applied to EVERY reader here because every check in this wall is a REQUIRED token — the
    `daemon >/dev/null 2>&1` test only picks which message to print (both of its branches demand
    daemon-boot.log), so nothing in this wall is a BAN, where stripping would instead let a
    violation hide inside a comment (the jw_08 split)."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def shell_code_only(text: str | None) -> str | None:
    """code_only for the shim — the same law spoken in the shell's comment marker."""
    if text is None:
        return None
    return _SHELL_COMMENT.sub("", text)


def _read(p: pathlib.Path) -> str | None:
    return code_only(p.read_text(encoding="utf-8")) if p.exists() else None


def _read_shell(p: pathlib.Path) -> str | None:
    return shell_code_only(p.read_text(encoding="utf-8")) if p.exists() else None


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
        fails.append("a still-discarding DaemonLaunch must be RED")
    if not detect(None, SHIM_OK, ADMIN_OK):
        fails.append("missing files must be RED, never a vacuous pass")
    if fails:
        print("JW-01 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("JW-01 SELFTEST OK — red on discard-everything, late net, discarding shim/DaemonLaunch, "
          "and missing files; green only when every boot lane is tailable")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(MAIN), _read_shell(SHIM), _read(ADMIN))
    if problems:
        print("JW-01 WALL RED — a boot-dead daemon leaves no trace:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("JW-01 WALL GREEN: boot failures land in daemon.log/daemon-boot.log and the shim shows the tail.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
