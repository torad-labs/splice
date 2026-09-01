#!/usr/bin/env python3
"""Attest that no campaign ledger has silently lost memory (DR-181).

WHY THIS EXISTS.

On 2026-09-01 `dev/campaigns/drift-repair.toml` went from 164 rows to 15 and lost 2604 lines. It
was committed and pushed, and the FULL gate passed 13 of 13 legs on that tip. Nothing was broken
in the sense any leg could observe: the tree compiled, every test ran, every wall was green. No
leg read the file at all, so a green gate said nothing whatsoever about campaign memory — and the
campaign ledger is the one artifact in this repo whose whole purpose is to survive the session
that wrote it.

That is a section-24 denominator failure inside our own tooling. Thirteen checks, each with a
denominator drawn from the source tree, and the destroyed artifact was in none of them. A check
cannot fail for a thing it does not enumerate.

WHAT THIS MEASURES.

Two instruments per ledger, both monotonic under every verb a campaign actually uses:

    rows   `[[items]]` blocks — a work unit. Added by `add`, removed only by `remove`.
    lines  total file lines — the NOTES. Rows are the skeleton; the dated notes under them are
           the reasoning, the verdicts, and the resume pointers, and they were the overwhelming
           majority of the 2604 lines lost. A truncation that preserved row count while deleting
           every note would still destroy the campaign, so row count alone is not enough.

THE DENOMINATOR COMES FROM THE DIRECTORY, NOT FROM THE FLOOR FILE. Ledgers are enumerated by
RECURSIVELY globbing dev/campaigns/**/*.toml. A ledger present on disk but absent from the floor
file FAILS by name — absence is not a disposition. A ledger named in the floor file but gone from
disk fails too: a whole campaign vanishing is the loudest truncation there is, and a check that
only iterated its own allowlist would report success over an empty directory.

DR-189 MADE THAT GLOB RECURSIVE, and the reason is the whole point of the paragraph above. The
first version globbed one level, which quietly meant "campaign memory" was defined as "files that
happen to sit at the top of the directory". Three memory-bearing registries live one level down —
proxy-hardening's wall_registry.toml (88 rows), law_registry.toml (19 rows) and the oracle's
expectations.toml — and each carries an explicit never-delete law in its own header. Truncating
each and running every leg that reads it showed wall_registry and expectations are caught by the
campaign wall gate, which grades them against an EXTERNAL denominator; law_registry is not, because
its wall (inf_02_every_law_walled.py) iterates the very rows it checks and so cannot fail for a row
that was deleted. 19 laws to 1 with every leg green — the same tautology this file was written
about, one directory down. Keys are paths relative to dev/campaigns/, so two registries sharing a
basename cannot collide into one floor entry.

THE FLOOR LIVES OUTSIDE dev/campaigns/. If the high-water marks were stored in the ledgers, the
same accident would take both and the check would agree with the wreckage. Two hand-authored lists
that check each other are not a check against reality.

RAISING IS FREE, LOWERING IS DELIBERATE. `--check` is what the gate runs. The bare invocation
re-records the floors upward, which is the gate's own remedy for the ordinary case of "the
campaign grew". Lowering a floor demands --allow-shrink, so a shrink can never be absorbed as
routine maintenance: it becomes a typed act and a reviewable diff line sitting next to the
deletion that caused it. That diff is precisely the review signal that was missing.

Usage:
    python3 checks/campaign-ledger-floor.py                 # re-record (raise only)
    python3 checks/campaign-ledger-floor.py --check         # verify; the gate leg
    python3 checks/campaign-ledger-floor.py --allow-shrink  # re-record, permitting a decrease
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LEDGER_DIR = ROOT / "dev" / "campaigns"
FLOOR = ROOT / "checks" / "config" / "campaign-ledger-floor.json"

# The same shape manifest.py's own HDR matches, so this counts what the CLI calls an item.
HDR = re.compile(r"^\[\[items?\]\]\s*$")


def measure(path: Path) -> dict[str, int]:
    lines = path.read_text(encoding="utf-8").splitlines()
    return {"rows": sum(1 for line in lines if HDR.match(line)), "lines": len(lines)}


def survey() -> dict[str, dict[str, int]]:
    """Every ledger under the directory — the denominator, read from the source.

    DR-189: recursive, and keyed by the path relative to LEDGER_DIR rather than by basename. A
    top-level ledger's key is unchanged by that (its relative path IS its name), so the existing
    floor entries carry over; a nested one gets its full relative path and cannot collide with a
    sibling campaign's file of the same name.
    """
    return {
        str(p.relative_to(LEDGER_DIR)): measure(p)
        for p in sorted(LEDGER_DIR.rglob("*.toml"))
    }


def load_floor() -> dict[str, dict[str, int]]:
    if not FLOOR.exists():
        return {}
    return json.loads(FLOOR.read_text(encoding="utf-8"))


def violations(current: dict, floor: dict) -> list[str]:
    found = []
    for name in sorted(set(current) | set(floor)):
        if name not in floor:
            found.append(
                f"{name}: on disk with no recorded floor — every ledger needs a disposition. "
                f"Run `python3 checks/campaign-ledger-floor.py` to record it."
            )
            continue
        if name not in current:
            found.append(f"{name}: RECORDED BUT GONE — a whole campaign ledger has disappeared.")
            continue
        for unit in ("rows", "lines"):
            have, want = current[name][unit], floor[name].get(unit, 0)
            if have < want:
                found.append(
                    f"{name}: {unit} fell {want - have} below the recorded floor "
                    f"({have} < {want}) — campaign memory was lost, not added to."
                )
    return found


def main(argv: list[str]) -> int:
    check = "--check" in argv
    allow_shrink = "--allow-shrink" in argv
    current, floor = survey(), load_floor()

    if not current:
        print(f"campaign-ledger-floor: no ledgers found in {LEDGER_DIR}", file=sys.stderr)
        return 1

    if check:
        found = violations(current, floor)
        if found:
            print("campaign-ledger-floor: FAIL", file=sys.stderr)
            for v in found:
                print(f"  {v}", file=sys.stderr)
            print(
                "\nA ledger is the memory of a campaign. If this shrink is deliberate (a `remove`,\n"
                "a retired campaign), re-record it explicitly:\n"
                "  python3 checks/campaign-ledger-floor.py --allow-shrink",
                file=sys.stderr,
            )
            return 1
        print(f"campaign-ledger-floor: OK ({len(current)} ledgers at or above their floors)")
        return 0

    if not allow_shrink:
        shrinks = [v for v in violations(current, floor) if "below the recorded floor" in v or "GONE" in v]
        if shrinks:
            print("campaign-ledger-floor: refusing to lower a floor without --allow-shrink", file=sys.stderr)
            for v in shrinks:
                print(f"  {v}", file=sys.stderr)
            return 1
        current = {n: {u: max(current[n][u], floor.get(n, {}).get(u, 0)) for u in ("rows", "lines")} for n in current}

    FLOOR.parent.mkdir(parents=True, exist_ok=True)
    FLOOR.write_text(json.dumps(current, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"campaign-ledger-floor: recorded {len(current)} ledgers -> {FLOOR.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
