#!/usr/bin/env bun
/** Responsibility-concentration scan — the oracle for the decomposition campaign.
 *
 * WHY THIS EXISTS: the style migration made the tree compliant (no top-level functions, no companion
 * objects) WITHOUT making it decomposed. The 14-function-per-class ceiling pushed collaborators into
 * existence inside the files that were already too big, so concentration moved sideways rather than
 * down: TurnDriver ended up declaring 12 types in one file, Daemon 9 types importing 32 subsystems.
 * A per-class function count cannot see that. This can.
 *
 * THE METRIC. For each production .kt file:
 *
 *     C = 0.5*logic_lines + 3*non_type_exports + 8*concerns
 *
 *   logic_lines  — non-blank, non-comment, non-import, non-package
 *   non_type_exports
 *                — top-level fun/val/var declarations. A top-level class/interface/object is NOT
 *                  counted here, because `concerns` below already counts it. See ONE DECLARATION,
 *                  ONE BILL.
 *   concerns     — declared types in the file PLUS distinct splice.* subsystems it imports.
 *                  This is the term that catches the failure above: splitting one god class into
 *                  six collaborators in the same file RAISES concerns rather than lowering it.
 *
 *                  ONE DECLARATION, ONE BILL (2026-08-18). The original `3*exports + 8*concerns`
 *                  charged every declared type TWICE — once as an export, once as a concern — so a
 *                  type cost 11 points, the equivalent of 22 logic lines, and a one-line
 *                  `data class TextBlock(val text: String)` scored as 22 lines of orchestration. On
 *                  core/wire/AnthropicRequest.kt (19 types in 133 lines) that double bill was 57 of
 *                  275.5 points. Measured blast radius of the correction across this tree: 8 band
 *                  flips, 2 files crossing 1.8 (dialect/passthrough/PassthroughProvider.kt
 *                  1.63 -> 1.83, gateway/round/ReanchorRunner.kt 1.70 -> 1.89), tree census
 *                  HIGH 8 -> 12, moderate 27 -> 27, over-1.8 35 -> 37, and all twelve HD-24 targets
 *                  stay band low with the worst at 1.77. It is a CORRECTNESS fix, not a
 *                  calibration: it says nothing about what a declaration should cost, only that it
 *                  is charged once. Whether a declaration should cost 8 at all remains open.
 *
 *                  SUBTRACTED PER LINE, never as `exports - types`. TYPE_DECL admits `private ` and
 *                  EXPORT_DECL does not, so the 14 files here that carry a top-level private class
 *                  hold a concern that was never an export — `3*(exports - types)` hands each of
 *                  them a -3 CREDIT, paying a file for hiding a collaborator behind `private`,
 *                  which is precisely how a god file's collaborators get born. Measured: 14 files
 *                  differ, min(exports - types) = -1. Per-line subtraction cannot go negative and
 *                  has the smaller blast radius of the two (8 band flips against 9).
 *
 * Then, per file, the gradient against its neighbours:
 *
 *     ratio = C / median(median C of each neighbouring PACKAGE)
 *
 *   neighbours   — the packages this file imports from, plus the packages that import this file's
 *                  package. A file is only a god object RELATIVE to what it sits next to; a dense
 *                  domain-type module with thin neighbours is fine, a dense orchestrator surrounded
 *                  by thin helpers is not.
 *
 *                  ONE VOTE PER PACKAGE, never one per file. A median taken over neighbour FILES
 *                  gives a package as many votes as it has files, so decomposing one importer into
 *                  twelve siblings multiplies that package's vote twelvefold and drags the median
 *                  down — this campaign's own edits then inflate the ratio of files nobody touched.
 *                  Measured on 647ed02 (ResponsesRequestBuilder -> 12 siblings): under the file-vote
 *                  denominator core/wire/AnthropicRequest.kt read 1.97 before and 5.57 after, band
 *                  moderate -> HIGH, without one line of it changing. Same reason the global median
 *                  below is taken over packages.
 *
 *                  WHAT THE PACKAGE VOTE DOES NOT BUY, stated because an earlier revision of this
 *                  docstring claimed it did: it removes the vote-COUNT multiplication, it does NOT
 *                  make the oracle invariant to a WITHIN-package split. A package votes with the
 *                  median C of its own files, and redistributing that package's content across more
 *                  files moves that median. Measured on 06002e6 (ChatRequestBuilder -> 5 same-package
 *                  siblings): splice.dialect.chat went from files [20.5, 180.0, 216.5] to
 *                  [17.0, 19.5, 20.5, 28.0, 44.5, 47.0, 105.0, 216.5], median C 180.0 -> 36.25, and
 *                  47 files nobody edited moved — core/wire/AnthropicRequest.kt 3.34 -> 6.19 with C
 *                  unchanged at 275.5, app/Daemon.kt 7.93 -> 8.95, spi/UpstreamClient.kt 4.98 -> 5.20,
 *                  app/cli/Command.kt 1.95 -> 2.00 crossing low -> moderate. No file crossed the 1.8
 *                  gate that time; nothing in the metric guarantees the next one will not.
 *
 *                  This is not a bug with a local repair. ANY file-scale denominator is a statistic of
 *                  the file-size distribution, and splitting a file changes that distribution, so no
 *                  choice of order statistic is invariant. The only partition-free denominator is the
 *                  neighbouring package's AGGREGATE C, and it was measured rather than assumed: it is
 *                  invariant (worst untouched drift 0.08 across both landings, zero band flips) but it
 *                  re-scopes the campaign — at every scale constant tried it flips the 1.8 verdict of
 *                  19-29 untouched files (over-1.8 40 -> 4..27, HIGH 22 -> 0..12), which would record 8
 *                  of the 12 HD-24 targets as decomposed without a line being touched. Adopting it is a
 *                  band-calibration decision for the orchestrator, not a property this script may change
 *                  on its own.
 *
 *                  So the drift is not eliminated here, it is made non-silent: `--since <ref>` reports
 *                  every file whose ratio moved between a commit and the working tree, with its C
 *                  delta, its denominator delta, and the share of the move each of the two accounts
 *                  for. A landing note may not claim a ratio without it.
 *
 *                  THE CAUSE COLUMN IS A SPLIT, NOT A BINARY (2026-08-18). Until this date the column
 *                  read `own` whenever C changed by ANY amount, and `neighbourhood` only when C was
 *                  byte-identical. ratio = C / denominator and BOTH factors move, so that test handed
 *                  every file carrying a one-line edit to `own` regardless of what its denominator
 *                  did — which is the one thing the column exists to tell apart. MEASURED on this
 *                  tree, 8c6912f -> working tree: app/cli/DoctorCommand.kt went 3.94 -> 8.10 while its
 *                  C moved 238.5 -> 239.0 (+0.5, one line) and its denominator HALVED, 60.5 -> 29.5.
 *                  The column called that `own`, crediting a 2x regression to half a point of code and
 *                  hiding the package split that caused it. KimiAuthProvider.kt was mislabelled the
 *                  same way (3.44 -> 2.64, C -1.5, denominator 37.8 -> 48.6, cause `own`). Every
 *                  landing note in this campaign cited the column in that state.
 *
 *                  The replacement is a split, and it is EXACT rather than heuristic:
 *
 *                      ratio_after/ratio_before = (C_after/C_before) * (denom_before/denom_after)
 *
 *                  so in log space the two factors ADD and their shares of the move sum to 1. `own`
 *                  and `neighbourhood` name the factor holding at least CAUSE_DOMINANCE of it; `mixed`
 *                  means neither does, and the reader has to look at both numbers rather than be told
 *                  an answer the arithmetic does not support.
 *
 *   BANDS:  ratio < 1.8 low  |  1.8-3.0 moderate  |  >= 3.0 HIGH (god object)
 *
 * CEILING EXCEPTIONS. Two files in this tree provably cannot reach 1.8 by refactoring, and a gate
 * that pretends otherwise buys its green by lying about what is reachable. They are named in
 * CEILING_EXCEPTIONS below with a ceiling and a justification a reader can evaluate, in the idiom
 * this repo already uses twice — `nonLibrary` in splice.module-law.gradle.kts and UNROUTED_ALLOWLIST
 * in checks/rule-routing.sh. Four properties, each of which is what stops the list becoming a
 * laundry:
 *
 *   - A CEILING, NOT A BLANKET. An excepted file is graded against its own recorded ceiling instead
 *     of --max-ratio. If its ratio RISES above that ceiling the gate still fails. An exception
 *     freezes a known state; it does not stop watching.
 *   - AND IT MAY NOT SIT ABOVE THE FILE (2026-08-18). The rise test above was, until this date, the
 *     ONLY ceiling comparison in this file, so the opposite direction — a ceiling RECORDED ABOVE the
 *     file's real ratio — failed nothing at all. That is the padding this mechanism exists to
 *     prevent, and it had already happened: UpstreamClient's ceiling sat at 6.14 against a file
 *     measuring 2.79, 3.35 points of unearned room, invisible to every mode. exceptionErrors() now
 *     fails when a recorded ceiling exceeds the measured ratio, with the number to record in the
 *     message — the same two-directional discipline the HIGH baseline already has, whose own SLACK
 *     text names this gap.
 *   - A BLANK OR MISSING JUSTIFICATION IS A HARD ERROR, not a pass. The justification is dated and
 *     mechanically shape-checked, exactly as UNROUTED_ALLOWLIST does it — an undated exemption is
 *     how the next one hides.
 *   - A STALE ENTRY IS A HARD ERROR. An exception naming a file that no longer exists fails the run
 *     rather than going quiet, because a silently-dead entry is an un-graded file one rename later.
 *   - ALWAYS LISTED. Every run prints the active exceptions separately from the passing files, so
 *     they are read rather than silently applied.
 *
 * Nothing else belongs here. Every other file above 1.8 is work HD-25 is about to do, and putting
 * one on this list is the exact laundering the mechanism exists to prevent.
 *
 * THE RATCHET (--ratchet, 2026-08-18). Until this date NOTHING RAN THIS FILE. It was absent from
 * checks/gate.sh, from every package.json script and from CI, so `npm run gate` printed GATE: PASS
 * while saying nothing about concentration and every ratio in the campaign was advisory — the same
 * defect class as the 2026-07-16 style pack that sat unrouted for a month while 336 top-level
 * functions accumulated under a green gate (see checks/rule-routing.sh, the wall written for that
 * scar). A wall nobody routes is a wall nobody has, and that was true of this oracle itself.
 *
 * `--max-ratio 1.8` cannot be the gate leg today: it is red on 42 files, so landing it would mean
 * finishing HD-25 first or granting 42 exemptions, and an exemption pile is exactly the laundering
 * CEILING_EXCEPTIONS exists to prevent. What IS enforceable today is the DIRECTION. `--ratchet`
 * grades the measured HIGH-band census against RATCHET_MAX_HIGH below and fails when it RISES, so a
 * new god object, or an untouched file pushed into HIGH, is red on the commit that does it while the
 * existing debt stays visible in every run's output.
 *
 * THE GATED CRITERION IS THE HIGH BAND, NOT THE FILE COUNT (corrected 2026-08-18). The first
 * revision of this ratchet also gated RATCHET_MAX_OVER, a COUNT of files whose ratio exceeds the
 * gate. That criterion forbids the work this oracle exists to drive, and the proof is in the ledger
 * (HD-25) rather than in an argument:
 *
 *   THE CONTROL. Extracting CliStyle.kt alone out of app/cli/DoctorCommand.kt is green (42 over, 8
 *   HIGH). Extracting DoctorCheckTypes.kt alone is green (42, 8). Extracting BOTH — a relocation of
 *   four type declarations and seven string constants, zero behaviour and zero logic moved, a change
 *   that cannot by construction make the tree worse — measures 43 over and was RED, because an
 *   UNTOUCHED file (app/DeviceLoginFlow.kt) crossed at 1.81 with its C byte-identical, ΔC +0.0, its
 *   denominator falling 62.5 -> 60.5, cause `neighbourhood`, own share 0% — while DoctorCommand's own
 *   row fell 8.10 -> 6.59, ΔC -44.5, own share 100%. The count punished the second for the first. The
 *   ledger (HD-25) additionally records all 36 extraction subsets of that file searched through
 *   collect()/scan() with verbatim blocks; ZERO reached the recorded 42.
 *
 *   WHY, and it is the denominator's documented property one paragraph up, not a tuning miss: the
 *   divisor is a file-scale ORDER STATISTIC, so ANY split moves files nobody touched. Splitting one
 *   god object at 8.10 into a composer at 1.45 plus five collaborators, two of them still above 1.8,
 *   RAISES the count while the worst row collapses and the HIGH band does not move at all. The count
 *   measures the file-size DISTRIBUTION; it does not measure concentration.
 *
 *   MEASURED OVER THE WHOLE CAMPAIGN, 8c6912f -> b595c52, 21 commits touching gateway/, 172 files
 *   created, ~33 own-cause decompositions:
 *
 *       over-1.8   ROSE 7  FELL 7   net 43 -> 42     (moved by one, in 14 moves)
 *       HIGH       ROSE 0  FELL 11  net 22 ->  8     (monotone — it never rose, not once)
 *       max ratio  ROSE 5  FELL 4   net 9.05 -> 8.10
 *
 *   The count went red on decomposition commits seven times and finished where it started. The HIGH
 *   band never rose once. Eleven of the twelve HD-24 targets were HIGH at 8c6912f and NONE is HIGH
 *   now, each on real C reduction (ΔC -121.5 to -482.5, own share 77-100%); the twelfth,
 *   ChatRequestBuilder.kt, was moderate at 2.83 and is 0.71. Seventeen HIGH rows left the band in
 *   all. That is the criterion tracking the work.
 *
 *   THE MAX RATIO WAS CONSIDERED AND REJECTED, on the same measurement. It does fall when a god
 *   object is decomposed (8.10 -> 6.59 on the control above, 8.10 -> 4.37 on the full split) — but
 *   ALL FIVE of its rises in this campaign carry own_share 0%, cause `neighbourhood`: 9.05 -> 9.33,
 *   9.33 -> 9.73, 9.73 -> 12.13, 6.74 -> 6.81 and 5.96 -> 8.10, not one of them a file gaining a
 *   line. Today's max holder, app/cli/DoctorCommand.kt, reads 3.94 -> 8.10 across the campaign with
 *   ΔC +0.5 and its denominator HALVED. A max arm would be the count's defect concentrated into one
 *   row: a single-file order statistic is strictly MORE drift-sensitive than a count of them. It is
 *   also a weak detector of the thing it would be added for — with the max at 8.10, a newly created
 *   file at 7.9, a worse god object than anything else now in the tree (next row: 4.37), passes a
 *   max arm untouched. The band catches that file; the max does not.
 *
 *   - IT CANNOT BE SATISFIED BY WEAKENING. Lowering --max-ratio cannot help: HIGH is `ratio >= 3.0`
 *     and does not read --max-ratio at all. Raising the baseline is a dated edit to this file that
 *     reads in the diff as exactly what it is — a record that the tree got worse.
 *   - IT TIGHTENS ITSELF. A count that FALLS is also a hard error, with the remedy in the message.
 *     A baseline held above the measured count is unearned room for the next regression to hide in;
 *     that is how the UpstreamClient ceiling came to sit at 6.14 against a file measuring 2.79.
 *   - KNOWN LIMIT, stated here rather than discovered later: HIGH is still a COUNT. A commit that
 *     retires one god object and creates another nets to zero and passes — the stashed DoctorCommand
 *     split does exactly that (DoctorCommand leaves at 1.45, DoctorAuth enters at 3.22). The ratchet
 *     has no reference commit, so it cannot filter by cause; `--since <ref>` names files, causes and
 *     the ΔC/Δdenom split, and is the diff-time instrument. The band is the standing floor under it.
 *   - THE OVER-GATE COUNT IS STILL REPORTED, on every run, with its worst offenders by name — it is
 *     the campaign's remaining debt and it stays visible. It is REPORTED, NOT GATED, for the reason
 *     measured above: it moves on splits that touch nothing, so gating it penalises decomposition.
 *
 * THE PACKAGE SCALE (V4-93, 2026-09-17). Every number above is per FILE, and the tree has learned
 * to pass it. The decomposition campaign moved concentration DOWN a level: a god object taken apart
 * into a composer plus eleven collaborators lowers every file's C and leaves fifty-one files in one
 * package, which is the same responsibility clump one directory up and is invisible to a file-scale
 * oracle by construction. Measured 2026-09-17 on this tree, with the file census green (band HIGH
 * 0): splice.app.cli holds 84 of 631 production files and 5050.0 of the tree's C, splice.dialect
 * .responses 80, splice.gateway.head 51 of the 99 files in :daemon-head. `head/` is the audit's own
 * finding (A row 5) and it is not even the worst one.
 *
 * So the census gains a PACKAGE row — files per package and summed C per package, printed on every
 * run — and the ratchet gains ONE gated number: the WORST package's file count, held exactly the way
 * RATCHET_MAX_HIGH is held. A rise is a regression (a package absorbed another file); a FALL is a
 * stale baseline (the remedy is printed, and a baseline above the measurement is unearned room, the
 * 6.14-UpstreamClient defect this file already records twice).
 *
 * WHY THE WORST PACKAGE'S FILE COUNT and not something cleverer:
 *   · IT IS PARTITION-STABLE in the direction that matters. Splitting a FILE inside a package raises
 *     that package's count, which is honest — the clump did get one file bigger — and it does not
 *     move any OTHER package's count, so unlike the file-scale ratio this number is not a statistic
 *     of a distribution every landing perturbs. There is no equivalent of the "47 untouched files
 *     moved" measurement here.
 *   · IT CANNOT BE SATISFIED BY WEAKENING. It reads no threshold and no band; the only way to move
 *     it is to move files out of the worst package, or to edit a dated line in this file.
 *   · SUMMED C IS REPORTED, NOT GATED, for the file census's own reason: it moves whenever any file
 *     in the package is edited, so gating it would red a commit that made a package smaller in every
 *     sense that matters. The count is the standing floor; the C column is what tells a package of 84
 *     thin files from a package of 84 thick ones.
 *   · KNOWN LIMIT, stated here rather than discovered later: it is a MAX, so a commit that moves one
 *     file out of the worst package and two into the second-worst can net to a pass. The per-package
 *     census printed beside it is what makes that visible; the ratchet is the floor, not the report.
 *   · THE FILE SCALE IS UNTOUCHED. measure(), scan() and the HIGH-band arm are byte-for-byte the
 *     semantics they were before this section: the package plane is an ADDITIONAL row and an
 *     ADDITIONAL gated number, and --json's shape is deliberately unchanged so nothing that reads it
 *     has to learn a new schema.
 *
 * V4-158 (2026-09-18): ported from checks/concentration.py, which it replaces, byte for byte on
 * stdout, stderr and exit code across every flag. The arithmetic is the reference's, not
 * JavaScript's: round() and the `f`/`%` formats round the EXACT binary value half-to-even
 * (pyRound, pyFixed), sum() is Neumaier-compensated, textwrap.fill is transcribed with its word
 * splitter, the file walk reproduces pathlib's glob/rglob order, and `--since` reads the
 * `git archive` tar itself. One deviation is deliberate: see collectRef.
 *
 * USAGE
 *     bun checks/concentration.ts                      # full table, exit 0
 *     bun checks/concentration.ts --top 15             # worst 15 only
 *     bun checks/concentration.ts --max-ratio 1.8      # gate: non-zero exit if any file is above
 *     bun checks/concentration.ts --ratchet --max-ratio 1.8   # gate leg: band HIGH may not move
 *     bun checks/concentration.ts --file <path>        # one file, with its neighbour list
 *     bun checks/concentration.ts --since <ref>        # what moved since <ref>, and why
 *     bun checks/concentration.ts --json               # machine-readable
 */
import { lstatSync, readdirSync, readFileSync, realpathSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { argparse, cpCompare, pyRepr, pySplitlines } from "./e2e/pyshim.ts";
import { dumpsIndent, floatRepr, obj, type PyValue } from "./e2e/pyjson.ts";

export const ROOT = dirname(dirname(realpathSync(import.meta.path)));
// restructure PR 3: :client is the first module to live outside gateway/, so the production
// universe is a LIST of module homes, not one `gateway/*` pattern. A source root this census stops
// walking leaves the ratchet grading a smaller tree than the one it claims to measure — and every
// baseline here is a count, so the loss reads as an improvement.
// Every §2.2 module home — the ones that exist and the ones the next module commits create: a glob
// over an absent directory matches nothing, collectRef drops absent archive roots, and so the
// census can only grow as modules land, never shrink.
// ONE line on purpose: the selftest proves the vacuity guard by patching this exact line.
export const SRC_GLOBS = ["gateway/*/src/main", "client/src/main", "core/src/main", "upstream/src/main", "dialects/*/src/main", "providers/*/src/main", "daemon/*/src/main", "app/src/main", "quality/*/src/main"];
export const SRC_RE = /^(gateway\/[^/]+|client|core|upstream|dialects\/[^/]+|providers\/[^/]+|daemon\/[^/]+|app|quality\/[^/]+)\/src\/main\/[^\n]*\.kt\n?$/u;
/** The ARCHIVE roots collectRef asks git for — the top segment of each glob above. */
export const SRC_ARCHIVE_ROOTS = ["gateway", "client", "core", "upstream", "dialects", "providers", "daemon", "app", "quality"];

export const TYPE_DECL = new RegExp(
  "^(public |internal |private )?(sealed |data |abstract |open |value |enum |fun |annotation )*"
  + "(class|interface|object) ",
  "u",
);
// THE CENSUS COUNTS EVERY SPELLING OF A TYPE, OR IT IS A DODGE LIST (DR-51, 2026-08-30).
// `fun interface` — Kotlin's SAM seam, 92 of them across 43 files of this tree — failed TYPE_DECL
// (no `fun ` in the modifier set) and PASSED EXPORT_DECL via its `fun `, so every one was billed 3
// points as a function instead of 8 as the declared type it is. A ports file of ten seams read as
// 30 where ten plain interfaces read as 80. Measured on landing: HIGH 0 -> 0, over-1.8 18 -> 18,
// 7 moderate<->low flips, 43 files' C moved — the ONE DECLARATION, ONE BILL radius, and the same
// kind of correction: it says only that a declaration is billed AS a declaration. `annotation
// class` was the same remaining dodge: the live MustConsume marker read types=0 until both type
// regexes admitted the modifier. The selftest derives annotation nodes from ast-grep's Kotlin AST,
// so its denominator is outside these regexes and a future spelling cannot disappear from both.
//
// NESTED types are REPORTED (the `nested_types` field below), deliberately NOT billed into C.
// Counting them at 8 was measured first: HIGH 0 -> 1 (cli/Command.kt 1.60 -> 6.12), 20 band flips,
// 76 files' C moved — and the minted HIGH rows are sealed hierarchies (Command's data-object
// variants, ContentBlock's wire cases), i.e. the exact shape this repo's own style law produces,
// not hidden collaborators. Charging the mandated idiom 8 points a variant re-scopes the campaign,
// which the denominator paragraph above already reserves to the orchestrator as a calibration
// decision. The field makes the blindness visible; the bill stays a human call.
export const NESTED_TYPE_DECL = new RegExp(
  "^[ \\t]+(public |internal |private |protected )?"
  + "(sealed |data |abstract |open |value |enum |inner |fun |annotation )*"
  + "(class|interface|object)[ \\t]+[A-Za-z_]",
  "u",
);
export const EXPORT_DECL = new RegExp(
  "^(public |internal )?(sealed |data |abstract |open |value |enum |suspend |inline )*"
  + "(class|interface|object|fun|val|var) ",
  "u",
);
export const SPLICE_IMPORT = /^import (splice\.[A-Za-z0-9_.]+)\.[A-Za-z0-9_]+/u;

// The share of a ratio move one factor must carry before `--since` will name it the cause. ratio =
// C / denominator, so in log space the two factors add and their shares sum to 1; a factor holding
// two thirds or more IS the explanation, and anything between the thirds is `mixed` — a row the
// reader has to open rather than a label that decides for them. Stated as a visible constant because
// a dominance rule nobody can read is the same defect as the binary it replaced.
export const CAUSE_DOMINANCE = 2 / 3;

// --------------------------------------------------------------------------------------------
// The files this tree provably cannot bring under the gate by refactoring. Format:
// [path, ceiling ratio, "YYYY-MM-DD: why"], the same shape as UNROUTED_ALLOWLIST in
// checks/rule-routing.sh. Read CEILING EXCEPTIONS in the header before adding one; the short
// version is that an entry here is a CEILING that still fails when breached, its justification is
// mechanically required, a stale entry is a hard error, and every run prints the list. Empty after
// HD-25: UpstreamClient measured 1.78 (C=91.0 / d=51.0) which is under --max-ratio 1.8, so the
// leftover 3.1 ceiling was padded. A second entry added to make a red gate green is the laundering
// this list exists to prevent.
export const CEILING_EXCEPTIONS: [string, number, string][] = [];

// Every exemption starts with a date, exactly as checks/rule-routing.sh requires of
// UNROUTED_ALLOWLIST — an undated one is how the next exemption hides.
const PY_WS = " \t\n\r\x0b\x0c\x1c\x1d\x1e\x1f\x85\xa0                　";
export const EXCEPTION_JUSTIFICATION = new RegExp(`^\\p{Nd}{4}-\\p{Nd}{2}-\\p{Nd}{2}: [^${PY_WS}]`, "u");

// --------------------------------------------------------------------------------------------
// THE RATCHET BASELINE — the census this tree is held to, MEASURED, never estimated. Read THE
// RATCHET in the header first. The count EXCLUDES the ceiling-excepted files above, which are
// graded against their own recorded ceilings by the same leg rather than counted twice. Moving
// this number is a deliberate, dated edit: UP records that the tree got worse, DOWN is the remedy
// the gate itself prints when work lands.
//
// THERE IS DELIBERATELY NO RATCHET_MAX_OVER. It existed until 2026-08-18 and gated the count of
// files above --max-ratio; it was removed, not merely stopped being read, because a baseline
// nobody grades is the stale number this campaign has now been bitten by twice (the 6.14
// UpstreamClient ceiling, the pre-decomposition AnthropicRequest ceiling). The count is measured
// and printed on every run as DEBT. See THE GATED CRITERION IS THE HIGH BAND in the header for
// the control that forced the change.
export const RATCHET_RECORDED = "2026-09-20";
export const RATCHET_MAX_HIGH = 1; // files in band HIGH  (re-measured 2026-09-20 after the :client extraction: 0 -> 1, ControlServer.kt 2.95 -> 3.14)
//
// THE 2026-09-20 MOVE, AND WHY IT IS A MEASUREMENT AND NOT A CONCESSION. Restructure PR 3 step 1
// took 32 files out of `splice.core.launch` and made them the :client module across six packages.
// `bun checks/concentration.ts --since HEAD --max-ratio 1.8` reports ControlServer.kt crossing with
//     ratio 2.95 -> 3.14   ΔC +0.0   Δdenom -4.0   moderate -> HIGH   own 0%   cause neighbourhood
// — its own C did not move by a tenth, and the ONE line the move changed in it is an import path
// (`splice.core.launch.McpAccessKey` -> `splice.client.mcp.McpAccessKey`), which is the same one
// subsystem. What moved is the partition the denominator is a statistic OF: the header's second
// section already records this property by name ("ANY file-scale denominator is a statistic of a
// partition ... a split moves files nobody touched"), with core/wire/AnthropicRequest.kt reading
// 1.97 before and 5.57 after for the same reason. Bringing ControlServer.kt under the line means
// decomposing :daemon-control inside a commit whose every other line is a file move, so the number is
// recorded here with its instrument output instead, and the file is HD-25's work like the other 96.

// THE PACKAGE-SCALE BASELINE (V4-93) — the worst package's FILE COUNT, measured, never estimated.
// Read THE PACKAGE SCALE in the header first. Same discipline as RATCHET_MAX_HIGH: UP records that
// a clump grew, DOWN is the remedy the gate itself prints. The package is named in the comment so
// the diff reads without running anything, but the NAME is not gated — a different package
// becoming the worst at the same count is not a regression, and gating the name would red a commit
// that moved the clump without growing it.
export const PACKAGE_RATCHET_RECORDED = "2026-09-17";
export const PACKAGE_MAX_FILES = 84; // splice.app.cli, 84 of 631 production files (next: splice.dialect.responses 80, splice.gateway.head 51)

// ---------------------------------------------------------------------------------------------
// The reference's numerics. Every float this file prints or compares went through one of these,
// because JavaScript's toFixed rounds the DECIMAL shortest form half-up while the reference rounds
// the EXACT binary value half-to-even — and 2.675 is 2.67499999... in binary.
// ---------------------------------------------------------------------------------------------

/** A finite double as the exact rational num / den, den a power of two. */
function exactRational(x: number): [bigint, bigint] {
  const view = new DataView(new ArrayBuffer(8));
  view.setFloat64(0, Math.abs(x));
  const bits = view.getBigUint64(0);
  const exp = Number((bits >> 52n) & 0x7ffn);
  const frac = bits & ((1n << 52n) - 1n);
  const mant = exp === 0 ? frac : frac | (1n << 52n);
  const shift = (exp === 0 ? 1 : exp) - 1075;
  return shift >= 0 ? [mant << BigInt(shift), 1n] : [mant, 1n << BigInt(-shift)];
}

/** |x| rounded half-to-even at `digits` decimals, as a decimal string without sign. */
function exactDigits(x: number, digits: number): string {
  const [n, d] = exactRational(x);
  const scaled = n * 10n ** BigInt(digits);
  let q = scaled / d;
  const r2 = (scaled % d) * 2n;
  if (r2 > d || (r2 === d && q % 2n === 1n)) q += 1n;
  const s = q.toString().padStart(digits + 1, "0");
  return digits === 0 ? s : `${s.slice(0, -digits)}.${s.slice(-digits)}`;
}

const signBit = (x: number) => x < 0 || Object.is(x, -0);

/** round(x, n) on a float. */
export function pyRound(x: number, digits: number): number {
  if (!Number.isFinite(x)) return x;
  return Number((signBit(x) ? "-" : "") + exactDigits(x, digits));
}

/** repr() / str() of a float. */
export function floatStr(x: number): string {
  if (Number.isNaN(x)) return "nan";
  if (x === Infinity) return "inf";
  if (x === -Infinity) return "-inf";
  return floatRepr(Object.is(x, -0) ? "-0" : String(x));
}

/** format(x, "[+].Nf"), no width. */
function fixed(x: number, digits: number, plus = false): string {
  const sign = signBit(x) && !Number.isNaN(x) ? "-" : plus ? "+" : "";
  if (Number.isNaN(x)) return (plus ? "+" : "") + "nan";
  if (!Number.isFinite(x)) return sign + "inf";
  return sign + exactDigits(x, digits);
}

/** format(x, "+"): the float's str() with its sign always written. */
const plusStr = (x: number): string => (signBit(x) || Number.isNaN(x) ? floatStr(x) : "+" + floatStr(x));

const cpLen = (s: string) => Array.from(s).length;
const cpSlice = (s: string, end: number) => Array.from(s).slice(0, end).join("");
const ljust = (s: string, w: number) => s + " ".repeat(Math.max(0, w - cpLen(s)));
const rjust = (s: string, w: number) => " ".repeat(Math.max(0, w - cpLen(s))) + s;

/** statistics.median. */
export function median(data: number[]): number {
  const sorted = [...data].sort((a, b) => a - b);
  const n = sorted.length;
  if (n === 0) throw new Error("no median for empty data");
  if (n % 2 === 1) return sorted[Math.floor(n / 2)];
  const i = n / 2;
  return (sorted[i - 1] + sorted[i]) / 2;
}

/** sum() over floats: CPython 3.12+ adds them with Neumaier compensation. */
function fsum(xs: number[]): number {
  if (xs.length === 0) return 0;
  let total = xs[0];
  let c = 0;
  for (const x of xs.slice(1)) {
    const t = total + x;
    if (Math.abs(total) >= Math.abs(x)) c += (total - t) + x;
    else c += (x - t) + total;
    total = t;
  }
  return c !== 0 && Number.isFinite(c) ? total + c : total;
}

const isBlank = (s: string) => Array.from(s).every((ch) => PY_WS.includes(ch));
const pyStrip = (s: string) => {
  const cps = Array.from(s);
  let a = 0;
  let b = cps.length;
  while (a < b && PY_WS.includes(cps[a])) a++;
  while (b > a && PY_WS.includes(cps[b - 1])) b--;
  return cps.slice(a, b).join("");
};

// ---------------------------------------------------------------------------------------------
// textwrap.fill with the reference's defaults: tabs expanded, whitespace replaced, long words
// broken, hyphenated words split, whitespace dropped at line edges. The word splitter is the
// reference's wordsep_re, with its Unicode \w spelled out.
// ---------------------------------------------------------------------------------------------
const TW_WS = "[\\t\\n\\x0b\\x0c\\r ]";
const TW_NWS = "[^\\t\\n\\x0b\\x0c\\r ]";
const WORD = "[\\p{L}\\p{N}_]";
const WP = "[\\p{L}\\p{N}_!\"'&.,?]";
const LT = "(?:(?!\\p{Nd})[\\p{L}\\p{N}_])";
const WORDSEP = new RegExp(
  `(${TW_WS}+`
  + `|(?<=${WP})-{2,}(?=${WORD})`
  + `|${TW_NWS}+?(?:-(?:(?<=${LT}{2}-)|(?<=${LT}-${LT}-))(?=${LT}-?${LT})|(?=${TW_WS}|$)|(?<=${WP})(?=-{2,}${WORD})))`,
  "gu",
);

function expandTabs(text: string, tabsize = 8): string {
  let out = "";
  let col = 0;
  for (const ch of text) {
    if (ch === "\t") {
      const n = tabsize - (col % tabsize);
      out += " ".repeat(n);
      col += n;
    } else {
      out += ch;
      col = ch === "\n" || ch === "\r" ? 0 : col + 1;
    }
  }
  return out;
}

export function textwrapFill(text: string, width: number, initialIndent: string, subsequentIndent: string): string {
  const munged = expandTabs(text).replace(/[\t\n\x0b\x0c\r]/g, " ");
  const chunks = [...munged.matchAll(WORDSEP)].map((m) => m[0]).filter((c) => c !== "");
  chunks.reverse();
  const lines: string[] = [];
  while (chunks.length) {
    const curLine: string[] = [];
    let curLen = 0;
    const indent = lines.length ? subsequentIndent : initialIndent;
    const room = width - cpLen(indent);
    if (isBlank(chunks[chunks.length - 1]) && lines.length) chunks.pop();
    while (chunks.length) {
      const l = cpLen(chunks[chunks.length - 1]);
      if (curLen + l <= room) {
        curLine.push(chunks.pop() as string);
        curLen += l;
      } else break;
    }
    if (chunks.length && cpLen(chunks[chunks.length - 1]) > room) {
      // _handle_long_word
      const spaceLeft = room < 1 ? 1 : room - curLen;
      const chunk = Array.from(chunks[chunks.length - 1]);
      let end = spaceLeft;
      if (chunk.length > spaceLeft) {
        const hyphen = chunk.slice(0, spaceLeft).lastIndexOf("-");
        if (hyphen > 0 && chunk.slice(0, hyphen).some((c) => c !== "-")) end = hyphen + 1;
      }
      curLine.push(chunk.slice(0, end).join(""));
      chunks[chunks.length - 1] = chunk.slice(end).join("");
      curLen = curLine.reduce((n, c) => n + cpLen(c), 0);
    }
    if (curLine.length && isBlank(curLine[curLine.length - 1])) {
      curLen -= cpLen(curLine[curLine.length - 1]);
      curLine.pop();
    }
    if (curLine.length) lines.push(indent + curLine.join(""));
  }
  return lines.join("\n");
}

// ---------------------------------------------------------------------------------------------
// The census.
// ---------------------------------------------------------------------------------------------

export interface Row {
  file: string;
  package: string;
  logic: number;
  exports: number;
  exports_non_type: number;
  types: number;
  nested_types: number;
  subsystems: string[];
  concerns: number;
  C: number;
  neighbour_packages: string[];
  neighbour_median_C: number;
  denominator: number;
  denominator_floored: boolean;
  ratio: number;
  band: string;
}

export interface PackageRow {
  package: string;
  files: number;
  C: number;
  median_C: number;
}

const out = (line = "") => process.stdout.write(line + "\n");
const err = (line = "") => process.stderr.write(line + "\n");

/** One row per PACKAGE: files, summed C, median C. The package plane of the same census.
 *
 *  Derived from the same `rows` the file plane measures, so the two can never disagree about what
 *  a file is or what its C is — and `median_C` is the identical statistic scan() already votes
 *  with, printed so a reader can see the denominator a neighbour contributes. */
export function packageCensus(rows: Row[]): PackageRow[] {
  const byPackage = new Map<string, Row[]>();
  for (const row of rows) {
    const list = byPackage.get(row.package);
    if (list) list.push(row);
    else byPackage.set(row.package, [row]);
  }
  const census = [...byPackage].map(([pkg, files]) => ({
    package: pkg,
    files: files.length,
    C: pyRound(fsum(files.map((f) => f.C)), 1),
    median_C: pyRound(median(files.map((f) => f.C)), 1),
  }));
  return census.sort((a, b) => b.files - a.files || b.C - a.C);
}

/** The package row of the census, printed on every run — a plane nobody prints is a plane
 *  nobody grades, which is the whole reason this file's own leg once executed `true`. */
export function reportPackages(census: PackageRow[], top = 8): void {
  const files = census.reduce((n, p) => n + p.files, 0);
  out(`\nPACKAGE SCALE — ${census.length} package(s), ${files} file(s); gated number is the worst FILE COUNT`);
  out(`  ${ljust("package", 44)} ${rjust("files", 5)} ${rjust("sum C", 8)} ${rjust("median C", 9)}`);
  // census[:top], with the reference's negative-index slicing
  for (const p of census.slice(0, top)) {
    out(`  ${ljust(p.package, 44)} ${rjust(String(p.files), 5)} ${rjust(fixed(p.C, 1), 8)} ${rjust(fixed(p.median_C, 1), 9)}`);
  }
  if (census.length > top) {
    out(`  ... ${census.length - top} more; full list \`bun checks/concentration.ts --packages\``);
  }
}

/** The ONE gated package number: the worst package's file count, against PACKAGE_MAX_FILES.
 *
 *  Two-directional for the same reason the HIGH band is (see THE PACKAGE SCALE): a rise is a
 *  regression, and a baseline held ABOVE the measurement is unearned room for the next one to hide
 *  in. An empty census is a broken walk, not a clean tree, so it refuses rather than passing. */
export function packageProblems(census: PackageRow[]): string[] {
  if (!census.length) {
    return [
      "PACKAGE SCALE: the census is EMPTY — no production package was measured. A plane with no "
      + "denominator cannot pass; check SRC_GLOBS against the tree.",
    ];
  }
  const worst = census[0];
  if (worst.files > PACKAGE_MAX_FILES) {
    return [
      `PACKAGE REGRESSION: the worst package holds ${worst.files} files, baseline `
      + `${PACKAGE_MAX_FILES} (${worst.package}, sum C ${floatStr(worst.C)}). A package absorbed a file `
      + "that nothing recorded — the file plane cannot see this, which is why the package plane "
      + "exists. Move the file out, or raise PACKAGE_MAX_FILES in checks/concentration.ts as a "
      + "dated edit recording that the clump grew.",
    ];
  }
  if (worst.files < PACKAGE_MAX_FILES) {
    return [
      `PACKAGE SLACK: the worst package holds ${worst.files} files (${worst.package}) and `
      + `the baseline still claims ${PACKAGE_MAX_FILES}. Set PACKAGE_MAX_FILES = ${worst.files} `
      + "and re-date PACKAGE_RATCHET_RECORDED in checks/concentration.ts. A baseline held above "
      + "the measured count is unearned room for the next regression to hide in — the same defect "
      + "as a ceiling recorded above its file's measured ratio.",
    ];
  }
  return [];
}

export const ceilings = (): Map<string, number> => new Map(CEILING_EXCEPTIONS.map(([path, ceiling]) => [path, ceiling]));

/** repr() of a malformed exception entry, a tuple of str and float. */
const entryRepr = (entry: unknown[]): string => {
  const items = entry.map((v) => (typeof v === "string" ? pyRepr(v) : typeof v === "number" ? floatStr(v) : String(v)));
  return `(${items.join(", ")}${items.length === 1 ? "," : ""})`;
};

/** Structural faults in CEILING_EXCEPTIONS, which fail EVERY mode rather than just the gate.
 *
 *  A malformed exception list is a defect whatever question the caller asked, and a list that is
 *  only validated on the gate path is a list that goes quiet the moment somebody runs --file. */
export function exceptionErrors(rows: Row[]): string[] {
  const known = new Map(rows.map((row) => [row.file, row]));
  const seen = new Set<string>();
  const errors: string[] = [];
  for (const entry of CEILING_EXCEPTIONS) {
    if (entry.length !== 3) {
      errors.push(`${entryRepr(entry)} is not (path, ceiling, justification) — three fields, always`);
      continue;
    }
    const [path, ceiling, why] = entry;
    if (seen.has(path)) {
      errors.push(`'${path}' is listed twice — one ceiling per file, or the stricter entry is dead text`);
    }
    seen.add(path);
    if (!EXCEPTION_JUSTIFICATION.test(pyStrip(String(why)))) {
      errors.push(
        `'${path}' has no dated justification — every exception starts 'YYYY-MM-DD: <why>'. `
        + "A blank or missing justification is a hard error, never a pass: an exemption "
        + "nobody can evaluate is indistinguishable from one nobody should have granted.",
      );
    }
    const row = known.get(path);
    if (row === undefined) {
      errors.push(
        `'${path}' is not a production .kt file any more — delete the entry. A stale exemption `
        + "is an ungraded file one rename later, which is the failure it was written to prevent.",
      );
      continue;
    }
    // THE CEILING MAY NOT SIT ABOVE THE FILE (2026-08-18). Every other ceiling comparison in
    // this file is `row.ratio > ceiling`, so a ceiling that RISES above its file fails and a
    // ceiling RECORDED ABOVE the file's real ratio failed NOTHING — the padding direction was
    // unguarded in every mode. That is not hypothetical: this list carried UpstreamClient at
    // 6.14 against a file measuring 2.79, i.e. 3.35 points of room the file never earned, and
    // nothing in the repo could see it. It is the same defect the HIGH baseline's SLACK arm
    // already treats as a hard error, and that arm's own message names this exact gap ("the
    // same defect as a ceiling recorded above its file's measured ratio").
    //
    // DELIBERATELY ONE-DIRECTIONAL, and this is the split main() already draws between exit 2
    // and exit 1. A padded ceiling is a stale LIST — nobody's code moved, the recorded number is
    // simply wrong — so it is a broken instrument and fails every mode here. A ceiling BREACHED
    // from below is a failing MEASUREMENT: the file or its neighbourhood moved, and ratchet(),
    // --file and the --max-ratio path already report it at exit 1 with the cause, the C/denom
    // split and the --since remedy attached. Re-reporting that as "CEILING_EXCEPTIONS is
    // invalid" would call a real regression a malformed list and orphan the three branches that
    // say it properly.
    const measured = row.ratio;
    if (pyRound(ceiling, 2) > measured) {
      errors.push(
        `'${path}' has a PADDED CEILING: recorded ${floatStr(ceiling)}, file measures ${floatStr(measured)} `
        + `(C=${floatStr(row.C)}, denominator=${floatStr(row.denominator)}). Record `
        + `${floatStr(measured)}. A ceiling held above its file's measured ratio is `
        + `${floatStr(pyRound(pyRound(ceiling, 2) - measured, 2))} points of unearned room for the `
        + "next regression to hide in, and on its own it fails nothing — the gate only ever "
        + "asked whether the ratio ROSE above the ceiling. A ceiling freezes a MEASURED "
        + "state; a number nobody re-measured is an exemption, which is the laundering this "
        + "list exists to prevent.",
      );
    }
  }
  return errors;
}

/** Print the active exceptions, separately from the passing files, on every run.
 *
 *  An exception that is never read is never evaluated, and an allowlist nobody reads is a laundry. */
export function reportExceptions(rows: Row[]): void {
  if (!CEILING_EXCEPTIONS.length) return;
  const byFile = new Map(rows.map((row) => [row.file, row]));
  out(`\nCEILING EXCEPTIONS (${CEILING_EXCEPTIONS.length}) — graded against their own ceiling, not --max-ratio:`);
  for (const [path, ceiling, why] of CEILING_EXCEPTIONS) {
    const row = byFile.get(path) as Row;
    const state = row.ratio > ceiling ? "OVER CEILING — the gate fails" : "within ceiling";
    out(`  ${path}`);
    out(`    ratio ${floatStr(row.ratio)}  ceiling ${floatStr(ceiling)}  C ${floatStr(row.C)}  [${state}]`);
    out(textwrapFill(why, 96, "    ", "    "));
  }
}

const baseName = (file: string) => file.slice(file.lastIndexOf("/") + 1);

/** The enforceable half of the 1.8 gate: the HIGH band may not RISE, and may not silently FALL.
 *
 *  Prints the baseline, the measured band, and the standing over-gate debt on every run — a
 *  ratchet whose output shows only its own verdict hides the files it is deliberately not gating.
 *  See THE RATCHET, and THE GATED CRITERION IS THE HIGH BAND, in the header: the count of files
 *  above --max-ratio is REPORTED here and is NOT the criterion, because it rises on splits that
 *  touch nothing and so forbids the decomposition this oracle exists to drive. */
export function ratchet(rows: Row[], maxRatio: number): number {
  const caps = ceilings();
  const graded = rows.filter((r) => !caps.has(r.file));
  const over = graded.filter((r) => r.ratio > maxRatio);
  const high = graded.filter((r) => r.band === "HIGH");

  const census = packageCensus(rows);
  const worstFiles = census.length ? census[0].files : 0;

  out(`CONCENTRATION RATCHET — baseline recorded ${RATCHET_RECORDED}, gate ratio ${floatStr(maxRatio)}`);
  out(`  ${ljust("band HIGH", 22)} baseline ${rjust(String(RATCHET_MAX_HIGH), 3)}   measured ${rjust(String(high.length), 3)}   [GATED]`);
  out(`  ${ljust(`files over ${floatStr(maxRatio)}`, 22)} ${rjust("", 12)} measured ${rjust(String(over.length), 3)}   [reported, not gated]`);
  // V4-93: the package plane's one gated number, printed beside the file plane's so a reader can
  // check both against the baselines the run claims to enforce.
  out(
    `  ${ljust("worst package files", 22)} baseline ${rjust(String(PACKAGE_MAX_FILES), 3)}   measured ${rjust(String(worstFiles), 3)}   `
    + `[GATED, recorded ${PACKAGE_RATCHET_RECORDED}]`,
  );
  const byFile = new Map(rows.map((row) => [row.file, row]));
  for (const [path, ceiling] of CEILING_EXCEPTIONS) {
    const row = byFile.get(path) as Row;
    const state = row.ratio > ceiling ? "OVER CEILING" : "within ceiling";
    out(`  ceiling exception  ratio ${rjust(fixed(row.ratio, 2), 5)}  ceiling ${ljust(floatStr(ceiling), 5)} [${state}]  ${path}`);
  }
  if (high.length) {
    // The gated census, by name. A gate whose own output cannot be checked against the number
    // it enforces is not auditable — the same rule that makes --max-ratio mandatory below.
    out(
      `\n  GATED — the ${high.length} file(s) in band HIGH:\n`
      + "        " + high.map((r) => `${baseName(r.file)} ${floatStr(r.ratio)}`).join(" | "),
    );
  }
  if (over.length) {
    out(
      `\n  DEBT: ${over.length} file(s) sit above ${floatStr(maxRatio)}. This count is REPORTED, NOT GATED, and `
      + "neither is their ratio.",
    );
    out(
      textwrapFill(
        "WHY NOT GATED: the denominator is a file-scale order statistic, so ANY split moves files "
        + "nobody touched — splitting one god object into a composer plus collaborators RAISES this "
        + "count while the worst row collapses and HIGH does not move. Measured over this campaign "
        + "it rose 7 times and fell 7, net 43 -> 42, while HIGH went 22 -> 8 without ever rising. "
        + "Gating it penalises decomposition; see the module docstring.",
        96,
        "        ",
        "        ",
      ),
    );
    out(
      "        worst: " + over.slice(0, 5).map((r) => `${baseName(r.file)} ${floatStr(r.ratio)}`).join(" | ") + "\n"
      + `        full list \`bun checks/concentration.ts --top ${over.length}\`; every one is HD-25's `
      + "work, not an exemption.\n"
      + "        a ceiling exception's justification reads with `--file <path>`.",
    );
  } else {
    // The state this ratchet exists to reach: nothing above the gate but the excepted files, so
    // the direction-only leg has no debt left to hide and `--max-ratio` can replace it outright.
    out(
      `\n  NO DEBT: nothing outside the ${CEILING_EXCEPTIONS.length} ceiling exception(s) is above `
      + `${floatStr(maxRatio)}.\n`
      + `        Retire this leg: make it \`--max-ratio ${floatStr(maxRatio)}\` (the hard gate) and delete `
      + "RATCHET_MAX_HIGH / RATCHET_RECORDED.",
    );
  }

  const problems: string[] = [];
  // ONE GATED NUMBER: the HIGH band. The count of files over --max-ratio is printed above as debt
  // and is deliberately absent from this loop — see the header for the pure-relocation control
  // that proved a count criterion red on a change that cannot make the tree worse.
  if (high.length > RATCHET_MAX_HIGH) {
    problems.push(
      `REGRESSION: band HIGH rose ${RATCHET_MAX_HIGH} -> ${high.length}. A god object appeared that `
      + `nothing recorded. Run \`bun checks/concentration.ts --since HEAD --max-ratio ${floatStr(maxRatio)}\`: `
      + "cause `own` is code in this change, cause `neighbourhood` is a denominator that moved under "
      + "the file, and the ΔC / Δdenom columns show the split the label was taken from. Fix the file — "
      + "raising RATCHET_MAX_HIGH is a dated edit recording that the tree got worse.",
    );
  } else if (high.length < RATCHET_MAX_HIGH) {
    problems.push(
      `SLACK: band HIGH fell ${RATCHET_MAX_HIGH} -> ${high.length}, and the baseline still claims `
      + `${RATCHET_MAX_HIGH}. Set RATCHET_MAX_HIGH = ${high.length} and re-date RATCHET_RECORDED in `
      + "checks/concentration.ts. A baseline held above the measured count is unearned room for the "
      + "next regression to hide in — the same defect as a ceiling recorded above its file's measured "
      + "ratio.",
    );
  }
  for (const [path, ceiling] of CEILING_EXCEPTIONS) {
    const row = byFile.get(path) as Row;
    if (row.ratio > ceiling) {
      problems.push(
        `CEILING BREACHED: ${path} ratio ${floatStr(row.ratio)} is above its recorded ceiling ${floatStr(ceiling)} `
        + `(C=${floatStr(row.C)}). A ceiling freezes a known state; it does not stop watching.`,
      );
    }
  }
  // V4-93: the package plane. An ADDITIONAL gated number — nothing above it was changed — so a
  // commit that passes the file census and clumps a package is red on the commit that does it.
  reportPackages(census);
  problems.push(...packageProblems(census));

  if (problems.length) {
    err(`\nFAIL: concentration ratchet — ${problems.length} problem(s):`);
    for (const problem of problems) err(textwrapFill(problem, 96, "  ✗ ", "    "));
    return 1;
  }
  out(
    `\nOK: concentration ratchet holds — band HIGH is exactly the ${RATCHET_RECORDED} baseline `
    + `(${RATCHET_MAX_HIGH}), the worst package holds exactly the ${PACKAGE_RATCHET_RECORDED} baseline `
    + `(${PACKAGE_MAX_FILES} files), and all ${CEILING_EXCEPTIONS.length} exception(s) are within their `
    + "own ceiling",
  );
  return 0;
}

export function measure(rel: string, text: string): Row {
  const lines = pySplitlines(text);
  const logic = lines.filter((line) => {
    const stripped = pyStrip(line);
    return stripped !== ""
      && !["//", "*", "/*"].some((p) => stripped.startsWith(p))
      && !["import ", "package "].some((p) => line.startsWith(p));
  });
  const exports = lines.filter((line) => EXPORT_DECL.test(line));
  const types = lines.filter((line) => TYPE_DECL.test(line));
  // ONE DECLARATION, ONE BILL — a top-level type is charged by `concerns`, so it must not also be
  // charged as an export. Tested PER LINE rather than as exports - types: TYPE_DECL admits
  // `private ` and EXPORT_DECL does not, so a file with a top-level private class would otherwise
  // be CREDITED 3 points for hiding a collaborator. See the header.
  const nonTypeExports = exports.filter((line) => !TYPE_DECL.test(line));
  // Matched over LOGIC lines, not raw lines, so `* class Foo does ...` inside a doc comment is
  // never a type; a type-shaped line inside a multiline string still counts, stated as the limit.
  const nestedTypes = logic.filter((line) => NESTED_TYPE_DECL.test(line));
  const subsystems = new Set<string>();
  for (const line of lines) {
    const m = SPLICE_IMPORT.exec(line);
    if (m) subsystems.add(m[1]);
  }
  const concerns = types.length + subsystems.size;
  const tail = rel.split("/kotlin/");
  return {
    file: rel,
    package: tail[tail.length - 1].split("/").slice(0, -1).join("."),
    logic: logic.length,
    exports: exports.length,
    exports_non_type: nonTypeExports.length,
    types: types.length,
    // REPORTED, NOT BILLED — see NESTED_TYPE_DECL above for the measured re-scoping radius.
    nested_types: nestedTypes.length,
    subsystems: [...subsystems].sort(cpCompare),
    concerns,
    C: pyRound(0.5 * logic.length + 3 * nonTypeExports.length + 8 * concerns, 1),
    neighbour_packages: [],
    neighbour_median_C: 0,
    denominator: 0,
    denominator_floored: false,
    ratio: 0,
    band: "",
  };
}

const decode = (bytes: Uint8Array) => new TextDecoder("utf-8", { ignoreBOM: true }).decode(bytes);

const isDir = (path: string, follow: boolean): boolean => {
  try {
    return (follow ? statSync(path) : lstatSync(path)).isDirectory();
  } catch {
    return false;
  }
};

const entries = (path: string): string[] => {
  try {
    return readdirSync(path);
  } catch {
    return [];
  }
};

/** fnmatch for one path segment: `*`, `?` and `[...]`, matching dotfiles like pathlib does. */
function segmentMatcher(segment: string): RegExp {
  let re = "";
  for (let i = 0; i < segment.length; i++) {
    const ch = segment[i];
    if (ch === "*") re += "[^/]*";
    else if (ch === "?") re += "[^/]";
    else if (ch === "[") {
      const close = segment.indexOf("]", i + 2);
      if (close < 0) re += "\\[";
      else {
        let body = segment.slice(i + 1, close);
        if (body.startsWith("!")) body = "^" + body.slice(1);
        re += `[${body.replace(/\\/g, "\\\\")}]`;
        i = close;
      }
    } else re += ch.replace(/[.+^${}()|\\\]]/g, "\\$&");
  }
  return new RegExp(`^${re}$`, "su");
}

/** ROOT.glob(SRC_GLOB): each wildcard segment expands in directory order and follows symlinked
 *  directories, exactly as the harness's one-symlink-per-module tree needs. */
function globDirs(pattern: string): string[] {
  let found = [""];
  for (const segment of pattern.split("/")) {
    const next: string[] = [];
    for (const base of found) {
      if (/[*?[]/.test(segment)) {
        const match = segmentMatcher(segment);
        for (const name of entries(join(ROOT, base))) {
          if (match.test(name)) next.push(base ? `${base}/${name}` : name);
        }
      } else next.push(base ? `${base}/${segment}` : segment);
    }
    found = next;
  }
  return found.filter((rel) => {
    try {
      lstatSync(join(ROOT, rel));
      return true;
    } catch {
      return false;
    }
  });
}

/** dir.rglob("*.kt") in pathlib 3.13's order: the root's own .kt entries first; then a stack walk
 *  that, for each directory it pops, lists every subdirectory's .kt entries the moment it meets that
 *  subdirectory and pushes it. Symlinked subdirectories are listed, never entered. */
function rglobKt(rel: string): string[] {
  const found: string[] = [];
  const kt = (dir: string) => {
    for (const name of entries(join(ROOT, dir))) {
      if (name.endsWith(".kt")) found.push(`${dir}/${name}`);
    }
  };
  kt(rel);
  const stack = [rel];
  while (stack.length) {
    const dir = stack.pop() as string;
    for (const name of entries(join(ROOT, dir))) {
      const child = `${dir}/${name}`;
      if (!isDir(join(ROOT, child), false)) continue;
      kt(child);
      stack.push(child);
    }
  }
  return found;
}

export function collect(): Row[] {
  const files = SRC_GLOBS.flatMap(globDirs).flatMap(rglobKt);
  return files.map((rel) => measure(rel, decode(readFileSync(join(ROOT, rel)))));
}

/** The regular-file members of a tar stream, as name and bytes: ustar prefix, pax `path` records
 *  and GNU long names honoured, global headers skipped. */
function tarFiles(blob: Uint8Array): [string, Uint8Array][] {
  const files: [string, Uint8Array][] = [];
  const field = (block: Uint8Array, at: number, len: number) => {
    const raw = block.subarray(at, at + len);
    const nul = raw.indexOf(0);
    return decode(nul < 0 ? raw : raw.subarray(0, nul));
  };
  let pos = 0;
  let paxPath: string | null = null;
  let longName: string | null = null;
  while (pos + 512 <= blob.length) {
    const header = blob.subarray(pos, pos + 512);
    if (header.every((b) => b === 0)) break;
    const size = parseInt(field(header, 124, 12).trim() || "0", 8);
    const type = String.fromCharCode(header[156]);
    const data = blob.subarray(pos + 512, pos + 512 + size);
    pos += 512 + Math.ceil(size / 512) * 512;
    if (type === "x") {
      // "<length> <key>=<value>\n" records, the length counted in BYTES
      let at = 0;
      while (at < data.length) {
        const space = data.indexOf(0x20, at);
        const len = parseInt(decode(data.subarray(at, space)), 10);
        const record = decode(data.subarray(space + 1, at + len - 1));
        const eq = record.indexOf("=");
        if (record.slice(0, eq) === "path") paxPath = record.slice(eq + 1);
        at += len;
      }
      continue;
    }
    if (type === "g") continue;
    if (type === "L") {
      longName = field(data, 0, data.length);
      continue;
    }
    let name = field(header, 0, 100);
    const prefix = field(header, 257, 6) === "ustar" ? field(header, 345, 155) : "";
    if (prefix) name = `${prefix}/${name}`;
    if (longName !== null) name = longName;
    if (paxPath !== null) name = paxPath;
    paxPath = null;
    longName = null;
    if (type === "0" || type === "\0" || type === "7") files.push([name, data]);
  }
  return files;
}

/** Same measurement, taken from a git ref instead of the working tree.
 *
 *  THE ONE DELIBERATE DEVIATION FROM THE REFERENCE (V4-158, orchestrator-approved 2026-09-18). A ref
 *  git cannot archive used to end in an interpreter traceback, exit 1 — a stack of the tool's own
 *  frames with git's actual complaint swallowed by capture_output. This prints what went wrong and
 *  what git said, same exit 1. Do not "restore parity" by putting a stack trace back. */
export function collectRef(ref: string): Row[] {
  // Only the roots that EXIST at `ref`: `git archive` fails the whole export on a pathspec that
  // matches nothing, and the first commit that introduces a new module home (restructure PR 3's
  // client/) is by definition a commit whose parent does not have it. Dropping the absent root is
  // correct here and not a hole — a root missing from the REF contributes no baseline rows, which
  // is exactly what "this module did not exist yet" means.
  const roots = SRC_ARCHIVE_ROOTS.filter((root) => {
    const at = Bun.spawnSync(["git", "-C", ROOT, "ls-tree", "--name-only", ref, "--", root], { stdout: "pipe", stderr: "pipe" });
    return at.exitCode === 0 && decode(at.stdout).trim().length > 0;
  });
  if (roots.length === 0) {
    err(`git ${ref} holds none of ${SRC_ARCHIVE_ROOTS.join(", ")} — there is no production tree to compare against`);
    process.exit(1);
  }
  const run = Bun.spawnSync(["git", "-C", ROOT, "archive", ref, "--", ...roots], { stdout: "pipe", stderr: "pipe" });
  if (run.exitCode !== 0) {
    err(`git archive ${ref} failed (exit ${run.exitCode}): ${decode(run.stderr).trim()}`);
    process.exit(1);
  }
  return tarFiles(run.stdout)
    .filter(([name]) => SRC_RE.test(name))
    .map(([name, data]) => measure(name, decode(data)));
}

export function scan(rows: Row[]): Row[] {
  const byPackage = new Map<string, Row[]>();
  for (const row of rows) {
    const list = byPackage.get(row.package);
    if (list) list.push(row);
    else byPackage.set(row.package, [row]);
  }

  // Each package votes once, with its own median C. See the header: a per-file vote lets a package
  // that gets decomposed outvote every other neighbour, so the oracle moves on files nobody
  // touched. This removes the vote-COUNT effect only — a package's own median still moves when its
  // content is redistributed across more files, which is what `--since` exists to surface.
  const packageMedian = new Map([...byPackage].map(([pkg, rs]) => [pkg, median(rs.map((r) => r.C))]));

  // A ratio taken against a tiny neighbourhood is noise, not a god object: a 63-line file whose
  // neighbours happen to score 1 would read as 63x while being smaller than the median package in
  // the tree. Smooth the denominator with a floor at half the global median C, and require the
  // file itself to clear the global median before any band above "low" can apply.
  //
  // A file with NO neighbourhood at all (imports no splice package, imported by nobody) is graded
  // against the global median outright (DR-117). The old fallback was the file's OWN C, which
  // pinned its ratio to 1.0 — band low forever regardless of size, so a self-contained 800-line
  // god file (vendored codec, standalone tool) passed every mode. The global median is the same
  // neutral comparator the floor already derives from, at full strength: measured on the live
  // tree 2026-08-31, all 12 zero-neighbour files stay low (worst 1.55), so the change is purely
  // prospective. Selftest arm 12 pins the wall.
  const globalMedian = packageMedian.size ? median([...packageMedian.values()]) : 0.0;
  const floor = globalMedian * 0.5;

  for (const row of rows) {
    const neighbours = new Set(row.subsystems.filter((pkg) => packageMedian.has(pkg)));
    for (const other of rows) if (other.subsystems.includes(row.package)) neighbours.add(other.package);
    neighbours.delete(row.package);
    const med = neighbours.size ? median([...neighbours].map((p) => packageMedian.get(p) as number)) : globalMedian;
    const denominator = Math.max(med, floor);
    row.neighbour_packages = [...neighbours].sort(cpCompare);
    row.neighbour_median_C = pyRound(med, 1);
    // REPORT THE DIVISOR ACTUALLY USED, not just the raw median. When the floor bites, the two
    // differ and every reader who reproduces C / neighbour_median_C gets a different number than
    // the tool printed — app/cli/Command.kt reports a median of 1.0 against a ratio of 2.29,
    // which reads as 63x by hand. Ten of 306 files are floored today, and two of the eight HIGH
    // rows are HIGH *because of* the floor rather than because of their neighbours, which is a
    // materially different finding. A gate whose arithmetic cannot be reproduced from its own
    // output is not auditable.
    row.denominator = pyRound(denominator, 1);
    row.denominator_floored = med < floor;
    // The ratio divides the denominator AS REPORTED, never the unrounded value it came from
    // (DR-51, 2026-08-30): dividing the raw value left 18 of 428 rows where C / the printed
    // denominator reproduced a DIFFERENT ratio than the row carried — the exact "arithmetic
    // cannot be reproduced from its own output" defect the REPORT-THE-DIVISOR comment above
    // was written against, one rounding step further down.
    row.ratio = row.denominator ? pyRound(row.C / row.denominator, 2) : 0.0;
    if (row.C < globalMedian) row.band = "low";
    else row.band = row.ratio >= 3.0 ? "HIGH" : row.ratio >= 1.8 ? "moderate" : "low";
  }
  return [...rows].sort((a, b) => b.ratio - a.ratio);
}

/** Split a ratio move into its two factors and name the one that DOMINATES.
 *
 *  ratio = C / denominator, so the move is exactly multiplicative:
 *
 *      ratio_after / ratio_before = (C_after / C_before) * (denominator_before / denominator_after)
 *
 *  Taking logs turns that product into a sum, so "how much of this move is the file's own density
 *  and how much is its neighbourhood" is a division, not a judgement call. Returns the cause and
 *  the share of the move attributable to the file's own C (the neighbourhood share is 1 - it).
 *
 *  WHY NOT `own if C changed`, which this replaces: that test fires on ANY non-zero C delta, so a
 *  half-point edit outranks a denominator that has halved. See THE CAUSE COLUMN IS A SPLIT in the
 *  header for the two measured misattributions that forced the change. */
export function causeOf(was: Row, now: Row): [string, number | null] {
  if (Math.min(was.C, now.C, was.denominator, now.denominator) <= 0) {
    // No log to take. Name whichever factor moved and return no share rather than invent one —
    // a fabricated split is worse than the binary this replaces.
    const ownMoved = was.C !== now.C;
    const neighbourhoodMoved = was.denominator !== now.denominator;
    if (ownMoved && !neighbourhoodMoved) return ["own", null];
    if (neighbourhoodMoved && !ownMoved) return ["neighbourhood", null];
    return ["mixed", null];
  }
  // SIGNED, never abs() (DR-51, 2026-08-30). With abs() the two shares still summed to 1, but the
  // sum being claimed was a lie whenever the factors OPPOSED: C falling while the denominator fell
  // faster is a ratio RISE that abs() split as "12% own / 88% neighbourhood" — reading as if the
  // file's own density helped push the ratio up, when its signed contribution was NEGATIVE. Signed
  // shares keep own + neighbourhood == 1 exactly (they are the two log factors over their sum) and
  // a share outside [0, 1] is the honest picture: one factor amplified the move past 100% and the
  // other pushed back. The dominance thresholds are unchanged and stay monotone over signed values.
  const own = Math.log(now.C / was.C);
  const neighbourhood = Math.log(was.denominator / now.denominator);
  const total = own + neighbourhood;
  if (total === 0) return ["mixed", null];
  const share = own / total;
  if (share >= CAUSE_DOMINANCE) return ["own", share];
  if (share <= 1 - CAUSE_DOMINANCE) return ["neighbourhood", share];
  return ["mixed", share];
}

export interface Move {
  file: string;
  cause: string;
  own_share: number | null;
  neighbourhood_share: number | null;
  C_before: number | null;
  C_after: number | null;
  C_delta: number;
  denominator_before: number | null;
  denominator_after: number | null;
  denominator_delta: number | null;
  ratio_before: number | null;
  ratio_after: number | null;
  band_before: string | null;
  band_after: string | null;
}

/** Every file whose ratio moved since `ref`, with the SPLIT that caused it.
 *
 *  Each row carries the C delta, the denominator delta, and `own_share` — the fraction of the (log)
 *  ratio move the file's own density accounts for. `cause` names the factor holding at least
 *  CAUSE_DOMINANCE of the move, or `mixed` when neither does. See causeOf. */
export function movement(ref: string, rows: Row[]): Move[] {
  const before = new Map(scan(collectRef(ref)).map((r) => [r.file, r]));
  const after = new Map(rows.map((r) => [r.file, r]));
  const moved: Move[] = [];
  const sortedKeys = (keys: Iterable<string>) => [...new Set(keys)].sort(cpCompare);
  // ADDED AND DELETED FILES ARE MOVEMENT (DR-51, 2026-08-30). The old intersection loop silently
  // dropped both, so "reports every file whose ratio moved" excluded the largest move there is —
  // a file that did not exist at the ref. A new god object created since <ref> appeared in no row
  // of the instrument whose header promises a landing note may not claim a ratio without it.
  for (const name of sortedKeys([...after.keys()].filter((k) => !before.has(k)))) {
    const now = after.get(name) as Row;
    moved.push({
      file: name, cause: "added", own_share: null, neighbourhood_share: null,
      C_before: null, C_after: now.C, C_delta: now.C,
      denominator_before: null, denominator_after: now.denominator, denominator_delta: null,
      ratio_before: null, ratio_after: now.ratio, band_before: null, band_after: now.band,
    });
  }
  for (const name of sortedKeys([...before.keys()].filter((k) => !after.has(k)))) {
    const was = before.get(name) as Row;
    moved.push({
      file: name, cause: "deleted", own_share: null, neighbourhood_share: null,
      C_before: was.C, C_after: null, C_delta: pyRound(-was.C, 1),
      denominator_before: was.denominator, denominator_after: null, denominator_delta: null,
      ratio_before: was.ratio, ratio_after: null, band_before: was.band, band_after: null,
    });
  }
  for (const name of sortedKeys([...before.keys()].filter((k) => after.has(k)))) {
    const was = before.get(name) as Row;
    const now = after.get(name) as Row;
    if (was.ratio === now.ratio) continue;
    const [cause, share] = causeOf(was, now);
    moved.push({
      file: name, cause,
      own_share: share === null ? null : pyRound(share, 3),
      neighbourhood_share: share === null ? null : pyRound(1 - share, 3),
      C_before: was.C, C_after: now.C, C_delta: pyRound(now.C - was.C, 1),
      denominator_before: was.denominator, denominator_after: now.denominator,
      denominator_delta: pyRound(now.denominator - was.denominator, 1),
      ratio_before: was.ratio, ratio_after: now.ratio, band_before: was.band, band_after: now.band,
    });
  }
  return moved;
}

const shortName = (file: string, width: number) =>
  ljust(cpSlice(file.replaceAll("gateway/", "").replaceAll("/src/main/kotlin/splice", "~"), width), width);

export function reportMovement(ref: string, moved: Move[], maxRatio: number | null): void {
  // Both deltas are printed because the cause is a SPLIT of them; a label with the numbers it was
  // derived from withheld is the binary this replaced, wearing a longer word. `cause` stays the
  // last field on the line so `awk '{print $NF}'` and `grep 'neighbourhood$'` still work, and
  // "neighbourhood" contains no "own" substring, so the two grep cleanly.
  const survivors = moved.filter((m) => m.cause !== "added" && m.cause !== "deleted");
  out(`${ljust("file", 52)} ${rjust("ratio", 14)}  ${rjust("ΔC", 8)} ${rjust("Δdenom", 8)}  ${rjust("band", 18)}  ${rjust("own%", 5)}  cause`);
  for (const m of survivors) {
    // A signed share can sit outside [0, 1] — one factor amplified the move past 100% and the
    // other pushed back. See causeOf; printing it clamped would re-create the abs() lie.
    const share = m.own_share === null ? "  n/a" : rjust(fixed(m.own_share * 100, 0) + "%", 5);
    out(
      `${shortName(m.file, 52)} `
      + `${rjust(fixed(m.ratio_before as number, 2), 6)} ->${rjust(fixed(m.ratio_after as number, 2), 6)}  `
      + `${rjust(fixed(m.C_delta, 1, true), 8)} ${rjust(fixed(m.denominator_delta as number, 1, true), 8)}  `
      + `${rjust(m.band_before as string, 8)} ->${rjust(m.band_after as string, 8)}  ${share}  ${m.cause}`,
    );
  }
  // Added and deleted files are their own sections rather than rows forced into a before/after
  // table half of which they do not have (DR-51 — the intersection loop used to drop them).
  for (const m of moved.filter((x) => x.cause === "added")) {
    out(
      `${shortName(m.file, 52)} `
      + `${rjust("(none)", 6)} ->${rjust(fixed(m.ratio_after as number, 2), 6)}  ${rjust(fixed(m.C_delta, 1, true), 8)} ${ljust("", 8)}  `
      + `${rjust("", 8)} ->${rjust(m.band_after as string, 8)}    n/a  added`,
    );
  }
  for (const m of moved.filter((x) => x.cause === "deleted")) {
    out(
      `${shortName(m.file, 52)} `
      + `${rjust(fixed(m.ratio_before as number, 2), 6)} ->${rjust("(gone)", 6)}  ${rjust(fixed(m.C_delta, 1, true), 8)} ${ljust("", 8)}  `
      + `${rjust(m.band_before as string, 8)} ->${rjust("", 8)}    n/a  deleted`,
    );
  }
  const count = (cause: string) => moved.filter((m) => m.cause === cause).length;
  out(
    `\n${moved.length} file(s) moved since ${ref} | own ${count("own")} `
    + `| neighbourhood ${count("neighbourhood")} | mixed ${count("mixed")} `
    + `| added ${count("added")} | deleted ${count("deleted")} `
    + `(dominance threshold ${fixed(CAUSE_DOMINANCE * 100, 0)}% of the log move)`,
  );
  if (maxRatio !== null) {
    const drift = moved.filter((m) => m.cause === "neighbourhood");
    const crossed = drift.filter((m) => ((m.ratio_before as number) > maxRatio) !== ((m.ratio_after as number) > maxRatio));
    if (crossed.length) {
      err(
        `\nWARNING: ${crossed.length} file(s) crossed ratio ${floatStr(maxRatio)} on a move their `
        + "DENOMINATOR dominates — that number belongs to whoever split a neighbouring "
        + "package, not to this file's density:",
      );
      for (const m of crossed) {
        err(
          `  ${m.file}  ratio ${floatStr(m.ratio_before as number)} -> ${floatStr(m.ratio_after as number)}  `
          + `C ${floatStr(m.C_before as number)} -> ${floatStr(m.C_after as number)} (${plusStr(m.C_delta)})  `
          + `denominator ${floatStr(m.denominator_before as number)} -> ${floatStr(m.denominator_after as number)} `
          + `(${plusStr(m.denominator_delta as number)})`,
        );
      }
    }
  }
}

// ---------------------------------------------------------------------------------------------
// --json: the reference's json.dumps(indent=2), ints and floats kept apart.
// ---------------------------------------------------------------------------------------------
const jFloat = (x: number | null): PyValue => (x === null ? null : { __pyNum: Object.is(x, -0) ? "-0" : String(x), isFloat: true });
const jInt = (x: number): PyValue => ({ __pyNum: String(x), isFloat: false });

export const rowJson = (r: Row): PyValue => obj([
  ["file", r.file],
  ["package", r.package],
  ["logic", jInt(r.logic)],
  ["exports", jInt(r.exports)],
  ["exports_non_type", jInt(r.exports_non_type)],
  ["types", jInt(r.types)],
  ["nested_types", jInt(r.nested_types)],
  ["subsystems", r.subsystems],
  ["concerns", jInt(r.concerns)],
  ["C", jFloat(r.C)],
  ["neighbour_packages", r.neighbour_packages],
  ["neighbour_median_C", jFloat(r.neighbour_median_C)],
  ["denominator", jFloat(r.denominator)],
  ["denominator_floored", r.denominator_floored],
  ["ratio", jFloat(r.ratio)],
  ["band", r.band],
]);

const moveJson = (m: Move): PyValue => obj([
  ["file", m.file],
  ["cause", m.cause],
  ["own_share", jFloat(m.own_share)],
  ["neighbourhood_share", jFloat(m.neighbourhood_share)],
  ["C_before", jFloat(m.C_before)],
  ["C_after", jFloat(m.C_after)],
  ["C_delta", jFloat(m.C_delta)],
  ["denominator_before", jFloat(m.denominator_before)],
  ["denominator_after", jFloat(m.denominator_after)],
  ["denominator_delta", jFloat(m.denominator_delta)],
  ["ratio_before", jFloat(m.ratio_before)],
  ["ratio_after", jFloat(m.ratio_after)],
  ["band_before", m.band_before],
  ["band_after", m.band_after],
]);

const PROG = "concentration.ts";
const USAGE = `usage: ${PROG} [-h] [--top TOP] [--max-ratio MAX_RATIO] [--ratchet]
                        [--file FILE] [--since SINCE] [--json] [--packages]
`;
const HELP = `${USAGE}
options:
  -h, --help            show this help message and exit
  --top TOP             show only the worst N
  --max-ratio MAX_RATIO
                        gate: fail if any file exceeds this ratio
  --ratchet             gate leg: fail if the count of band-HIGH files moves
                        off the recorded baseline (rise = regression, fall =
                        stale baseline), or a ceiling exception is breached.
                        The count of files over --max-ratio is reported as
                        debt, NOT gated. Requires --max-ratio.
  --file FILE           report one file and list its neighbours
  --since SINCE         report what moved since a git ref, and whether it was
                        own or neighbourhood
  --json
  --packages            print the PACKAGE-scale census in full rather than its
                        worst 8 (V4-93)
`;

export function main(argv: string[]): number {
  const args = argparse(argv, [
    { flag: "--top", kind: "int", dflt: 0 },
    { flag: "--max-ratio", kind: "float" },
    { flag: "--ratchet", kind: "true" },
    { flag: "--file", kind: "str" },
    { flag: "--since", kind: "str" },
    { flag: "--json", kind: "true" },
    { flag: "--packages", kind: "true" },
  ], PROG, USAGE, HELP);
  const maxRatio = args.max_ratio as number | null;
  const top = args.top as number;
  const file = args.file as string | null;
  const since = args.since as string | null;

  if (args.ratchet && since) {
    err("--ratchet and --since are mutually exclusive: --ratchet enforces the standing gate, "
      + "--since reports movement");
    return 2;
  }

  const rows = scan(collect());

  // Validated before any question is answered, and in EVERY mode: an exemption with no
  // justification, or one naming a file that no longer exists, is a defect regardless of what the
  // caller asked for. Exit 2, distinct from the gate's exit 1 — this is a broken instrument, not a
  // failing measurement.
  const errors = exceptionErrors(rows);
  if (errors.length) {
    err(`FAIL: CEILING_EXCEPTIONS is invalid (${errors.length} problem(s)):`);
    for (const e of errors) err(`  ✗ ${e}`);
    return 2;
  }

  // --since returns before --file is ever read, so the pair silently answered the question the
  // caller did not ask. Refused loudly instead, the same way --ratchet-without--max-ratio and an
  // invalid CEILING_EXCEPTIONS are (review 2026-08-28, PR 99) — exit 2 is this file's "you asked
  // the instrument something it cannot answer", distinct from the gate's failing-measurement 1.
  if (since && file) {
    err("--since and --file are mutually exclusive: --since reports movement, --file reports one file");
    return 2;
  }

  if (since) {
    const moved = movement(since, rows);
    if (args.json) out(dumpsIndent(moved.map(moveJson), 2));
    else reportMovement(since, moved, maxRatio);
    return 0;
  }

  if (file) {
    const matches = rows.filter((r) => r.file.endsWith(file));
    if (!matches.length) {
      err(`no such production file: ${file}`);
      return 2;
    }
    // AMBIGUITY IS EXIT 2, NEVER A SILENT PICK (DR-51, 2026-08-30). Taking whichever matching file
    // sorted worst and reporting it as THE answer meant `--file Topology.kt` with a twin basename
    // graded a file the caller never named — the per-target acceptance gate (HD-24) then passes or
    // fails on the wrong file with nothing red anywhere.
    if (matches.length > 1) {
      err(`--file ${pyRepr(file)} is ambiguous — ${matches.length} production files match; name it uniquely:`);
      for (const r of matches) err(`  ${r.file}`);
      return 2;
    }
    const target = matches[0];
    out(dumpsIndent(rowJson(target), 2));
    // An excepted file is graded against its own ceiling here too, and says so out loud. A
    // per-target check that passed silently under an exemption would be the quietest possible
    // place for one to hide.
    const cap = ceilings().get(target.file);
    if (cap !== undefined) {
      const why = (CEILING_EXCEPTIONS.find(([path]) => path === target.file) as [string, number, string])[2];
      out(`\nCEILING EXCEPTION — graded against ceiling ${floatStr(cap)}, not --max-ratio:`);
      out(textwrapFill(why, 96, "  ", "  "));
    }
    // --max-ratio GATES a single file too. It used to be silently ignored here, so
    // `--file <a HIGH file> --max-ratio 1.8` printed the offending ratio and still exited 0 —
    // a per-target gate that could not fail is the same fake green this scan exists to find.
    // Per-target acceptance (HD-24) is exactly this call, so it has to be able to go red.
    if (maxRatio !== null) {
      const limit = cap !== undefined ? cap : maxRatio;
      if (target.ratio > limit) {
        const named = cap !== undefined ? "its ceiling " : "";
        err(`FAIL: ${target.file} ratio ${floatStr(target.ratio)} is above ${named}${floatStr(limit)}`);
        return 1;
      }
    }
    return 0;
  }

  if (args.packages && !(args.json || top || args.ratchet || file || since)) {
    reportPackages(packageCensus(rows), rows.length);
    return 0;
  }

  if (args.ratchet) {
    if (maxRatio === null) {
      err("--ratchet needs --max-ratio: the leg reports the standing debt above a stated threshold and "
        + "grades the ceiling exceptions against it, and a threshold that is not stated at the call "
        + "site is not auditable from the gate's own output.");
      return 2;
    }
    return ratchet(rows, maxRatio);
  }

  if (args.json) {
    out(dumpsIndent((top ? rows.slice(0, top) : rows).map(rowJson), 2));
  } else {
    const shown = top ? rows.slice(0, top) : rows.filter((r) => r.band !== "low");
    // `denom` is the divisor actually used, so ratio = C / denom always reproduces by hand; a
    // trailing * marks a row where the neighbourhood median was below the floor and the floor
    // was substituted, i.e. the score is graded against the tree's scale rather than against
    // that file's own neighbours.
    out(`${ljust("file", 58)} ${rjust("C", 7)} ${rjust("denom", 7)} ${rjust("ratio", 6)}  band`);
    for (const r of shown) {
      out(
        `${shortName(r.file, 58)} `
        + `${rjust(fixed(r.C, 0), 7)} ${rjust(fixed(r.denominator, 0), 6)}${r.denominator_floored ? "*" : " "} `
        + `${rjust(fixed(r.ratio, 2), 6)}  ${r.band}`,
      );
    }
    const high = rows.filter((r) => r.band === "HIGH");
    const med = rows.filter((r) => r.band === "moderate");
    out(`\n${rows.length} files | HIGH ${high.length} | moderate ${med.length} | low ${rows.length - high.length - med.length}`);
    // V4-93: the package row of the census, on every run of the table.
    reportPackages(packageCensus(rows), args.packages ? rows.length : 8);
    reportExceptions(rows);
  }

  if (maxRatio !== null) {
    // An excepted file is graded against its recorded ceiling; everything else against the gate.
    // A ceiling is not a blanket — a file that RISES above its own recorded number still fails.
    const caps = ceilings();
    const over = rows.filter((r) => r.ratio > (caps.get(r.file) ?? maxRatio));
    if (over.length) {
      err(`\nFAIL: ${over.length} file(s) above their limit (gate ${floatStr(maxRatio)}):`);
      for (const r of over) {
        const cap = caps.get(r.file);
        const limit = cap !== undefined ? `ceiling ${floatStr(cap)}` : `max ${floatStr(maxRatio)}`;
        err(`  ${r.file}  ratio=${floatStr(r.ratio)}  C=${floatStr(r.C)}  (over ${limit})`);
      }
      return 1;
    }
    out(
      `\nOK: every file is at or below ratio ${floatStr(maxRatio)}, `
      + `and all ${CEILING_EXCEPTIONS.length} exception(s) are within their own ceiling`,
    );
  }
  return 0;
}

if (import.meta.main) process.exit(main(process.argv.slice(2)));
