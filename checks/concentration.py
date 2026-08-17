#!/usr/bin/env python3
"""Responsibility-concentration scan — the oracle for the decomposition campaign.

WHY THIS EXISTS: the style migration made the tree compliant (no top-level functions, no companion
objects) WITHOUT making it decomposed. The 14-function-per-class ceiling pushed collaborators into
existence inside the files that were already too big, so concentration moved sideways rather than
down: TurnDriver ended up declaring 12 types in one file, Daemon 9 types importing 32 subsystems.
A per-class function count cannot see that. This can.

THE METRIC. For each production .kt file:

    C = 0.5*logic_lines + 3*exported_declarations + 8*concerns

  logic_lines  — non-blank, non-comment, non-import, non-package
  exports      — top-level class/interface/object/fun/val/var declarations
  concerns     — declared types in the file PLUS distinct splice.* subsystems it imports.
                 This is the term that catches the failure above: splitting one god class into
                 six collaborators in the same file RAISES concerns rather than lowering it.

Then, per file, the gradient against its neighbours:

    ratio = C / median(C of neighbours)

  neighbours   — files in the packages this file imports from, plus files whose packages import
                 this file's package. A file is only a god object RELATIVE to what it sits next to;
                 a dense domain-type module with thin neighbours is fine, a dense orchestrator
                 surrounded by thin helpers is not.

  BANDS:  ratio < 1.8 low  |  1.8-3.0 moderate  |  >= 3.0 HIGH (god object)

A natural hub is allowed to be dense: core/wire/AnthropicRequest.kt carries 19 exports in 133 lines
and scores 2.0, which is the right shape for a module that declares domain types. The band that
matters is HIGH.

USAGE
    python3 checks/concentration.py                      # full table, exit 0
    python3 checks/concentration.py --top 15             # worst 15 only
    python3 checks/concentration.py --max-ratio 1.8      # gate: non-zero exit if any file is above
    python3 checks/concentration.py --file <path>        # one file, with its neighbour list
    python3 checks/concentration.py --json               # machine-readable
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import statistics
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC_GLOB = "gateway/*/src/main"

TYPE_DECL = re.compile(
    r"^(public |internal |private )?(sealed |data |abstract |open |value |enum )*(class|interface|object) "
)
EXPORT_DECL = re.compile(
    r"^(public |internal )?(sealed |data |abstract |open |value |enum |suspend |inline )*"
    r"(class|interface|object|fun|val|var) "
)
SPLICE_IMPORT = re.compile(r"^import (splice\.[A-Za-z0-9_.]+)\.[A-Za-z0-9_]+")


def measure(path: pathlib.Path) -> dict:
    lines = path.read_text(errors="replace").splitlines()
    logic = [
        line
        for line in lines
        if line.strip()
        and not line.strip().startswith(("//", "*", "/*"))
        and not line.startswith(("import ", "package "))
    ]
    exports = [line for line in lines if EXPORT_DECL.match(line)]
    types = [line for line in lines if TYPE_DECL.match(line)]
    subsystems = {m.group(1) for line in lines if (m := SPLICE_IMPORT.match(line))}
    concerns = len(types) + len(subsystems)
    rel = str(path.relative_to(ROOT))
    return {
        "file": rel,
        "package": ".".join(rel.split("/kotlin/")[-1].split("/")[:-1]).replace("/", "."),
        "logic": len(logic),
        "exports": len(exports),
        "types": len(types),
        "subsystems": sorted(subsystems),
        "concerns": concerns,
        "C": round(0.5 * len(logic) + 3 * len(exports) + 8 * concerns, 1),
    }


def scan() -> list[dict]:
    files = [p for d in ROOT.glob(SRC_GLOB) for p in d.rglob("*.kt")]
    rows = [measure(p) for p in files]
    by_package: dict[str, list[dict]] = {}
    for row in rows:
        by_package.setdefault(row["package"], []).append(row)

    # A ratio taken against a tiny neighbourhood is noise, not a god object: a 63-line file whose
    # neighbours happen to score 1 would read as 63x while being smaller than the median file in the
    # tree. Smooth the denominator with a floor at half the global median C, and require the file
    # itself to clear the global median before any band above "low" can apply.
    global_median = statistics.median([r["C"] for r in rows]) if rows else 0.0
    floor = global_median * 0.5

    for row in rows:
        neighbours: list[dict] = []
        for subsystem in row["subsystems"]:
            neighbours.extend(by_package.get(subsystem, []))
        for other in rows:
            if row["package"] in other["subsystems"]:
                neighbours.append(other)
        neighbours = [n for n in neighbours if n["file"] != row["file"]]
        median = statistics.median([n["C"] for n in neighbours]) if neighbours else row["C"]
        denominator = max(median, floor)
        row["neighbours"] = len(neighbours)
        row["neighbour_median_C"] = round(median, 1)
        row["ratio"] = round(row["C"] / denominator, 2) if denominator else 0.0
        if row["C"] < global_median:
            row["band"] = "low"
        else:
            row["band"] = "HIGH" if row["ratio"] >= 3.0 else ("moderate" if row["ratio"] >= 1.8 else "low")
    return sorted(rows, key=lambda r: -r["ratio"])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--top", type=int, default=0, help="show only the worst N")
    ap.add_argument("--max-ratio", type=float, help="gate: fail if any file exceeds this ratio")
    ap.add_argument("--file", help="report one file and list its neighbours")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    rows = scan()

    if args.file:
        target = next((r for r in rows if r["file"].endswith(args.file)), None)
        if target is None:
            print(f"no such production file: {args.file}", file=sys.stderr)
            return 2
        print(json.dumps(target, indent=2))
        return 0

    if args.json:
        print(json.dumps(rows if not args.top else rows[: args.top], indent=2))
    else:
        shown = rows[: args.top] if args.top else [r for r in rows if r["band"] != "low"]
        print(f"{'file':58} {'C':>7} {'nbrMed':>7} {'ratio':>6}  band")
        for r in shown:
            print(
                f"{r['file'].replace('gateway/', '').replace('/src/main/kotlin/splice', '~')[:58]:58} "
                f"{r['C']:7.0f} {r['neighbour_median_C']:7.0f} {r['ratio']:6.2f}  {r['band']}"
            )
        high = [r for r in rows if r["band"] == "HIGH"]
        med = [r for r in rows if r["band"] == "moderate"]
        print(f"\n{len(rows)} files | HIGH {len(high)} | moderate {len(med)} | low {len(rows) - len(high) - len(med)}")

    if args.max_ratio is not None:
        over = [r for r in rows if r["ratio"] > args.max_ratio]
        if over:
            print(f"\nFAIL: {len(over)} file(s) above ratio {args.max_ratio}:", file=sys.stderr)
            for r in over:
                print(f"  {r['file']}  ratio={r['ratio']}  C={r['C']}", file=sys.stderr)
            return 1
        print(f"\nOK: every file is at or below ratio {args.max_ratio}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
