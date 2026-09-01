#!/usr/bin/env python3
"""WALL for JW-13 — a duplicate head port must be a named pre-flight failure, not an opaque bind error.

GAP (RED at authoring, 2026-08-08): copy-pasting a [heads.X] block and forgetting to change `port`
surfaces only as "[daemon] head 'b' failed to start: Address already in use" on whichever head
lost the race — no pointer to the sibling holding the port, and (JW-01) only in daemon.log. The
analogous WRAPPER-COMMAND collision is validated precisely (install prints both owners); ports have
no such check.

GREEN requires ALL of:
  1. Topology.portCollisions() + portCollisionMessage() next to the head-resolution helpers;
  2. doctor's configurationChecks FAILs on a port collision, naming both heads;
  3. the daemon's assembleDaemonHeads names the sibling instead of the bare OS error.

EXIT 0 = named pre-flight. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
TOPO = ROOT / "gateway/core/src/main/kotlin/splice/core/topology/Topology.kt"
# HD-25: configurationChecks — the declaration this wall reads — moved out of DoctorCommand.kt into
# its own collaborator when that file was decomposed (it was the tree's worst concentration row at
# 8.10). Re-anchored onto the ONE file that now holds it, at the same single-file resolution, the
# same way DAEMON below was repointed at HeadBoot.kt.
DOCTOR = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/DoctorConfigChecks.kt"
# assembleDaemonHeads (and its portCollisionMessage pre-flight) moved out of Daemon.kt into its own
# collaborator in the 2026-08-17 decomposition (campaign claude-head, CH target Daemon) — repointed
# the same way the kt-state-paths-single-source ignore was, following the code rather than the
# god-file it used to live in.
DAEMON = ROOT / "gateway/app/src/main/kotlin/splice/app/head/HeadBoot.kt"


def detect(topo: str | None, doctor: str | None, daemon: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    for name, text in (("Topology.kt", topo), ("DoctorConfigChecks.kt", doctor), ("HeadBoot.kt", daemon)):
        if text is None:
            return [f"{name} missing — refusing to pass vacuously"]
    problems: list[str] = []
    if "fun portCollisions(" not in (topo or ""):
        problems.append("no Topology.portCollisions() — the wrapper-command collision is validated "
                        "but a duplicate port is not")
    if "portCollision" not in (doctor or ""):
        problems.append("doctor's config checks never flag a port collision — a duplicated port "
                        "still surfaces as an opaque per-head bind error")
    if "portCollision" not in (daemon or ""):
        problems.append("assembleDaemonHeads does not name the sibling — the daemon log stays "
                        "'Address already in use' with no pointer to the other head")
    return problems


_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//.*?$", re.M)
_IMPORT_LINE = re.compile(r"^import .*$", re.M)


def code_only(text: str | None) -> str | None:
    """A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
    this wall after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 already carry."""
    if text is None:
        return None
    stripped = _BLOCK_COMMENT.sub("", text)
    stripped = _LINE_COMMENT.sub("", stripped)
    return _IMPORT_LINE.sub("", stripped)


def _read(p: pathlib.Path) -> str | None:
    return code_only(p.read_text(encoding="utf-8")) if p.exists() else None


TOPO_OK = "fun portCollisions(): Map<Int, List<String>>\nfun portCollisionMessage("
DOC_OK = "portCollisions().map { FAIL naming both }"
DMN_OK = "portCollisionMessage pre-flight in assembleDaemonHeads"


def derived_mutants() -> list[str]:
    """DR-35b: the gate's polarity law sees vacuity only on TODO items — a DONE item's wall that
    can no longer fail is invisible (neutered-but-present rot). Derive mutants from the LIVE
    sources, cx_02's derived-selftest idiom: deleting each required token from today's tree must
    turn detect red, or that token has rotted into always-green furniture."""
    live = [_read(TOPO), _read(DOCTOR), _read(DAEMON)]
    if detect(*live):
        return ["derived mutants need the live tree green; the wall is RED right now"]
    fails = []
    for where, tokens in ((0, ["fun portCollisions("]), (1, ["portCollision"]), (2, ["portCollision"])):
        mutated = [x for x in live]
        for t in tokens:
            mutated[where] = (mutated[where] or "").replace(t, "")
        if not detect(*mutated):
            fails.append(f"live tree with {'/'.join(tokens)} deleted must be RED — furniture token")
    return fails


def selftest() -> int:
    fails = []
    if not detect("no helper", "no check", "bare error"):
        fails.append("today's no-check shape must be RED")
    if detect(TOPO_OK, DOC_OK, DMN_OK):
        fails.append(f"helper + doctor + daemon must be GREEN, got {detect(TOPO_OK, DOC_OK, DMN_OK)}")
    if not detect("no helper", DOC_OK, DMN_OK):
        fails.append("a missing Topology helper must be RED")
    if not detect(TOPO_OK, "no check", DMN_OK):
        fails.append("a doctor that never checks must be RED")
    if not detect(TOPO_OK, DOC_OK, "bare error"):
        fails.append("a daemon that never names the sibling must be RED")
    if not detect(None, DOC_OK, DMN_OK):
        fails.append("missing files must be RED, never a vacuous pass")
    fails.extend(derived_mutants())
    if fails:
        print("JW-13 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("JW-13 SELFTEST OK — red on missing helper, unchecked doctor, bare-error daemon, and "
          "missing files; green only when a duplicate port is a named pre-flight failure")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(TOPO), _read(DOCTOR), _read(DAEMON))
    if problems:
        print("JW-13 WALL RED — a duplicate head port is an opaque bind error:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("JW-13 WALL GREEN: port collisions are a named pre-flight failure in doctor and the daemon.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
