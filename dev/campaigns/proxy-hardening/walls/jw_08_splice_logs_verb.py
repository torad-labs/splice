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
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
COMMAND = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/Command.kt"
# HD-25: the logsDir row this wall reads is inside daemonChecks, which moved out of DoctorCommand.kt
# into the daemon-section collaborator when that file was decomposed (it was the tree's worst
# concentration row at 8.10). Re-anchored onto the ONE file that now holds it, at the same
# single-file resolution. NOT widened to the cli package: unlike the CONTROL_DIR sweep below, this
# is a REQUIRED token, so a directory read would let any sibling satisfy it.
DOCTOR = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/DoctorDaemonChecks.kt"
# HD-24: the remediation strings this key polices left ControlServer.kt when the control plane
# split into splice.control + splice.control.api ("refresh failed — run: splice logs" now lives in
# api/AuthRoutes.kt), which left the single-file read policing a file that carries no remediation
# text at all. A file LIST cannot fix this key the way it fixed JW-06's: this is a NEGATIVE
# assertion, so the file that would carry the violation need not exist yet. Scoped to the
# NEIGHBOURHOOD instead — every .kt under the control plane, recursively — the same remedy CX-18
# uses for its ban, so a new route file is covered the moment it is written, with no wall edit.
CONTROL_DIR = ROOT / "gateway/control/src/main/kotlin/splice/control"


def detect(command: str | None, doctor: str | None, control: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    for name, text in (("Command.kt", command), ("DoctorDaemonChecks.kt", doctor),
                       ("control plane sources", control)):
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


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
    a REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.

    Applied to _read (which feeds the required tokens) and deliberately NOT to _read_tree, which
    feeds the BAN. The two directions want opposite treatment: stripping makes a required token
    harder to satisfy, but would make a banned string easier to hide. Both stay strict this way."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read(p: pathlib.Path) -> str | None:
    return code_only(p.read_text(encoding="utf-8")) if p.exists() else None


def _read_tree(d: pathlib.Path) -> str | None:
    """Concatenate every .kt under `d`, recursively. A missing or .kt-less tree reads as None
    (vacuity RED) rather than as an empty sweep that trivially satisfies a negative assertion.

    Raw text on purpose — see code_only: this feeds the ban, where a comment must still count."""
    if not d.is_dir():
        return None
    texts = [p.read_text(encoding="utf-8") for p in sorted(d.rglob("*.kt"))]
    return "\n".join(texts) if texts else None


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
    # HD-24 staleness control. Every case above stayed correct through the ControlServer split
    # while the READER went blind, so the reader is a positive control too: the swept tree must
    # actually reach the remediation strings this wall's negative assertion is written against.
    live = _read_tree(CONTROL_DIR)
    if live is None:
        fails.append(f"the control-plane sweep found no sources under {CONTROL_DIR} — the reader "
                     "is pointed at nothing and the negative assertion is vacuous")
    elif "splice logs" not in live:
        fails.append("the control-plane sweep no longer reaches any `splice logs` remediation "
                     "string — the negative assertion has nothing left to police")
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
    problems = detect(_read(COMMAND), _read(DOCTOR), _read_tree(CONTROL_DIR))
    if problems:
        print("JW-08 WALL RED — no `splice logs`, and the log path is unsignposted:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("JW-08 WALL GREEN: `splice logs` exists (daemon-independent) and doctor names the log path.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
