#!/usr/bin/env python3
"""WALL for JW-04 — an edited splice.toml must be VISIBLY stale, never silently inert.

GAP (RED at authoring, 2026-08-07): topology loads once at boot (locked no-hot-reload decision),
the shim replaces a daemon only on a version mismatch, and nothing anywhere compares the file on
disk to what the daemon booted with — the classic "I changed the config and nothing happened"
dead end.

GREEN requires ALL of (visibility only — hot reload stays deliberately absent):
  1. /health publishes the booted topologyDigest + configPath (ControlServer);
  2. the daemon can answer "is the on-disk file different now" (topologyStale, recomputed
     per request, failing OPEN on an unreadable file);
  3. bin/splice-launch warns (non-fatal, names `splice restart`) on a stale topology;
  4. doctor renders the digest comparison (WARN + splice restart fix on mismatch).

EXIT 0 = staleness visible. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
CONTROL = ROOT / "gateway/control/src/main/kotlin/splice/control/ControlServer.kt"
SHIM = ROOT / "bin/splice-launch"
DOCTOR = ROOT / "gateway/app/src/main/kotlin/splice/app/cli/DoctorCommand.kt"


def detect(control: str | None, shim: str | None, doctor: str | None) -> list[str]:
    """Pure detection. No I/O — the selftest feeds it directly."""
    for name, text in (("ControlServer.kt", control), ("bin/splice-launch", shim), ("DoctorCommand.kt", doctor)):
        if text is None:
            return [f"{name} missing — refusing to pass vacuously"]
    problems: list[str] = []
    if "topologyDigest" not in (control or ""):
        problems.append("/health carries no topologyDigest — no consumer can ever know the "
                        "running daemon booted from different bytes")
    if "topologyStale" not in (control or ""):
        problems.append("the daemon never re-compares the on-disk file — every consumer would "
                        "have to reimplement the hash")
    if "topologyStale" not in (shim or "") and "topologyDigest" not in (shim or ""):
        problems.append("the launch shim never checks topology staleness — the operator relaunch "
                        "after an edit stays silently inert")
    if "splice restart" not in (shim or ""):
        problems.append("the shim's staleness warning does not name the fix (splice restart)")
    if "topologyDigest" not in (doctor or "") and "topologyStale" not in (doctor or ""):
        problems.append("doctor never compares the on-disk config to the booted one")
    return problems


def _read(p: pathlib.Path) -> str | None:
    return p.read_text(encoding="utf-8") if p.exists() else None


CONTROL_OK = 'put("topologyDigest", d)\nput("topologyStale", stale)'
SHIM_OK = 'topologyStale warn "splice restart"'
DOCTOR_OK = "health.topologyDigest != localDigest -> WARN"


def selftest() -> int:
    fails = []
    if not detect("controlHealthJson version only", "shim", "doctor"):
        fails.append("today's digest-less shape must be RED")
    if detect(CONTROL_OK, SHIM_OK, DOCTOR_OK):
        fails.append(f"published+checked shape must be GREEN, got {detect(CONTROL_OK, SHIM_OK, DOCTOR_OK)}")
    if not detect(CONTROL_OK, "shim without the check", DOCTOR_OK):
        fails.append("a shim that never checks must be RED")
    if not detect(CONTROL_OK, SHIM_OK, "doctor blind"):
        fails.append("a doctor that never compares must be RED")
    if not detect('put("topologyDigest", d) only', SHIM_OK, DOCTOR_OK):
        fails.append("a daemon that publishes the digest but never re-compares must be RED")
    if not detect(None, SHIM_OK, DOCTOR_OK):
        fails.append("missing files must be RED, never a vacuous pass")
    if fails:
        print("JW-04 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("JW-04 SELFTEST OK — red on digest-less health, non-checking shim, blind doctor, "
          "publish-without-recompare, and missing files; green only when staleness is visible")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    problems = detect(_read(CONTROL), _read(SHIM), _read(DOCTOR))
    if problems:
        print("JW-04 WALL RED — an edited splice.toml is silently inert:")
        for p in problems:
            print(f"  · {p}")
        return 1
    print("JW-04 WALL GREEN: the booted topology digest is published, re-compared, and surfaced by shim and doctor.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
