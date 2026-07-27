#!/usr/bin/env python3
"""WALL for CX-19 — every captured oracle scenario must have a replay verdict, and the corpus
must still be the corpus that was captured.

CX-19 as written in the audit was "add a response-side golden per dialect", where WE author the
golden — grading ourselves. The migration oracle reshapes it: the golden comes from the legacy Node
reference implementation, recorded byte-exactly by `npm run oracle:capture` while that stack was
green (104/104, `cd server && node --test`).

GAP (RED at authoring, 2026-07-26): 11 scenarios captured, 0 replayed. Per bun#34441 a captured-
but-unreplayed scenario "wasn't counted and wasn't protected against regression" — the fixtures are
inert until something grades against them.

GREEN requires ALL of:
  1. every [[scenario]] row has left "not-yet-replayed"
  2. no row is "kotlin-wrong" (open bugs by definition)
  3. every "sanctioned" row cites its authority AND pins the new expected bytes
  4. the row set still matches the fixture corpus (rows leave by passing, never by deletion)
  5. INTEGRITY (review finding #6): every fixture still hashes to the sha256 recorded in
     _manifest.json at capture time. Recording a hash and never checking it is the NO-SAVED-TRUTH
     hole qgre's zero_ratchet exists to close — a hand-edited fixture would otherwise pass silently.
  6. SURVIVABILITY (review finding #7): if server/ is gone the oracle can never be re-captured, so
     the fixtures must be present and intact. This wall says so out loud rather than letting
     `oracle:capture` fail confusingly later.

EXIT 0 = replayed, classified, and intact.  EXIT 1 = work remains or the corpus drifted.
--selftest = the POSITIVE CONTROL (gate check C6).
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import sys
import tomllib
from collections.abc import Mapping

ROOT = pathlib.Path(__file__).resolve().parents[4]
ORACLE = ROOT / "dev/campaigns/proxy-hardening/oracle"
EXPECT = ORACLE / "expectations.toml"
FIXTURES = ORACLE / "fixtures"
SERVER = ROOT / "server"

UNREPLAYED = "not-yet-replayed"
WRONG = "kotlin-wrong"
SANCTIONED = "sanctioned"
KNOWN = {UNREPLAYED, WRONG, SANCTIONED}


def detect(rows: list[dict], on_disk: set[str], manifest: Mapping[str, dict],
           digests: Mapping[str, str], server_present: bool) -> tuple[list[str], dict]:
    """Pure detection. digests: fixture stem -> sha256 of its current bytes."""
    problems: list[str] = []
    named = {str(r.get("name", "")) for r in rows}

    for missing in sorted(on_disk - named):
        problems.append(f"UNENROLLED   {missing}: fixture on disk with no expectations row — "
                        "not counted, not protected (bun#34441)")
    for phantom in sorted(named - on_disk):
        problems.append(f"NO FIXTURE   {phantom}: expectations row names a fixture that is not on disk")

    # integrity — the recorded sha256 must still hold
    for stem, sha in sorted(digests.items()):
        rec = manifest.get(stem, {}).get("sha256")
        if rec is None:
            problems.append(f"UNRECORDED   {stem}: fixture has no sha256 in _manifest.json — "
                            "cannot prove it is the bytes that were captured")
        elif rec != sha:
            problems.append(f"CORRUPT      {stem}: fixture bytes differ from the sha256 recorded at "
                            "capture time. Either it was hand-edited (revert it) or it was legitimately "
                            "re-captured (re-run oracle:capture so the manifest agrees).")

    if not server_present and not on_disk:
        problems.append("ORACLE LOST  server/ is gone AND no fixtures remain — the migration oracle is "
                        "unrecoverable. It cannot be re-captured.")

    counts = {"rows": len(rows), "unreplayed": 0, "wrong": 0, "sanctioned": 0}
    for r in rows:
        name, status = r.get("name"), r.get("status")
        if status == UNREPLAYED:
            counts["unreplayed"] += 1
            problems.append(f"UNREPLAYED   {name}: captured but never graded against the Kotlin gateway")
        elif status == WRONG:
            counts["wrong"] += 1
            problems.append(f"KOTLIN WRONG {name}: gateway diverges and the reference was right")
        elif status == SANCTIONED:
            counts["sanctioned"] += 1
            if not str(r.get("authority", "")).strip():
                problems.append(f"UNCITED      {name}: sanctioned divergence with no G-number / campaign item")
            if not str(r.get("pinned_sha256", "")).strip():
                problems.append(f"UNPINNED     {name}: sanctioned divergence does not pin the new expected "
                                "bytes — an unmonitored hole")
        else:
            problems.append(f"BAD STATUS   {name}: unknown status '{status}'")
    return problems, counts


def _load():
    rows = tomllib.loads(EXPECT.read_text(encoding="utf-8")).get("scenario", []) if EXPECT.exists() else []
    on_disk, digests = set(), {}
    manifest = {}
    if FIXTURES.is_dir():
        mf = FIXTURES / "_manifest.json"
        if mf.exists():
            manifest = json.loads(mf.read_text(encoding="utf-8")).get("scenarios", {})
        for p in sorted(FIXTURES.glob("*.json")):
            if p.stem.startswith("_"):
                continue
            on_disk.add(p.stem)
            digests[p.stem] = hashlib.sha256(p.read_bytes()).hexdigest()
    return rows, on_disk, manifest, digests


def selftest() -> int:
    fails = []
    sha_a = hashlib.sha256(b"a").hexdigest()
    sha_b = hashlib.sha256(b"b").hexdigest()
    man = {"s1": {"sha256": sha_a}}

    def case(name, rows, disk, manifest, digests, server, want_red, needle=None):
        got, _ = detect(rows, disk, manifest, digests, server)
        if want_red and not got:
            fails.append(f"{name}: must be RED")
        if not want_red and got:
            fails.append(f"{name}: must be GREEN, got {got}")
        if needle and not any(needle in g for g in got):
            fails.append(f"{name}: expected a '{needle}' finding, got {got}")

    case("unreplayed", [{"name": "s1", "status": UNREPLAYED}], {"s1"}, man, {"s1": sha_a}, True, True, "UNREPLAYED")
    case("kotlin-wrong", [{"name": "s1", "status": WRONG}], {"s1"}, man, {"s1": sha_a}, True, True, "KOTLIN WRONG")
    case("sanctioned uncited", [{"name": "s1", "status": SANCTIONED, "pinned_sha256": "x"}],
         {"s1"}, man, {"s1": sha_a}, True, True, "UNCITED")
    case("sanctioned unpinned", [{"name": "s1", "status": SANCTIONED, "authority": "G13"}],
         {"s1"}, man, {"s1": sha_a}, True, True, "UNPINNED")
    case("unenrolled fixture", [], {"s1"}, man, {"s1": sha_a}, True, True, "UNENROLLED")
    case("phantom row", [{"name": "s9", "status": SANCTIONED, "authority": "G", "pinned_sha256": "p"}],
         set(), {}, {}, True, True, "NO FIXTURE")
    # THE integrity case (review finding #6) — a hand-edited fixture must not pass silently
    case("corrupt fixture", [{"name": "s1", "status": SANCTIONED, "authority": "G13", "pinned_sha256": "p"}],
         {"s1"}, man, {"s1": sha_b}, True, True, "CORRUPT")
    case("unrecorded fixture", [{"name": "s1", "status": SANCTIONED, "authority": "G", "pinned_sha256": "p"}],
         {"s1"}, {}, {"s1": sha_a}, True, True, "UNRECORDED")
    case("oracle lost", [], set(), {}, {}, False, True, "ORACLE LOST")
    case("bad status", [{"name": "s1", "status": "whatever"}], {"s1"}, man, {"s1": sha_a}, True, True, "BAD STATUS")
    # the one green shape: sanctioned, cited, pinned, intact
    case("green", [{"name": "s1", "status": SANCTIONED, "authority": "G13", "pinned_sha256": "abc"}],
         {"s1"}, man, {"s1": sha_a}, True, False)

    if fails:
        print("CX-19 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("CX-19 SELFTEST OK — red on unreplayed/kotlin-wrong/uncited/unpinned/unenrolled/phantom/"
          "CORRUPT/unrecorded/oracle-lost/bad-status; green only when every row is classified, cited, "
          "pinned and byte-intact")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    if not EXPECT.exists():
        print(f"CX-19 WALL RED: {EXPECT.relative_to(ROOT)} missing — the oracle ledger is gone")
        return 1
    rows, on_disk, manifest, digests = _load()
    problems, counts = detect(rows, on_disk, manifest, digests, SERVER.is_dir())
    print(f"CX-19 oracle: {counts['rows']} enrolled | {len(on_disk)} fixtures | "
          f"unreplayed {counts['unreplayed']} | kotlin-wrong {counts['wrong']} | "
          f"sanctioned {counts['sanctioned']} | server/ {'present' if SERVER.is_dir() else 'GONE (re-capture impossible)'}")
    if problems:
        print("CX-19 WALL RED — the captured oracle is not yet grading anything:")
        for p in problems[:14]:
            print(f"  · {p}")
        if len(problems) > 14:
            print(f"  · … and {len(problems) - 14} more")
        return 1
    print("CX-19 WALL GREEN: every captured scenario replayed, classified, and byte-intact.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
