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
  3. every "sanctioned" row cites its authority AND pins the new expected bytes — this applies
     IDENTICALLY to a [[divergence]] row (a field-level difference cutting across every scenario,
     which cannot be a [[scenario]] row without corrupting the roster). Before 2026-08-07 the
     loader read only the `scenario` array, so a divergence block was a comment with TOML syntax:
     deleting it left the wall's output byte-identical (found by the 2026-07-30 review repair,
     rescued from stash-archive/cx19-round2-repair-2026-07-30). Same law, one checker
     (_grade_status).
  4. every "passing" row carries its replay proof (a passed row STAYS enrolled — deleting it
     would un-protect the scenario, the exact bun#34441 hole; "leaves the red list" means the
     status stops being a problem, never that the row leaves the file)
  5. the row set still matches the fixture corpus both ways
  6. INTEGRITY (review finding #6): every fixture still hashes to the sha256 recorded in
     _manifest.json at capture time. Recording a hash and never checking it is the NO-SAVED-TRUTH
     hole qgre's zero_ratchet exists to close — a hand-edited fixture would otherwise pass silently.
  7. SURVIVABILITY (review finding #7): if server/ is gone the oracle can never be re-captured, so
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
PASSING = "passing"
KNOWN = {UNREPLAYED, WRONG, SANCTIONED, PASSING}


def _grade_status(ident: str, row: Mapping, counts: dict, problems: list) -> None:
    """The status contract, applied identically to a [[scenario]] and a [[divergence]] row.

    ONE function on purpose (2026-07-30 review repair): a written law that no checker applies
    is the bun#34441 shape this wall exists to close.
    """
    status = row.get("status")
    if status == UNREPLAYED:
        counts["unreplayed"] += 1
        problems.append(f"UNREPLAYED   {ident}: captured but never graded against the Kotlin gateway")
    elif status == WRONG:
        counts["wrong"] += 1
        problems.append(f"KOTLIN WRONG {ident}: gateway diverges and the reference was right")
    elif status == SANCTIONED:
        counts["sanctioned"] += 1
        if not str(row.get("authority", "")).strip():
            problems.append(f"UNCITED      {ident}: sanctioned divergence with no G-number / PR / campaign item")
        if not str(row.get("pinned_sha256", "")).strip():
            problems.append(f"UNPINNED     {ident}: sanctioned divergence does not pin the new expected "
                            "bytes — an unmonitored hole")
    elif status == PASSING:
        counts["passing"] += 1
        if not str(row.get("proof", "")).strip():
            problems.append(f"UNPROVEN     {ident}: passing with no replay proof — a pass nobody observed "
                            "is not a pass")
    else:
        problems.append(f"BAD STATUS   {ident}: unknown status '{status}'")


# The field the RUNNER reads to decide whether an observed value is the sanctioned one. A row that
# carries neither is a WILDCARD in replay.mjs: isSanctioned() matched the leaf and accepted any
# value at all, at any depth. Naming them here is what keeps the two checkers on the same field.
_RUNNER_PINS = ("pinned_value", "expected_without_session_header")


def _grade_divergence_pin(ident: str, row: Mapping, problems: list) -> None:
    """A sanctioned DIVERGENCE must pin bytes the runner can actually enforce.

    Before this (review 2026-08-12) the wall required `pinned_sha256` while replay.mjs read
    `pinned_value` / `expected_without_session_header` — two checkers, two different fields, both
    reporting green over a row that sanctioned every observed value. Requiring the sha to be the
    hash OF the runner-readable pin makes drift between them structurally impossible, and caught a
    live defect on day one: the prompt_cache_key row's `pinned_sha256` was 32 hex characters (the
    cache-key suffix, pasted) and had never been a hash of anything.
    """
    if row.get("status") != SANCTIONED:
        return
    pin = next((str(row[k]) for k in _RUNNER_PINS if str(row.get(k, "")).strip()), None)
    if pin is None:
        problems.append(f"WILDCARD     {ident}: sanctioned with no {' / '.join(_RUNNER_PINS)} — the "
                        "runner would accept ANY observed value at this field")
        return
    declared = str(row.get("pinned_sha256", "")).strip()
    actual = hashlib.sha256(pin.encode("utf-8")).hexdigest()
    if declared and declared != actual:
        problems.append(f"PIN MISMATCH {ident}: pinned_sha256 is not the sha256 of the pinned value "
                        f"(declared {declared[:16]}…, actual {actual[:16]}…)")


def detect(rows: list[dict], on_disk: set[str], manifest: Mapping[str, dict],
           digests: Mapping[str, str], server_present: bool,
           divergences: list[dict] | None = None) -> tuple[list[str], dict]:
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

    counts = {"rows": len(rows), "unreplayed": 0, "wrong": 0, "sanctioned": 0, "passing": 0,
              "divergences": len(divergences or [])}
    for r in rows:
        _grade_status(str(r.get("name")), r, counts, problems)
    for d in divergences or []:
        ident = f"divergence:{d.get('field', '<unnamed>')}"
        if not str(d.get("field", "")).strip():
            problems.append(f"NO FIELD     {ident}: a divergence row without a field cannot be tracked or retired")
        _grade_status(ident, d, counts, problems)
        _grade_divergence_pin(ident, d, problems)
    return problems, counts


def _load():
    doc = tomllib.loads(EXPECT.read_text(encoding="utf-8")) if EXPECT.exists() else {}
    rows = doc.get("scenario", [])
    divergences = doc.get("divergence", [])
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
    return rows, on_disk, manifest, digests, divergences


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
    # passing must carry its receipt — a pass nobody observed is not a pass
    case("passing unproven", [{"name": "s1", "status": PASSING}], {"s1"}, man, {"s1": sha_a}, True, True, "UNPROVEN")
    # THE integrity case (review finding #6) — a hand-edited fixture must not pass silently
    case("corrupt fixture", [{"name": "s1", "status": SANCTIONED, "authority": "G13", "pinned_sha256": "p"}],
         {"s1"}, man, {"s1": sha_b}, True, True, "CORRUPT")
    case("unrecorded fixture", [{"name": "s1", "status": SANCTIONED, "authority": "G", "pinned_sha256": "p"}],
         {"s1"}, {}, {"s1": sha_a}, True, True, "UNRECORDED")
    case("oracle lost", [], set(), {}, {}, False, True, "ORACLE LOST")
    case("bad status", [{"name": "s1", "status": "whatever"}], {"s1"}, man, {"s1": sha_a}, True, True, "BAD STATUS")
    # [[divergence]] rows ride the SAME checker — uncited/unpinned/field-less must be red
    for nm, div, needle in [
        ("divergence uncited", {"field": "f.x", "status": SANCTIONED, "pinned_sha256": "p"}, "UNCITED"),
        ("divergence unpinned", {"field": "f.x", "status": SANCTIONED, "authority": "PR#58"}, "UNPINNED"),
        ("divergence fieldless", {"status": SANCTIONED, "authority": "PR#58", "pinned_sha256": "p"}, "NO FIELD"),
        # 2026-08-12: a sanction the RUNNER cannot enforce. This shape satisfied every wall check
        # while replay.mjs accepted any value at all at that leaf — two checkers, two fields, both
        # green over an unmonitored hole.
        ("divergence wildcard", {"field": "f.x", "status": SANCTIONED, "authority": "PR#58",
                                 "pinned_sha256": "p"}, "WILDCARD"),
        # ...and the sha must actually BE the hash of that pin (the live prompt_cache_key row
        # carried a 32-char cache-key suffix in the sha256 field and nothing ever noticed).
        ("divergence pin mismatch", {"field": "f.x", "status": SANCTIONED, "authority": "PR#58",
                                     "pinned_value": "v", "pinned_sha256": "deadbeef"}, "PIN MISMATCH"),
    ]:
        got, _ = detect([{"name": "s1", "status": PASSING, "proof": "replay"}], {"s1"}, man, {"s1": sha_a},
                        True, divergences=[div])
        if not any(needle in g for g in got):
            fails.append(f"{nm}: expected a '{needle}' finding, got {got}")
    # the green shapes: sanctioned (cited+pinned) and passing (proven), divergences held to the same law
    case("green", [{"name": "s1", "status": SANCTIONED, "authority": "G13", "pinned_sha256": "abc"}],
         {"s1"}, man, {"s1": sha_a}, True, False)
    got, _ = detect(
        [{"name": "s1", "status": PASSING, "proof": "replay 2026-08-07"}], {"s1"}, man, {"s1": sha_a}, True,
        divergences=[{"field": "f.x", "status": SANCTIONED, "authority": "PR#58",
                      "pinned_value": "v", "pinned_sha256": "4c94485e0c21ae6c41ce1dfe7b6bfaceea5ab68e40a2476f50208e526f506080"}],
    )
    if got:
        fails.append(f"green passing+divergence: must be GREEN, got {got}")

    if fails:
        print("CX-19 SELFTEST FAIL:")
        for f in fails:
            print("  " + f)
        return 1
    print("CX-19 SELFTEST OK — red on unreplayed/kotlin-wrong/uncited/unpinned/unproven/unenrolled/"
          "phantom/CORRUPT/unrecorded/oracle-lost/bad-status, for scenario AND divergence rows; green "
          "only when every row is classified, cited/proven, pinned and byte-intact")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    if not EXPECT.exists():
        print(f"CX-19 WALL RED: {EXPECT.relative_to(ROOT)} missing — the oracle ledger is gone")
        return 1
    rows, on_disk, manifest, digests, divergences = _load()
    problems, counts = detect(rows, on_disk, manifest, digests, SERVER.is_dir(), divergences=divergences)
    print(f"CX-19 oracle: {counts['rows']} enrolled | {len(on_disk)} fixtures | "
          f"unreplayed {counts['unreplayed']} | kotlin-wrong {counts['wrong']} | "
          f"sanctioned {counts['sanctioned']} | passing {counts['passing']} | "
          f"divergences {counts['divergences']} | "
          f"server/ {'present' if SERVER.is_dir() else 'GONE (re-capture impossible)'}")
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
