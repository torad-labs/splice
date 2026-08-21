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
                 every file whose ratio moved between a commit and the working tree, with its C
                 delta, its denominator delta, and the share of the move each of the two accounts
                 for. A landing note may not claim a ratio without it.

                 THE CAUSE COLUMN IS A SPLIT, NOT A BINARY (2026-08-18). Until this date the column
                 read `own` whenever C changed by ANY amount, and `neighbourhood` only when C was
                 byte-identical. ratio = C / denominator and BOTH factors move, so that test handed
                 every file carrying a one-line edit to `own` regardless of what its denominator
                 did — which is the one thing the column exists to tell apart. MEASURED on this
                 tree, 8c6912f -> working tree: app/cli/DoctorCommand.kt went 3.94 -> 8.10 while its
                 C moved 238.5 -> 239.0 (+0.5, one line) and its denominator HALVED, 60.5 -> 29.5.
                 The column called that `own`, crediting a 2x regression to half a point of code and
                 hiding the package split that caused it. KimiAuthProvider.kt was mislabelled the
                 same way (3.44 -> 2.64, C -1.5, denominator 37.8 -> 48.6, cause `own`). Every
                 landing note in this campaign cited the column in that state.

                 The replacement is a split, and it is EXACT rather than heuristic:

                     ratio_after/ratio_before = (C_after/C_before) * (denom_before/denom_after)

                 so in log space the two factors ADD and their shares of the move sum to 1. `own`
                 and `neighbourhood` name the factor holding at least CAUSE_DOMINANCE of it; `mixed`
                 means neither does, and the reader has to look at both numbers rather than be told
                 an answer the arithmetic does not support.

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
  - AND IT MAY NOT SIT ABOVE THE FILE (2026-08-18). The rise test above was, until this date, the
    ONLY ceiling comparison in this file, so the opposite direction — a ceiling RECORDED ABOVE the
    file's real ratio — failed nothing at all. That is the padding this mechanism exists to
    prevent, and it had already happened: UpstreamClient's ceiling sat at 6.14 against a file
    measuring 2.79, 3.35 points of unearned room, invisible to every mode. exception_errors() now
    fails when a recorded ceiling exceeds the measured ratio, with the number to record in the
    message — the same two-directional discipline the HIGH baseline already has, whose own SLACK
    text names this gap.
  - A BLANK OR MISSING JUSTIFICATION IS A HARD ERROR, not a pass. The justification is dated and
    mechanically shape-checked, exactly as UNROUTED_ALLOWLIST does it — an undated exemption is
    how the next one hides.
  - A STALE ENTRY IS A HARD ERROR. An exception naming a file that no longer exists fails the run
    rather than going quiet, because a silently-dead entry is an un-graded file one rename later.
  - ALWAYS LISTED. Every run prints the active exceptions separately from the passing files, so
    they are read rather than silently applied.

Nothing else belongs here. Every other file above 1.8 is work HD-25 is about to do, and putting
one on this list is the exact laundering the mechanism exists to prevent.

THE RATCHET (--ratchet, 2026-08-18). Until this date NOTHING RAN THIS FILE. It was absent from
checks/gate.sh, from every package.json script and from CI, so `npm run gate` printed GATE: PASS
while saying nothing about concentration and every ratio in the campaign was advisory — the same
defect class as the 2026-07-16 style pack that sat unrouted for a month while 336 top-level
functions accumulated under a green gate (see checks/rule-routing.sh, the wall written for that
scar). A wall nobody routes is a wall nobody has, and that was true of this oracle itself.

`--max-ratio 1.8` cannot be the gate leg today: it is red on 42 files, so landing it would mean
finishing HD-25 first or granting 42 exemptions, and an exemption pile is exactly the laundering
CEILING_EXCEPTIONS exists to prevent. What IS enforceable today is the DIRECTION. `--ratchet`
grades the measured HIGH-band census against RATCHET_MAX_HIGH below and fails when it RISES, so a
new god object, or an untouched file pushed into HIGH, is red on the commit that does it while the
existing debt stays visible in every run's output.

THE GATED CRITERION IS THE HIGH BAND, NOT THE FILE COUNT (corrected 2026-08-18). The first
revision of this ratchet also gated RATCHET_MAX_OVER, a COUNT of files whose ratio exceeds the
gate. That criterion forbids the work this oracle exists to drive, and the proof is in the ledger
(HD-25) rather than in an argument:

  THE CONTROL. Extracting CliStyle.kt alone out of app/cli/DoctorCommand.kt is green (42 over, 8
  HIGH). Extracting DoctorCheckTypes.kt alone is green (42, 8). Extracting BOTH — a relocation of
  four type declarations and seven string constants, zero behaviour and zero logic moved, a change
  that cannot by construction make the tree worse — measures 43 over and was RED, because an
  UNTOUCHED file (app/DeviceLoginFlow.kt) crossed at 1.81 with its C byte-identical, ΔC +0.0, its
  denominator falling 62.5 -> 60.5, cause `neighbourhood`, own share 0% — while DoctorCommand's own
  row fell 8.10 -> 6.59, ΔC -44.5, own share 100%. The count punished the second for the first. The
  ledger (HD-25) additionally records all 36 extraction subsets of that file searched through
  collect()/scan() with verbatim blocks; ZERO reached the recorded 42.

  WHY, and it is the denominator's documented property one paragraph up, not a tuning miss: the
  divisor is a file-scale ORDER STATISTIC, so ANY split moves files nobody touched. Splitting one
  god object at 8.10 into a composer at 1.45 plus five collaborators, two of them still above 1.8,
  RAISES the count while the worst row collapses and the HIGH band does not move at all. The count
  measures the file-size DISTRIBUTION; it does not measure concentration.

  MEASURED OVER THE WHOLE CAMPAIGN, 8c6912f -> b595c52, 21 commits touching gateway/, 172 files
  created, ~33 own-cause decompositions:

      over-1.8   ROSE 7  FELL 7   net 43 -> 42     (moved by one, in 14 moves)
      HIGH       ROSE 0  FELL 11  net 22 ->  8     (monotone — it never rose, not once)
      max ratio  ROSE 5  FELL 4   net 9.05 -> 8.10

  The count went red on decomposition commits seven times and finished where it started. The HIGH
  band never rose once. Eleven of the twelve HD-24 targets were HIGH at 8c6912f and NONE is HIGH
  now, each on real C reduction (ΔC -121.5 to -482.5, own share 77-100%); the twelfth,
  ChatRequestBuilder.kt, was moderate at 2.83 and is 0.71. Seventeen HIGH rows left the band in
  all. That is the criterion tracking the work.

  THE MAX RATIO WAS CONSIDERED AND REJECTED, on the same measurement. It does fall when a god
  object is decomposed (8.10 -> 6.59 on the control above, 8.10 -> 4.37 on the full split) — but
  ALL FIVE of its rises in this campaign carry own_share 0%, cause `neighbourhood`: 9.05 -> 9.33,
  9.33 -> 9.73, 9.73 -> 12.13, 6.74 -> 6.81 and 5.96 -> 8.10, not one of them a file gaining a
  line. Today's max holder, app/cli/DoctorCommand.kt, reads 3.94 -> 8.10 across the campaign with
  ΔC +0.5 and its denominator HALVED. A max arm would be the count's defect concentrated into one
  row: a single-file order statistic is strictly MORE drift-sensitive than a count of them. It is
  also a weak detector of the thing it would be added for — with the max at 8.10, a newly created
  file at 7.9, a worse god object than anything else now in the tree (next row: 4.37), passes a
  max arm untouched. The band catches that file; the max does not.

  - IT CANNOT BE SATISFIED BY WEAKENING. Lowering --max-ratio cannot help: HIGH is `ratio >= 3.0`
    and does not read --max-ratio at all. Raising the baseline is a dated edit to this file that
    reads in the diff as exactly what it is — a record that the tree got worse.
  - IT TIGHTENS ITSELF. A count that FALLS is also a hard error, with the remedy in the message.
    A baseline held above the measured count is unearned room for the next regression to hide in;
    that is how the UpstreamClient ceiling came to sit at 6.14 against a file measuring 2.79.
  - KNOWN LIMIT, stated here rather than discovered later: HIGH is still a COUNT. A commit that
    retires one god object and creates another nets to zero and passes — the stashed DoctorCommand
    split does exactly that (DoctorCommand leaves at 1.45, DoctorAuth enters at 3.22). The ratchet
    has no reference commit, so it cannot filter by cause; `--since <ref>` names files, causes and
    the ΔC/Δdenom split, and is the diff-time instrument. The band is the standing floor under it.
  - THE OVER-GATE COUNT IS STILL REPORTED, on every run, with its worst offenders by name — it is
    the campaign's remaining debt and it stays visible. It is REPORTED, NOT GATED, for the reason
    measured above: it moves on splits that touch nothing, so gating it penalises decomposition.

USAGE
    python3 checks/concentration.py                      # full table, exit 0
    python3 checks/concentration.py --top 15             # worst 15 only
    python3 checks/concentration.py --max-ratio 1.8      # gate: non-zero exit if any file is above
    python3 checks/concentration.py --ratchet --max-ratio 1.8   # gate leg: band HIGH may not move
    python3 checks/concentration.py --file <path>        # one file, with its neighbour list
    python3 checks/concentration.py --since <ref>        # what moved since <ref>, and why
    python3 checks/concentration.py --json               # machine-readable
"""
from __future__ import annotations

import argparse
import io
import json
import math
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

# The share of a ratio move one factor must carry before `--since` will name it the cause. ratio =
# C / denominator, so in log space the two factors add and their shares sum to 1; a factor holding
# two thirds or more IS the explanation, and anything between the thirds is `mixed` — a row the
# reader has to open rather than a label that decides for them. Stated as a visible constant because
# a dominance rule nobody can read is the same defect as the binary it replaced.
CAUSE_DOMINANCE = 2 / 3

# --------------------------------------------------------------------------------------------
# The files this tree provably cannot bring under the gate by refactoring. Format:
# (path, ceiling ratio, "YYYY-MM-DD: why"), the same shape as UNROUTED_ALLOWLIST in
# checks/rule-routing.sh. Read CEILING EXCEPTIONS in the module docstring before adding one; the
# short version is that an entry here is a CEILING that still fails when breached, its
# justification is mechanically required, a stale entry is a hard error, and every run prints the
# list. Empty after HD-25: UpstreamClient measured 1.78 (C=91.0 / d=51.0) which is under
# --max-ratio 1.8, so the leftover 3.1 ceiling was padded. A second entry added to make a
# red gate green is the laundering this list exists to prevent.
CEILING_EXCEPTIONS: list[tuple[str, float, str]] = []

# Every exemption starts with a date, exactly as checks/rule-routing.sh requires of
# UNROUTED_ALLOWLIST — an undated one is how the next exemption hides.
EXCEPTION_JUSTIFICATION = re.compile(r"^\d{4}-\d{2}-\d{2}: \S")

# --------------------------------------------------------------------------------------------
# THE RATCHET BASELINE — the census this tree is held to, MEASURED, never estimated. Read THE
# RATCHET in the module docstring first. The count EXCLUDES the ceiling-excepted files above,
# which are graded against their own recorded ceilings by the same leg rather than counted twice.
# Moving this number is a deliberate, dated edit: UP records that the tree got worse, DOWN is the
# remedy the gate itself prints when work lands.
#
# THERE IS DELIBERATELY NO RATCHET_MAX_OVER. It existed until 2026-08-18 and gated the count of
# files above --max-ratio; it was removed, not merely stopped being read, because a baseline
# nobody grades is the stale number this campaign has now been bitten by twice (the 6.14
# UpstreamClient ceiling, the pre-decomposition AnthropicRequest ceiling). The count is measured
# and printed on every run as DEBT. See THE GATED CRITERION IS THE HIGH BAND in the docstring for
# the control that forced the change.
RATCHET_RECORDED = "2026-08-19"
RATCHET_MAX_HIGH = 0  # files in band HIGH  (re-measured 2026-08-19 after SseReader same-package split: 1 -> 0; HIGH band empty)


def ceilings() -> dict[str, float]:
    return {path: ceiling for path, ceiling, _ in CEILING_EXCEPTIONS}


def exception_errors(rows: list[dict]) -> list[str]:
    """Structural faults in CEILING_EXCEPTIONS, which fail EVERY mode rather than just the gate.

    A malformed exception list is a defect whatever question the caller asked, and a list that is
    only validated on the gate path is a list that goes quiet the moment somebody runs --file.
    """
    known = {row["file"]: row for row in rows}
    seen: set[str] = set()
    errors = []
    for entry in CEILING_EXCEPTIONS:
        if len(entry) != 3:
            errors.append(f"{entry!r} is not (path, ceiling, justification) — three fields, always")
            continue
        path, ceiling, why = entry
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
            continue
        # THE CEILING MAY NOT SIT ABOVE THE FILE (2026-08-18). Every other ceiling comparison in
        # this file is `row["ratio"] > ceiling`, so a ceiling that RISES above its file fails and a
        # ceiling RECORDED ABOVE the file's real ratio failed NOTHING — the padding direction was
        # unguarded in every mode. That is not hypothetical: this list carried UpstreamClient at
        # 6.14 against a file measuring 2.79, i.e. 3.35 points of room the file never earned, and
        # nothing in the repo could see it. It is the same defect the HIGH baseline's SLACK arm
        # already treats as a hard error, and that arm's own message names this exact gap ("the
        # same defect as a ceiling recorded above its file's measured ratio").
        #
        # DELIBERATELY ONE-DIRECTIONAL, and this is the split main() already draws between exit 2
        # and exit 1. A padded ceiling is a stale LIST — nobody's code moved, the recorded number is
        # simply wrong — so it is a broken instrument and fails every mode here. A ceiling BREACHED
        # from below is a failing MEASUREMENT: the file or its neighbourhood moved, and ratchet(),
        # --file and the --max-ratio path already report it at exit 1 with the cause, the C/denom
        # split and the --since remedy attached. Re-reporting that as "CEILING_EXCEPTIONS is
        # invalid" would call a real regression a malformed list and orphan the three branches that
        # say it properly.
        measured = known[path]["ratio"]
        if round(float(ceiling), 2) > measured:
            errors.append(
                f"'{path}' has a PADDED CEILING: recorded {ceiling}, file measures {measured} "
                f"(C={known[path]['C']}, denominator={known[path]['denominator']}). Record "
                f"{measured}. A ceiling held above its file's measured ratio is "
                f"{round(round(float(ceiling), 2) - measured, 2)} points of unearned room for the "
                "next regression to hide in, and on its own it fails nothing — the gate only ever "
                "asked whether the ratio ROSE above the ceiling. A ceiling freezes a MEASURED "
                "state; a number nobody re-measured is an exemption, which is the laundering this "
                "list exists to prevent."
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


def ratchet(rows: list[dict], max_ratio: float) -> int:
    """The enforceable half of the 1.8 gate: the HIGH band may not RISE, and may not silently FALL.

    Prints the baseline, the measured band, and the standing over-gate debt on every run — a
    ratchet whose output shows only its own verdict hides the files it is deliberately not gating.
    See THE RATCHET, and THE GATED CRITERION IS THE HIGH BAND, in the module docstring: the count
    of files above --max-ratio is REPORTED here and is NOT the criterion, because it rises on
    splits that touch nothing and so forbids the decomposition this oracle exists to drive.
    """
    caps = ceilings()
    graded = [r for r in rows if r["file"] not in caps]
    over = [r for r in graded if r["ratio"] > max_ratio]
    high = [r for r in graded if r["band"] == "HIGH"]

    print(f"CONCENTRATION RATCHET — baseline recorded {RATCHET_RECORDED}, gate ratio {max_ratio}")
    print(f"  {'band HIGH':<22} baseline {RATCHET_MAX_HIGH:>3}   measured {len(high):>3}   [GATED]")
    print(f"  {f'files over {max_ratio}':<22} {'':>12} measured {len(over):>3}   [reported, not gated]")
    by_file = {row["file"]: row for row in rows}
    for path, ceiling, _ in CEILING_EXCEPTIONS:
        row = by_file[path]
        state = "OVER CEILING" if row["ratio"] > ceiling else "within ceiling"
        print(f"  ceiling exception  ratio {row['ratio']:5.2f}  ceiling {ceiling:<5} [{state}]  {path}")
    if high:
        # The gated census, by name. A gate whose own output cannot be checked against the number
        # it enforces is not auditable — the same rule that makes --max-ratio mandatory below.
        print(
            f"\n  GATED — the {len(high)} file(s) in band HIGH:\n"
            "        " + " | ".join(f"{r['file'].rsplit('/', 1)[-1]} {r['ratio']}" for r in high)
        )
    if over:
        print(
            f"\n  DEBT: {len(over)} file(s) sit above {max_ratio}. This count is REPORTED, NOT GATED, and "
            f"neither is their ratio."
        )
        print(
            textwrap.fill(
                "WHY NOT GATED: the denominator is a file-scale order statistic, so ANY split moves files "
                "nobody touched — splitting one god object into a composer plus collaborators RAISES this "
                "count while the worst row collapses and HIGH does not move. Measured over this campaign "
                "it rose 7 times and fell 7, net 43 -> 42, while HIGH went 22 -> 8 without ever rising. "
                "Gating it penalises decomposition; see the module docstring.",
                width=96,
                initial_indent="        ",
                subsequent_indent="        ",
            )
        )
        print(
            f"        worst: " + " | ".join(f"{r['file'].rsplit('/', 1)[-1]} {r['ratio']}" for r in over[:5]) + "\n"
            f"        full list `python3 checks/concentration.py --top {len(over)}`; every one is HD-25's "
            f"work, not an exemption.\n"
            f"        a ceiling exception's justification reads with `--file <path>`."
        )
    else:
        # The state this ratchet exists to reach: nothing above the gate but the excepted files, so
        # the direction-only leg has no debt left to hide and `--max-ratio` can replace it outright.
        print(
            f"\n  NO DEBT: nothing outside the {len(CEILING_EXCEPTIONS)} ceiling exception(s) is above "
            f"{max_ratio}.\n"
            f"        Retire this leg: make it `--max-ratio {max_ratio}` (the hard gate) and delete "
            f"RATCHET_MAX_HIGH / RATCHET_RECORDED."
        )

    problems: list[str] = []
    # ONE GATED NUMBER: the HIGH band. The count of files over --max-ratio is printed above as debt
    # and is deliberately absent from this loop — see the docstring for the pure-relocation control
    # that proved a count criterion red on a change that cannot make the tree worse.
    if len(high) > RATCHET_MAX_HIGH:
        problems.append(
            f"REGRESSION: band HIGH rose {RATCHET_MAX_HIGH} -> {len(high)}. A god object appeared that "
            f"nothing recorded. Run `python3 checks/concentration.py --since HEAD --max-ratio {max_ratio}`: "
            f"cause `own` is code in this change, cause `neighbourhood` is a denominator that moved under "
            f"the file, and the ΔC / Δdenom columns show the split the label was taken from. Fix the file — "
            f"raising RATCHET_MAX_HIGH is a dated edit recording that the tree got worse."
        )
    elif len(high) < RATCHET_MAX_HIGH:
        problems.append(
            f"SLACK: band HIGH fell {RATCHET_MAX_HIGH} -> {len(high)}, and the baseline still claims "
            f"{RATCHET_MAX_HIGH}. Set RATCHET_MAX_HIGH = {len(high)} and re-date RATCHET_RECORDED in "
            f"checks/concentration.py. A baseline held above the measured count is unearned room for the "
            f"next regression to hide in — the same defect as a ceiling recorded above its file's measured "
            f"ratio."
        )
    for path, ceiling, _ in CEILING_EXCEPTIONS:
        row = by_file[path]
        if row["ratio"] > ceiling:
            problems.append(
                f"CEILING BREACHED: {path} ratio {row['ratio']} is above its recorded ceiling {ceiling} "
                f"(C={row['C']}). A ceiling freezes a known state; it does not stop watching."
            )

    if problems:
        print(f"\nFAIL: concentration ratchet — {len(problems)} problem(s):", file=sys.stderr)
        for problem in problems:
            print(textwrap.fill(problem, width=96, initial_indent="  ✗ ", subsequent_indent="    "), file=sys.stderr)
        return 1
    print(
        f"\nOK: concentration ratchet holds — band HIGH is exactly the {RATCHET_RECORDED} baseline "
        f"({RATCHET_MAX_HIGH}), and all {len(CEILING_EXCEPTIONS)} exception(s) are within their own ceiling"
    )
    return 0


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


def cause_of(was: dict, now: dict) -> tuple[str, float | None]:
    """Split a ratio move into its two factors and name the one that DOMINATES.

    ratio = C / denominator, so the move is exactly multiplicative:

        ratio_after / ratio_before = (C_after / C_before) * (denominator_before / denominator_after)

    Taking logs turns that product into a sum, so "how much of this move is the file's own density
    and how much is its neighbourhood" is a division, not a judgement call. Returns the cause and
    the share of the move attributable to the file's own C (the neighbourhood share is 1 - it).

    WHY NOT `own if C changed`, which this replaces: that test fires on ANY non-zero C delta, so a
    half-point edit outranks a denominator that has halved. See THE CAUSE COLUMN IS A SPLIT in the
    module docstring for the two measured misattributions that forced the change.
    """
    factors = (was["C"], now["C"], was["denominator"], now["denominator"])
    if min(factors) <= 0:
        # No log to take. Name whichever factor moved and return no share rather than invent one —
        # a fabricated split is worse than the binary this replaces.
        own_moved = was["C"] != now["C"]
        neighbourhood_moved = was["denominator"] != now["denominator"]
        if own_moved and not neighbourhood_moved:
            return "own", None
        if neighbourhood_moved and not own_moved:
            return "neighbourhood", None
        return "mixed", None
    own = abs(math.log(now["C"] / was["C"]))
    neighbourhood = abs(math.log(was["denominator"] / now["denominator"]))
    if own + neighbourhood == 0:
        return "mixed", None
    share = own / (own + neighbourhood)
    if share >= CAUSE_DOMINANCE:
        return "own", share
    if share <= 1 - CAUSE_DOMINANCE:
        return "neighbourhood", share
    return "mixed", share


def movement(ref: str, rows: list[dict]) -> list[dict]:
    """Every file whose ratio moved since `ref`, with the SPLIT that caused it.

    Each row carries the C delta, the denominator delta, and `own_share` — the fraction of the (log)
    ratio move the file's own density accounts for. `cause` names the factor holding at least
    CAUSE_DOMINANCE of the move, or `mixed` when neither does. See cause_of.
    """
    before = {r["file"]: r for r in scan(collect_ref(ref))}
    after = {r["file"]: r for r in rows}
    moved = []
    for name in sorted(set(before) & set(after)):
        was, now = before[name], after[name]
        if was["ratio"] == now["ratio"]:
            continue
        cause, share = cause_of(was, now)
        moved.append(
            {
                "file": name,
                "cause": cause,
                "own_share": None if share is None else round(share, 3),
                "neighbourhood_share": None if share is None else round(1 - share, 3),
                "C_before": was["C"],
                "C_after": now["C"],
                "C_delta": round(now["C"] - was["C"], 1),
                "denominator_before": was["denominator"],
                "denominator_after": now["denominator"],
                "denominator_delta": round(now["denominator"] - was["denominator"], 1),
                "ratio_before": was["ratio"],
                "ratio_after": now["ratio"],
                "band_before": was["band"],
                "band_after": now["band"],
            }
        )
    return moved


def report_movement(ref: str, moved: list[dict], max_ratio: float | None) -> None:
    # Both deltas are printed because the cause is a SPLIT of them; a label with the numbers it was
    # derived from withheld is the binary this replaced, wearing a longer word. `cause` stays the
    # last field on the line so `awk '{print $NF}'` and `grep 'neighbourhood$'` still work, and
    # "neighbourhood" contains no "own" substring, so the two grep cleanly.
    print(f"{'file':52} {'ratio':>14}  {'ΔC':>8} {'Δdenom':>8}  {'band':>18}  {'own%':>5}  cause")
    for m in moved:
        share = "  n/a" if m["own_share"] is None else f"{m['own_share']:>5.0%}"
        print(
            f"{m['file'].replace('gateway/', '').replace('/src/main/kotlin/splice', '~')[:52]:52} "
            f"{m['ratio_before']:6.2f} ->{m['ratio_after']:6.2f}  "
            f"{m['C_delta']:+8.1f} {m['denominator_delta']:+8.1f}  "
            f"{m['band_before']:>8} ->{m['band_after']:>8}  {share}  {m['cause']}"
        )
    counts = {c: sum(1 for m in moved if m["cause"] == c) for c in ("own", "neighbourhood", "mixed")}
    print(
        f"\n{len(moved)} file(s) moved since {ref} | own {counts['own']} "
        f"| neighbourhood {counts['neighbourhood']} | mixed {counts['mixed']} "
        f"(dominance threshold {CAUSE_DOMINANCE:.0%} of the log move)"
    )
    if max_ratio is not None:
        drift = [m for m in moved if m["cause"] == "neighbourhood"]
        crossed = [m for m in drift if (m["ratio_before"] > max_ratio) != (m["ratio_after"] > max_ratio)]
        if crossed:
            print(
                f"\nWARNING: {len(crossed)} file(s) crossed ratio {max_ratio} on a move their "
                f"DENOMINATOR dominates — that number belongs to whoever split a neighbouring "
                f"package, not to this file's density:",
                file=sys.stderr,
            )
            for m in crossed:
                print(
                    f"  {m['file']}  ratio {m['ratio_before']} -> {m['ratio_after']}  "
                    f"C {m['C_before']} -> {m['C_after']} ({m['C_delta']:+})  "
                    f"denominator {m['denominator_before']} -> {m['denominator_after']} "
                    f"({m['denominator_delta']:+})",
                    file=sys.stderr,
                )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--top", type=int, default=0, help="show only the worst N")
    ap.add_argument("--max-ratio", type=float, help="gate: fail if any file exceeds this ratio")
    ap.add_argument(
        "--ratchet",
        action="store_true",
        help="gate leg: fail if the count of band-HIGH files moves off the recorded baseline (rise = "
        "regression, fall = stale baseline), or a ceiling exception is breached. The count of files "
        "over --max-ratio is reported as debt, NOT gated. Requires --max-ratio.",
    )
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

    if args.ratchet:
        if args.max_ratio is None:
            print(
                "--ratchet needs --max-ratio: the leg reports the standing debt above a stated threshold and "
                "grades the ceiling exceptions against it, and a threshold that is not stated at the call "
                "site is not auditable from the gate's own output.",
                file=sys.stderr,
            )
            return 2
        return ratchet(rows, args.max_ratio)

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
