#!/usr/bin/env python3
"""Responsibility-concentration scan — the oracle for the decomposition campaign.

WHY THIS EXISTS: the style migration made the tree compliant (no top-level functions, no companion
objects) WITHOUT making it decomposed. The 14-function-per-class ceiling pushed collaborators into
existence inside the files that were already too big, so concentration moved sideways rather than
down: TurnDriver ended up declaring 12 types in one file, Daemon 9 types importing 32 subsystems.
A per-class function count cannot see that. This can.

THE METRIC. For each production .kt file:

    C = 0.5*logic_lines + 3*non_type_exports + 8*concerns

  logic_lines  — non-blank, non-comment, non-import, non-package
  non_type_exports
               — top-level fun/val/var declarations. A top-level class/interface/object is NOT
                 counted here, because `concerns` below already counts it. See ONE DECLARATION,
                 ONE BILL.
  concerns     — declared types in the file PLUS distinct splice.* subsystems it imports.
                 This is the term that catches the failure above: splitting one god class into
                 six collaborators in the same file RAISES concerns rather than lowering it.

                 ONE DECLARATION, ONE BILL (2026-08-18). The original `3*exports + 8*concerns`
                 charged every declared type TWICE — once as an export, once as a concern — so a
                 type cost 11 points, the equivalent of 22 logic lines, and a one-line
                 `data class TextBlock(val text: String)` scored as 22 lines of orchestration. On
                 core/wire/AnthropicRequest.kt (19 types in 133 lines) that double bill was 57 of
                 275.5 points. Measured blast radius of the correction across this tree: 8 band
                 flips, 2 files crossing 1.8 (dialect/passthrough/PassthroughProvider.kt
                 1.63 -> 1.83, gateway/round/ReanchorRunner.kt 1.70 -> 1.89), tree census
                 HIGH 8 -> 12, moderate 27 -> 27, over-1.8 35 -> 37, and all twelve HD-24 targets
                 stay band low with the worst at 1.77. It is a CORRECTNESS fix, not a
                 calibration: it says nothing about what a declaration should cost, only that it
                 is charged once. Whether a declaration should cost 8 at all remains open.

                 SUBTRACTED PER LINE, never as `exports - types`. TYPE_DECL admits `private ` and
                 EXPORT_DECL does not, so the 14 files here that carry a top-level private class
                 hold a concern that was never an export — `3*(exports - types)` hands each of
                 them a -3 CREDIT, paying a file for hiding a collaborator behind `private`,
                 which is precisely how a god file's collaborators get born. Measured: 14 files
                 differ, min(exports - types) = -1. Per-line subtraction cannot go negative and
                 has the smaller blast radius of the two (8 band flips against 9).

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

CEILING EXCEPTIONS. Two files in this tree provably cannot reach 1.8 by refactoring, and a gate
that pretends otherwise buys its green by lying about what is reachable. They are named in
CEILING_EXCEPTIONS below with a ceiling and a justification a reader can evaluate, in the idiom
this repo already uses twice — `nonLibrary` in splice.module-law.gradle.kts and UNROUTED_ALLOWLIST
in checks/rule-routing.sh. Four properties, each of which is what stops the list becoming a
laundry:

  - A CEILING, NOT A BLANKET. An excepted file is graded against its own recorded ceiling instead
    of --max-ratio. If its ratio RISES above that ceiling the gate still fails. An exception
    freezes a known state; it does not stop watching.
  - A BLANK OR MISSING JUSTIFICATION IS A HARD ERROR, not a pass. The justification is dated and
    mechanically shape-checked, exactly as UNROUTED_ALLOWLIST does it — an undated exemption is
    how the next one hides.
  - A STALE ENTRY IS A HARD ERROR. An exception naming a file that no longer exists fails the run
    rather than going quiet, because a silently-dead entry is an un-graded file one rename later.
  - ALWAYS LISTED. Every run prints the active exceptions separately from the passing files, so
    they are read rather than silently applied.

Nothing else belongs here. Every other file above 1.8 is work HD-25 is about to do, and putting
one on this list is the exact laundering the mechanism exists to prevent.

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
import textwrap

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

# --------------------------------------------------------------------------------------------
# The files this tree provably cannot bring under the gate by refactoring. Format:
# (path, ceiling ratio, "YYYY-MM-DD: why"), the same shape as UNROUTED_ALLOWLIST in
# checks/rule-routing.sh. Read CEILING EXCEPTIONS in the module docstring before adding one; the
# short version is that an entry here is a CEILING that still fails when breached, its
# justification is mechanically required, a stale entry is a hard error, and every run prints the
# list. Two entries, and every other file above the gate is HD-25's work — a third entry added to
# make a red gate green is the laundering this list exists to prevent.
CEILING_EXCEPTIONS: list[tuple[str, float, str]] = [
    (
        "gateway/core/src/main/kotlin/splice/core/wire/AnthropicRequest.kt",
        4.19,
        "2026-08-18: 15 Anthropic wire-format DTOs in 74 lines, with ZERO splice.* imports and "
        "ZERO non-type exports — the entire score is the declarations, and the declarations are "
        "one cohesive wire surface. TIGHTENED from 5.83 the same day: the four serializer objects "
        "moved verbatim to the same package's AnthropicWireCodecs.kt (HD-25), which was the one "
        "honest seam here — they were the only declarations in the file carrying algorithm, and "
        "being same-package the move cost no consumer an import. What is left is the shape "
        "catalogue, and a ceiling recorded above it would exempt room the file no longer needs. "
        "Every destination a split could use is closed by the module "
        "direction law: :core's allowed-dependency set is empty "
        "(gateway/build-logic/src/main/kotlin/splice.module-law.gradle.kts:15), and five of this "
        "file's six neighbour packages sit outside :core (splice.dialect.chat, "
        "splice.dialect.passthrough, splice.dialect.responses, splice.gateway.compact, "
        "splice.gateway.reasoning), so moving any type there inverts the graph; the three dialect "
        "modules additionally may not depend on each other, so the shared request shape cannot be "
        "parked in one of them and read by the other two. The sixth neighbour, splice.core.parse, "
        "is a consumer. A 26-configuration weighting sweep (HD-25 note, 2026-08-17) found no "
        "variant that leaves this file below HIGH without flipping a third of the tree. The "
        "ceiling is its measured ratio under one-declaration-one-bill, not a target — and with "
        "no margin, so a future breach may be NEIGHBOURHOOD drift rather than this file growing: "
        "its C is 157.0 over a denominator of 37.5, and any split inside a neighbour package "
        "lowers that denominator with nothing here changing. Run --since before reading a red as "
        "regression; cause `own` is this file, cause `neighbourhood` is the denominator moving.",
    ),
    (
        "gateway/provider-spi/src/main/kotlin/splice/spi/UpstreamClient.kt",
        6.14,
        "2026-08-18: unlike AnthropicRequest this file is mass, not declarations — 491 logic "
        "lines against 4 types, 6 non-type exports and 3 subsystems, so 245.5 of its 319.5 C is "
        "the logic term. That mass is one retry loop whose FOUR budgets share a single RetryState "
        "(UpstreamClient.kt:251-277): `attempt` (connect-phase backoff), `refreshedOnce` (the 401 "
        "single-flight refresh, which must NOT consume an attempt), `streamReissues` (G5 — spans "
        "the whole turn, never reset per handoff, deliberately independent of maxRetries) and "
        "`amendedOnce` (RC-4 — budgeted alone, never by the attempt counter). Their MUTUAL "
        "INDEPENDENCE is the invariant, and it is load-bearing: the review recorded in-code at "
        "UpstreamClient.kt:264-269 (2026-07-24) found that coupling just two of them, via a single "
        "`attempt += 1` in the amend step, made the loop guard eat a valid amended resend at the "
        "budget boundary — it computed a good body and then failed the turn on the stale "
        "pre-amendment error. Splitting the loop across files puts that shared state on a seam. "
        "The ceiling is its measured ratio, and it is NOT a licence to grow: any reduction that "
        "keeps the four budgets in one object is welcome and should lower this number.",
    ),
]

# Every exemption starts with a date, exactly as checks/rule-routing.sh requires of
# UNROUTED_ALLOWLIST — an undated one is how the next exemption hides.
EXCEPTION_JUSTIFICATION = re.compile(r"^\d{4}-\d{2}-\d{2}: \S")


def ceilings() -> dict[str, float]:
    return {path: ceiling for path, ceiling, _ in CEILING_EXCEPTIONS}


def exception_errors(rows: list[dict]) -> list[str]:
    """Structural faults in CEILING_EXCEPTIONS, which fail EVERY mode rather than just the gate.

    A malformed exception list is a defect whatever question the caller asked, and a list that is
    only validated on the gate path is a list that goes quiet the moment somebody runs --file.
    """
    known = {row["file"] for row in rows}
    seen: set[str] = set()
    errors = []
    for entry in CEILING_EXCEPTIONS:
        if len(entry) != 3:
            errors.append(f"{entry!r} is not (path, ceiling, justification) — three fields, always")
            continue
        path, _, why = entry
        if path in seen:
            errors.append(f"'{path}' is listed twice — one ceiling per file, or the stricter entry is dead text")
        seen.add(path)
        if not EXCEPTION_JUSTIFICATION.match(str(why).strip()):
            errors.append(
                f"'{path}' has no dated justification — every exception starts 'YYYY-MM-DD: <why>'. "
                "A blank or missing justification is a hard error, never a pass: an exemption "
                "nobody can evaluate is indistinguishable from one nobody should have granted."
            )
        if path not in known:
            errors.append(
                f"'{path}' is not a production .kt file any more — delete the entry. A stale exemption "
                "is an ungraded file one rename later, which is the failure it was written to prevent."
            )
    return errors


def report_exceptions(rows: list[dict]) -> None:
    """Print the active exceptions, separately from the passing files, on every run.

    An exception that is never read is never evaluated, and an allowlist nobody reads is a laundry.
    """
    if not CEILING_EXCEPTIONS:
        return
    by_file = {row["file"]: row for row in rows}
    print(f"\nCEILING EXCEPTIONS ({len(CEILING_EXCEPTIONS)}) — graded against their own ceiling, not --max-ratio:")
    for path, ceiling, why in CEILING_EXCEPTIONS:
        row = by_file[path]
        state = "OVER CEILING — the gate fails" if row["ratio"] > ceiling else "within ceiling"
        print(f"  {path}")
        print(f"    ratio {row['ratio']}  ceiling {ceiling}  C {row['C']}  [{state}]")
        print(textwrap.fill(why, width=96, initial_indent="    ", subsequent_indent="    "))


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
    # ONE DECLARATION, ONE BILL — a top-level type is charged by `concerns`, so it must not also be
    # charged as an export. Tested PER LINE rather than as len(exports) - len(types): TYPE_DECL
    # admits `private ` and EXPORT_DECL does not, so a file with a top-level private class would
    # otherwise be CREDITED 3 points for hiding a collaborator. See the module docstring.
    non_type_exports = [line for line in exports if not TYPE_DECL.match(line)]
    subsystems = {m.group(1) for line in lines if (m := SPLICE_IMPORT.match(line))}
    concerns = len(types) + len(subsystems)
    return {
        "file": rel,
        "package": ".".join(rel.split("/kotlin/")[-1].split("/")[:-1]).replace("/", "."),
        "logic": len(logic),
        "exports": len(exports),
        "exports_non_type": len(non_type_exports),
        "types": len(types),
        "subsystems": sorted(subsystems),
        "concerns": concerns,
        "C": round(0.5 * len(logic) + 3 * len(non_type_exports) + 8 * concerns, 1),
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
        # REPORT THE DIVISOR ACTUALLY USED, not just the raw median. When the floor bites, the two
        # differ and every reader who reproduces C / neighbour_median_C gets a different number than
        # the tool printed — app/cli/Command.kt reports a median of 1.0 against a ratio of 2.29,
        # which reads as 63x by hand. Ten of 306 files are floored today, and two of the eight HIGH
        # rows are HIGH *because of* the floor rather than because of their neighbours, which is a
        # materially different finding. A gate whose arithmetic cannot be reproduced from its own
        # output is not auditable.
        row["denominator"] = round(denominator, 1)
        row["denominator_floored"] = median < floor
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

    # Validated before any question is answered, and in EVERY mode: an exemption with no
    # justification, or one naming a file that no longer exists, is a defect regardless of what the
    # caller asked for. Exit 2, distinct from the gate's exit 1 — this is a broken instrument, not a
    # failing measurement.
    errors = exception_errors(rows)
    if errors:
        print(f"FAIL: CEILING_EXCEPTIONS is invalid ({len(errors)} problem(s)):", file=sys.stderr)
        for err in errors:
            print(f"  ✗ {err}", file=sys.stderr)
        return 2

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
        # An excepted file is graded against its own ceiling here too, and says so out loud. A
        # per-target check that passed silently under an exemption would be the quietest possible
        # place for one to hide.
        cap = ceilings().get(target["file"])
        if cap is not None:
            why = next(w for path, _, w in CEILING_EXCEPTIONS if path == target["file"])
            print(f"\nCEILING EXCEPTION — graded against ceiling {cap}, not --max-ratio:")
            print(textwrap.fill(why, width=96, initial_indent="  ", subsequent_indent="  "))
        # --max-ratio GATES a single file too. It used to be silently ignored here, so
        # `--file <a HIGH file> --max-ratio 1.8` printed the offending ratio and still exited 0 —
        # a per-target gate that could not fail is the same fake green this scan exists to find.
        # Per-target acceptance (HD-24) is exactly this call, so it has to be able to go red.
        if args.max_ratio is not None:
            limit = cap if cap is not None else args.max_ratio
            if target["ratio"] > limit:
                named = "its ceiling " if cap is not None else ""
                print(
                    f"FAIL: {target['file']} ratio {target['ratio']} is above {named}{limit}",
                    file=sys.stderr,
                )
                return 1
        return 0

    if args.json:
        print(json.dumps(rows if not args.top else rows[: args.top], indent=2))
    else:
        shown = rows[: args.top] if args.top else [r for r in rows if r["band"] != "low"]
        # `denom` is the divisor actually used, so ratio = C / denom always reproduces by hand; a
        # trailing * marks a row where the neighbourhood median was below the floor and the floor
        # was substituted, i.e. the score is graded against the tree's scale rather than against
        # that file's own neighbours.
        print(f"{'file':58} {'C':>7} {'denom':>7} {'ratio':>6}  band")
        for r in shown:
            print(
                f"{r['file'].replace('gateway/', '').replace('/src/main/kotlin/splice', '~')[:58]:58} "
                f"{r['C']:7.0f} {r['denominator']:6.0f}{'*' if r['denominator_floored'] else ' '} "
                f"{r['ratio']:6.2f}  {r['band']}"
            )
        high = [r for r in rows if r["band"] == "HIGH"]
        med = [r for r in rows if r["band"] == "moderate"]
        print(f"\n{len(rows)} files | HIGH {len(high)} | moderate {len(med)} | low {len(rows) - len(high) - len(med)}")
        report_exceptions(rows)

    if args.max_ratio is not None:
        # An excepted file is graded against its recorded ceiling; everything else against the gate.
        # A ceiling is not a blanket — a file that RISES above its own recorded number still fails.
        caps = ceilings()
        over = [r for r in rows if r["ratio"] > caps.get(r["file"], args.max_ratio)]
        if over:
            print(f"\nFAIL: {len(over)} file(s) above their limit (gate {args.max_ratio}):", file=sys.stderr)
            for r in over:
                cap = caps.get(r["file"])
                limit = f"ceiling {cap}" if cap is not None else f"max {args.max_ratio}"
                print(f"  {r['file']}  ratio={r['ratio']}  C={r['C']}  (over {limit})", file=sys.stderr)
            return 1
        print(
            f"\nOK: every file is at or below ratio {args.max_ratio}, "
            f"and all {len(CEILING_EXCEPTIONS)} exception(s) are within their own ceiling"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
