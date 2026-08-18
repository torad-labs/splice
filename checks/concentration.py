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

    ratio = C / median(median C of each neighbouring PACKAGE)

  neighbours   — the packages this file imports from, plus the packages that import this file's
                 package. A file is only a god object RELATIVE to what it sits next to; a dense
                 domain-type module with thin neighbours is fine, a dense orchestrator surrounded
                 by thin helpers is not.

                 ONE VOTE PER PACKAGE, never one per file. A median taken over neighbour FILES
                 gives a package as many votes as it has files, so decomposing one importer into
                 twelve siblings multiplies that package's vote twelvefold and drags the median
                 down — this campaign's own edits then inflate the ratio of files nobody touched.
                 Measured on 647ed02 (ResponsesRequestBuilder -> 12 siblings): under the file-vote
                 denominator core/wire/AnthropicRequest.kt read 1.97 before and 5.57 after, band
                 moderate -> HIGH, without one line of it changing. Same reason the global median
                 below is taken over packages.

                 WHAT THE PACKAGE VOTE DOES NOT BUY, stated because an earlier revision of this
                 docstring claimed it did: it removes the vote-COUNT multiplication, it does NOT
                 make the oracle invariant to a WITHIN-package split. A package votes with the
                 median C of its own files, and redistributing that package's content across more
                 files moves that median. Measured on 06002e6 (ChatRequestBuilder -> 5 same-package
                 siblings): splice.dialect.chat went from files [20.5, 180.0, 216.5] to
                 [17.0, 19.5, 20.5, 28.0, 44.5, 47.0, 105.0, 216.5], median C 180.0 -> 36.25, and
                 47 files nobody edited moved — core/wire/AnthropicRequest.kt 3.34 -> 6.19 with C
                 unchanged at 275.5, app/Daemon.kt 7.93 -> 8.95, spi/UpstreamClient.kt 4.98 -> 5.20,
                 app/cli/Command.kt 1.95 -> 2.00 crossing low -> moderate. No file crossed the 1.8
                 gate that time; nothing in the metric guarantees the next one will not.

                 This is not a bug with a local repair. ANY file-scale denominator is a statistic of
                 the file-size distribution, and splitting a file changes that distribution, so no
                 choice of order statistic is invariant. The only partition-free denominator is the
                 neighbouring package's AGGREGATE C, and it was measured rather than assumed: it is
                 invariant (worst untouched drift 0.08 across both landings, zero band flips) but it
                 re-scopes the campaign — at every scale constant tried it flips the 1.8 verdict of
                 19-29 untouched files (over-1.8 40 -> 4..27, HIGH 22 -> 0..12), which would record 8
                 of the 12 HD-24 targets as decomposed without a line being touched. Adopting it is a
                 band-calibration decision for the orchestrator, not a property this script may change
                 on its own.

                 So the drift is not eliminated here, it is made non-silent: `--since <ref>` reports
                 every file whose ratio moved between a commit and the working tree and labels the
                 cause `own` (its own C changed) or `neighbourhood` (its C is identical and only the
                 denominator moved). A landing note may not claim a ratio without it.

  BANDS:  ratio < 1.8 low  |  1.8-3.0 moderate  |  >= 3.0 HIGH (god object)

The band that matters is HIGH. Note what the metric does NOT excuse: core/wire/AnthropicRequest.kt
carries 19 domain types in 133 lines and reads HIGH against its consumers — identically before and
after 647ed02, and 6.19 today, of which the 3.34 -> 6.19 step is neighbourhood drift from 06002e6
and not one line of the file. Whether a pure DTO hub should be scored by C at all is a calibration
question about C, not a regression, and is not decided here.

USAGE
    python3 checks/concentration.py                      # full table, exit 0
    python3 checks/concentration.py --top 15             # worst 15 only
    python3 checks/concentration.py --max-ratio 1.8      # gate: non-zero exit if any file is above
    python3 checks/concentration.py --file <path>        # one file, with its neighbour list
    python3 checks/concentration.py --since <ref>        # what moved since <ref>, and why
    python3 checks/concentration.py --json               # machine-readable
"""
from __future__ import annotations

import argparse
import io
import json
import pathlib
import re
import statistics
import subprocess
import sys
import tarfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC_GLOB = "gateway/*/src/main"
SRC_RE = re.compile(r"^gateway/[^/]+/src/main/.*\.kt$")

TYPE_DECL = re.compile(
    r"^(public |internal |private )?(sealed |data |abstract |open |value |enum )*(class|interface|object) "
)
EXPORT_DECL = re.compile(
    r"^(public |internal )?(sealed |data |abstract |open |value |enum |suspend |inline )*"
    r"(class|interface|object|fun|val|var) "
)
SPLICE_IMPORT = re.compile(r"^import (splice\.[A-Za-z0-9_.]+)\.[A-Za-z0-9_]+")


def measure(rel: str, text: str) -> dict:
    lines = text.splitlines()
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


def collect() -> list[dict]:
    files = [p for d in ROOT.glob(SRC_GLOB) for p in d.rglob("*.kt")]
    return [measure(str(p.relative_to(ROOT)), p.read_text(errors="replace")) for p in files]


def collect_ref(ref: str) -> list[dict]:
    """Same measurement, taken from a git ref instead of the working tree."""
    blob = subprocess.run(
        ["git", "-C", str(ROOT), "archive", ref, "--", "gateway"], capture_output=True, check=True
    ).stdout
    rows = []
    with tarfile.open(fileobj=io.BytesIO(blob)) as tar:
        for member in tar.getmembers():
            if member.isfile() and SRC_RE.match(member.name):
                text = tar.extractfile(member).read().decode(errors="replace")
                rows.append(measure(member.name, text))
    return rows


def scan(rows: list[dict]) -> list[dict]:
    by_package: dict[str, list[dict]] = {}
    for row in rows:
        by_package.setdefault(row["package"], []).append(row)

    # Each package votes once, with its own median C. See the module docstring: a per-file vote lets
    # a package that gets decomposed outvote every other neighbour, so the oracle moves on files
    # nobody touched. This removes the vote-COUNT effect only — a package's own median still moves
    # when its content is redistributed across more files, which is what `--since` exists to surface.
    package_median = {pkg: statistics.median([r["C"] for r in rs]) for pkg, rs in by_package.items()}

    # A ratio taken against a tiny neighbourhood is noise, not a god object: a 63-line file whose
    # neighbours happen to score 1 would read as 63x while being smaller than the median package in
    # the tree. Smooth the denominator with a floor at half the global median C, and require the
    # file itself to clear the global median before any band above "low" can apply.
    global_median = statistics.median(list(package_median.values())) if package_median else 0.0
    floor = global_median * 0.5

    for row in rows:
        neighbours = {pkg for pkg in row["subsystems"] if pkg in package_median}
        neighbours |= {other["package"] for other in rows if row["package"] in other["subsystems"]}
        neighbours.discard(row["package"])
        median = statistics.median([package_median[p] for p in neighbours]) if neighbours else row["C"]
        denominator = max(median, floor)
        row["neighbour_packages"] = sorted(neighbours)
        row["neighbour_median_C"] = round(median, 1)
        row["ratio"] = round(row["C"] / denominator, 2) if denominator else 0.0
        if row["C"] < global_median:
            row["band"] = "low"
        else:
            row["band"] = "HIGH" if row["ratio"] >= 3.0 else ("moderate" if row["ratio"] >= 1.8 else "low")
    return sorted(rows, key=lambda r: -r["ratio"])


def movement(ref: str, rows: list[dict]) -> list[dict]:
    """Every file whose ratio moved since `ref`, with the cause of the move.

    `own` means the file's own C changed, i.e. somebody edited it. `neighbourhood` means its C is
    byte-for-byte the same score and only the denominator moved — a number that belongs to whoever
    split a neighbouring package, never to this file's density. See the docstring.
    """
    before = {r["file"]: r for r in scan(collect_ref(ref))}
    after = {r["file"]: r for r in rows}
    moved = []
    for name in sorted(set(before) & set(after)):
        was, now = before[name], after[name]
        if was["ratio"] == now["ratio"]:
            continue
        moved.append(
            {
                "file": name,
                "cause": "own" if was["C"] != now["C"] else "neighbourhood",
                "C_before": was["C"],
                "C_after": now["C"],
                "ratio_before": was["ratio"],
                "ratio_after": now["ratio"],
                "band_before": was["band"],
                "band_after": now["band"],
            }
        )
    return moved


def report_movement(ref: str, moved: list[dict], max_ratio: float | None) -> None:
    print(f"{'file':58} {'ratio':>14}  {'band':>18}  cause")
    for m in moved:
        print(
            f"{m['file'].replace('gateway/', '').replace('/src/main/kotlin/splice', '~')[:58]:58} "
            f"{m['ratio_before']:6.2f} ->{m['ratio_after']:6.2f}  "
            f"{m['band_before']:>8} ->{m['band_after']:>8}  {m['cause']}"
        )
    drift = [m for m in moved if m["cause"] == "neighbourhood"]
    print(f"\n{len(moved)} file(s) moved since {ref} | own {len(moved) - len(drift)} | neighbourhood {len(drift)}")
    if max_ratio is not None:
        crossed = [m for m in drift if (m["ratio_before"] > max_ratio) != (m["ratio_after"] > max_ratio)]
        if crossed:
            print(f"\nWARNING: {len(crossed)} untouched file(s) crossed ratio {max_ratio} on neighbourhood drift:",
                  file=sys.stderr)
            for m in crossed:
                print(f"  {m['file']}  {m['ratio_before']} -> {m['ratio_after']}  C unchanged at {m['C_after']}",
                      file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--top", type=int, default=0, help="show only the worst N")
    ap.add_argument("--max-ratio", type=float, help="gate: fail if any file exceeds this ratio")
    ap.add_argument("--file", help="report one file and list its neighbours")
    ap.add_argument("--since", help="report what moved since a git ref, and whether it was own or neighbourhood")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    rows = scan(collect())

    if args.since:
        moved = movement(args.since, rows)
        if args.json:
            print(json.dumps(moved, indent=2))
        else:
            report_movement(args.since, moved, args.max_ratio)
        return 0

    if args.file:
        target = next((r for r in rows if r["file"].endswith(args.file)), None)
        if target is None:
            print(f"no such production file: {args.file}", file=sys.stderr)
            return 2
        print(json.dumps(target, indent=2))
        # --max-ratio GATES a single file too. It used to be silently ignored here, so
        # `--file <a HIGH file> --max-ratio 1.8` printed the offending ratio and still exited 0 —
        # a per-target gate that could not fail is the same fake green this scan exists to find.
        # Per-target acceptance (HD-24) is exactly this call, so it has to be able to go red.
        if args.max_ratio is not None and target["ratio"] > args.max_ratio:
            print(
                f"FAIL: {target['file']} ratio {target['ratio']} is above {args.max_ratio}",
                file=sys.stderr,
            )
            return 1
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
