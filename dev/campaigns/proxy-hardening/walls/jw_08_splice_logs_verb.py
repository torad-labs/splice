#!/usr/bin/env python3
"""WALL for JW-08 — there must be a `splice logs` verb and doctor must name the log path.

GAP (RED at authoring, 2026-08-08): every remediation ends at "check daemon.log" (the control
plane says so literally), yet there is no CLI verb to reach it, and doctor prints the STATE dir
(~/.claude-codex/state) while daemon.log lives in the SIBLING logs dir — so the one path doctor
prints does not contain the logs. The only log surface (the dashboard panel) needs the daemon up,
a browser, and the mgmt-key: exactly what is broken when you need logs.

GREEN requires ALL of:
  1. a Logs command in the verb table (reachable, daemon-independent — reuses LogFileSource);
  2. doctor prints a row pointing at the LOGS dir daemon.log (not the state dir);
  3. the "check daemon.log" remediation strings name `splice logs`.

EXIT 0 = reachable + signposted. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
COMMAND = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/Command.kt"
DOCTOR = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/DoctorCommand.kt"
CONTROL = ROOT / "gateway/control/src/main/kotlin/splice/control/ControlServer.kt"


def detect(command: str | None, doctor: str | None, control: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    for name, text in (("Command.kt", command), ("DoctorCommand.kt", doctor), ("ControlServer.kt", control)):
        if text is None:
            return [f"{name} missing — refusing to pass vacuously"]
    problems: list[str] = []
    if '"logs"' not in (command or ""):
        problems.append("no `logs` verb in the parse table — every remediation ends at daemon.log "
                        "with no CLI path to it")
    if "logsDir" not in (doctor or ""):
        problems.append("doctor never names the logs dir — it prints the state dir, which does "
                        "NOT contain daemon.log")
    if "check daemon.log" in (control or ""):
        problems.append("a 'check daemon.log' remediation string still does not name `splice logs`")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


CMD_OK = '"logs" to { a -> Logs(a) }'
DOC_OK = 'DoctorCheck("logs", INFO, statePaths.logsDir...daemon.log)'
CTRL_OK = 'put("note", "refresh failed — run: splice logs")'


def selftest() -> int:
    fails = []
    if not detect("verb table no logs", "prints state dir only", "check daemon.log"):
        fails.append("today's no-verb shape must be RED")
    if detect(CMD_OK, DOC_OK, CTRL_OK):
        fails.append(f"verb + doctor row + fixed string must be GREEN, got {detect(CMD_OK, DOC_OK, CTRL_OK)}")
    if not detect("verb table no logs", DOC_OK, CTRL_OK):
        fails.append("a missing logs verb must be RED")
    if not detect(CMD_OK, "state dir only", CTRL_OK):
        fails.append("a doctor that never names logsDir must be RED")
    if not detect(CMD_OK, DOC_OK, "check daemon.log"):
        fails.append("a lingering 'check daemon.log' string must be RED")
    if not detect(None, DOC_OK, CTRL_OK):
        fails.append("missing files must be RED, never a vacuous pass")
    if fails:
        print("JW-08 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("JW-08 SELFTEST OK — red on missing verb, state-dir-only doctor, lingering daemon.log "
          "string, and missing files; green only when logs are reachable and signposted")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(COMMAND), _read(DOCTOR), _read(CONTROL))
    if problems:
        print("JW-08 WALL RED — no `splice logs`, and the log path is unsignposted:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("JW-08 WALL GREEN: `splice logs` exists (daemon-independent) and doctor names the log path.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
