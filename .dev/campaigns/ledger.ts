#!/usr/bin/env bun
/**
 * THE LEDGER CLI — the only channel to campaign state.
 *
 *   bun .dev/campaigns/manifest.ts <ledger.toml> <command> [args]
 *
 * Raw tool edits are blocked by the repo-local Bun channel hook. The pre-commit mirror checks
 * actual staged bytes against the last validated CLI mutation's hash. Raw edits skip the lock,
 * validation, and comment preservation that carry every decision and resume pointer.
 *
 * The ledger path is the first argument, matching the fleet CLI's shape, so one binary serves any
 * number of campaigns.
 *
 * LINEAGE (recorded 2026-09-18). This is the INTERIM runner: projects vendor it and keep working
 * until `the-ledger` replaces it. It is a REFERENCE and a record of incidents, not a model for its
 * successor — operator ruling 2026-09-18: the new CLI is not bounded by prior versions of itself,
 * so nothing here should be carried forward merely because it is here.
 *
 * The doctrine is ported from torad-fleet's .dev/campaigns/manifest.py, not its fleet surface; the
 * split is stated in ledger-core.ts's header.
 *
 * The hardening in this file and ledger-core.ts came from two sources, and WHERE A COMMENT BELOW
 * NAMES A SEAT, THAT SEAT FOUND IT — deliberately not summarized into a list here, because a
 * hand-authored summary over hand-authored attributions is a second denominator over the first,
 * which is the defect this CLI keeps filing rows about:
 *   .dev/campaigns/REVIEW-eli-ledger-2026-09-17.md — the adversarial review of the ledger system
 *   (provenance and its one sanctioned repair, the checked `touched` contract, newline injection
 *   into notes, duplicate ids, release-stale outside the lock, lock/temp debris); and the scout
 *   seats' field findings from 2026-09-18 (the `focus` seat pointer, per-file receipt blob hashes,
 *   the exit-code-after-a-pipe law, the pattern-honesty law).
 *
 * Vendoring gate: `selftest` runs 145 checks over a scratch ledger — including the wedged-proof
 * read-only path, the orchestrator-gated `reattest` repair, the review-milestone walls
 * (`deliver`, `<M>-review` as ONE row for one builder, no review note per row, the generated
 * `review` brief, `followup`, the `plan` growth ceiling and the no-plan refusal) and the
 * machinery-seat classifier in its permissive form — and passes 145/145 at the
 * revision this line was written. Run it at every
 * vendoring, and note the lineage in the vendoring repo. The ontology those walls enforce is
 * stated once, above `milestoneOf`, in the operator's words.
 */

import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import {
  coexistsWithPython,
  findBlock,
  headerLines,
  isItemStatus,
  ITEM_STATUSES,
  LedgerError,
  locateItems,
  mutate,
  notesOf,
  parseOrThrow,
  readLines,
  readLinesLoose,
  reattestLedger,
  toml,
  today,
  type ItemBlock,
  type ItemStatus,
} from "./ledger-core.ts";
import {
  assertItemMandates,
  handleLedgerEarn,
} from "./ledger-earn.ts";
import {
  parseDepends,
  parseRequires,
  parseReviews,
  MAX_REVIEWS_PER_TARGET,
} from "./earn-core.ts";

const USAGE = `usage: bun .dev/campaigns/manifest.ts <ledger.toml> <command> [args]
       bun .dev/campaigns/manifest.ts laws      every campaign's laws, the one verb that takes no ledger

read
  list [--status S] [--phase P]     compact table of items
  get <ID>                          one item with its notes (~15 lines, not the whole file)
  next                              the next actionable item
  laws                              the law sheet from the ledger header
  packet <ID>                       a self-contained dispatch brief with computed fences
  phase-status <P>                  one line per item in a phase (milestone) with its status
  touched <ID>                      the latest receipt's touched files, one per line

write
  add --id I --phase P --title T [--files a,b] [--verify V] [--status S]
                                    P is a milestone, or <M>-review — the review milestone attached to a
                                    DELIVERED milestone M; a delivered milestone takes no rows
  deliver <P> --sha <sha>           orchestrator only: every row of P done and pushed at <sha>; the ONE
                                    review runs on that sha; its fixes are rows of <P>-review, worked by
                                    one builder while the others continue with the next milestone
  plan <N>                          orchestrator only: the planned row count, in the header; add refuses
                                    a milestone row once the ledger holds 2N of them; run again to raise
                                    it — the raise is dated in the header
  review <P>                        the review BRIEF for a delivered milestone: its goal (the rows and
                                    their verify lines), three named lenses, PASS as the expected
                                    outcome, and the two filing channels — ONE fix row or followup
  followup "text"                   a dated header line for a finding that is not a fix row: found
                                    mid-milestone (no review per row), seen through no lens, or
                                    after a review closed
  set-status <ID> <status>          ${ITEM_STATUSES.join(" | ")}
  verify-phase <P> "gate evidence"  orchestrator only: every done item in phase P → verified,
                                    one dated note each (the milestone gate, not a per-row rerun)
  note <ID> "text"                  append a dated note to ONE ROW (never rewrites history)
  add-note "text"                   append a dated note to the CAMPAIGN (header) — a lesson or a
                                    measurement with no row. Not a law: laws are injected into every
                                    seat's SessionStart, so file a rule with add-law and nothing else
  depends <ID> <dep[,…]>
  require <ID> <done|verified> <slug[,…]>
  require-set <ID> <done|verified> <slug[,…]>
                                    exact-set variant (can remove slugs; empty removes line)
  remedy <ID>
  receipt <ID> --cmd C --exit N --tests N --touched a,b [--tail "..."] [--seat S]
                                    the builder's proof: exact command, exit, test count, plain-path
                                    touched files, output tail. "done" is refused without one at exit 0
                                    --deleted a,b records removed tracked files so stage can stage the removal
                                    --seat S (or $LEDGER_SEAT) is checked against the claim: a row claimed
                                    by one seat and delivered by another is the collision nothing caught
                                    scratch paths (**/*.tmp.ts, **/.tmp-*) are refused: the touched list is what gets committed
  stage <ID>                        orchestrator: git add exactly the receipt's touched files as they are
                                    on disk now, then the ledger+proof pair; refuses only a dirty index and
                                    a commit that would not load (no hashes, no moved-bytes refusal)
  focus <ID> [--seat S]             write this seat's active pointer (re-anchor after compaction)
  landed <ID> [sha]                 orchestrator, after the commit: set-compare the receipt's touched+deleted lists
                                    with git show --name-only <sha> (default HEAD), both directions; exit 1 on a miss
  reattest                          orchestrator: re-bind the proof to the ledger bytes after a wedge
  init "title" --rows N             orchestrator: create a new ledger (header + proof); the plan is declared at birth
  claim <ID> <seat>                 record ownership with a liveness stamp (--seat S also accepted)
  release <ID>                      release one named claim — the repair for a wrong claim and the
                                    honest way to hand a row over; release-stale can only judge age
  release-stale [--minutes N]       release claims older than N minutes (default 60)
  list --plain / get <ID> --raw     machine shapes, byte-identical to manifest.py's list and get
                                    block: the id first on each line, the item block as filed
  migrate-claims [--dry-run]        the cutover migration: write each in_flight row's last
                                    manifest.py CLAIM note into claimed_by/claimed_at. Idempotent;
                                    re-run it as the last act before deleting that CLI
  add-law "text"                    append a law to the header
  amend-header <old> <new>          replace a line in the header
  amend <ID> [--title T] [--verify V] [--files a,b]
                                    rewrite dispatch fields (grant-gated; old values auto-noted)

check
  audit [--deep]                    the record against reality: finished-but-unreported rows, closed rows
                                    with no receipt, claims that are not seat names, fences that match
                                    nothing, live rows sharing a file, scratch paths on a receipt.
                                    --deep adds which done rows are not yet in a commit. Exits 1 on findings
  validate                          parse the ledger and report item counts
  selftest                          exercise the CLI against a temporary ledger`;

// ── argument helpers ──────────────────────────────────────────────────────────────────────────

function flag(argv: readonly string[], name: string): string | null {
  const index = argv.indexOf(`--${name}`);
  if (index === -1) return null;
  return argv[index + 1] ?? null;
}

function required(argv: readonly string[], name: string): string {
  const value = flag(argv, name);
  if (value === null) throw new LedgerError(`--${name} is required`);
  return value;
}

/** The arguments that are neither a `--flag` nor a flag's value. */
function noFlagPositionals(argv: readonly string[]): string[] {
  const out: string[] = [];
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i]!.startsWith("--")) { i += 1; continue; }
    out.push(argv[i]!);
  }
  return out;
}

function positional(argv: readonly string[], index: number, what: string): string {
  const value = argv[index];
  if (value === undefined) throw new LedgerError(`expected ${what}`);
  return value;
}

/** Positionals beyond the ones a verb takes are refused by name: a space-separated --touched list
 *  once recorded ONE of eight files at exit 0 and read as complete (gs-claude-app, 2026-09-18). */
function noExtraPositionals(argv: readonly string[], allowed: number): void {
  const extras: string[] = [];
  let seen = 0;
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i]!;
    if (a.startsWith("--")) { i += 1; continue; }
    seen += 1;
    if (seen > allowed) extras.push(a);
  }
  if (extras.length > 0) throw new LedgerError(`unexpected extra argument(s): ${extras.join(" ")} — lists are ONE comma-separated value (--touched a,b,c)`);
}

// ── read commands ─────────────────────────────────────────────────────────────────────────────

const STATUS_MARK: Record<ItemStatus, string> = {
  todo: "·",
  in_flight: "▸",
  blocked: "■",
  done: "○",
  verified: "●",
};

function renderList(blocks: readonly ItemBlock[], status: string | null, phase: string | null): string {
  const rows = blocks
    .map((block) => block.item)
    .filter((item) => (status === null || item.status === status))
    .filter((item) => (phase === null || item.phase === phase));

  if (rows.length === 0) return "no matching items";

  const widest = Math.max(...rows.map((item) => item.id.length));
  const lines = rows.map((item) => {
    const claim = item.claimedBy === undefined ? "" : `  @${item.claimedBy}`;
    return `${STATUS_MARK[item.status]} ${item.id.padEnd(widest)}  ${item.status.padEnd(9)}  ${item.phase.padEnd(10)}  ${item.title}${claim}`;
  });

  const tally = ITEM_STATUSES.map((candidate) => {
    const count = blocks.filter((block) => block.item.status === candidate).length;
    return count === 0 ? null : `${candidate} ${count}`;
  }).filter((entry) => entry !== null);

  return `${lines.join("\n")}\n\n${rows.length} shown · ${tally.join(" · ")}`;
}

/**
 * VENDORING DELTA 11 (splice V4-143 Phase C, 2026-09-18): THE MACHINE SHAPES CALLERS PARSE. Three
 * callers read manifest.py's output with regexes, not through a library: law-check.mjs and
 * build-punch-list.mjs take the FIRST whitespace token of each `list` line as the row id and match
 * `^files = [...]$` / `^status = "..."$` / `^# [date] CLAIM: owner=` against `get`. This CLI's
 * human `list` starts every line with a status glyph, so those callers would read `▸` as the id,
 * filter it out, and see ZERO rows — build-punch-list throws, and law-check stops at its own
 * zero-rows guard (DID NOT RUN, exit 2, law-check.mjs:39; measured with the flags removed). Both
 * fail loud, so the cost was an outage of both callers at the cutover, not a silent pass — an
 * earlier version of this comment claimed law-check would pass, inferred from its reader without
 * reading the guard. Phase B compared parsed id and status across the CLIs, not
 * the byte shape a caller consumes, so it could not see this.
 *
 * `list --plain` is manifest.py's line (manifest.py:3540: id padded to 6, phase to 2, status to 9,
 * the RAW title value's first 90 code points, an owner suffix on in_flight rows). `get --raw` is the
 * item block exactly as it sits in the file (manifest.py:3568). Both are additive: the human views
 * are unchanged, and the cutover swaps each caller's argv without touching its parser.
 */
function renderPlainList(lines: readonly string[], blocks: readonly ItemBlock[], status: string | null, phase: string | null): string {
  const out: string[] = [];
  for (const block of blocks) {
    const body = lines.slice(block.start, block.end);
    let rawPhase = "";
    let rawTitle = "";
    for (const line of body) {
      if (line.startsWith("phase")) rawPhase = line.includes('"') ? (line.split('"')[1] ?? "") : "";
      else if (line.startsWith("title")) {
        // Python's str.strip() then strip('"'): whitespace first, then every quote at either end.
        const value = line.slice(line.indexOf("=") + 1).trim().replace(/^"+|"+$/g, "");
        rawTitle = Array.from(value).slice(0, 90).join("");
      }
    }
    if (status !== null && block.item.status !== status) continue;
    if (phase !== null && rawPhase !== phase) continue;
    const owner = block.item.claimedBy ?? lastClaimOwner(lines, block);
    const suffix = block.item.status === "in_flight" && owner !== undefined ? ` @${Array.from(owner).slice(0, 12).join("")}` : "";
    out.push(`${block.item.id.padEnd(6)} ${rawPhase.padEnd(2)} ${block.item.status.padEnd(9)} ${rawTitle}${suffix}`);
  }
  return out.join("\n");
}

/** The owner named by a row's last manifest.py `CLAIM: owner=` note, or undefined when it has none
 *  or a later `CLAIM-RELEASED` note ended it (manifest.py's `_block_owner`, delta 14). */
function lastClaimOwner(lines: readonly string[], block: ItemBlock): string | undefined {
  let owner: string | undefined;
  for (const note of notesOf(lines, block)) {
    const claimed = /CLAIM: owner=(\S+)/.exec(note);
    if (claimed) owner = claimed[1];
    else if (note.includes("CLAIM-RELEASED")) owner = undefined;
  }
  return owner;
}

/**
 * manifest.py's `_retirement_marker` (delta 14): the LAST structured `RETIRED:` / `RETIRE-LIFTED:`
 * note decides; with no structured marker, the first comment line matching the legacy text scan
 * retires the row — the floor that keeps pre-grammar prose rulings protected.
 */
function retirementMarker(notes: readonly string[]): string | null {
  let structured: string | null = null;
  let structuredLine: string | null = null;
  let legacy: string | null = null;
  for (const note of notes) {
    const stripped = note.trim();
    if (!stripped.startsWith("#")) continue;
    const m = /^#\s*(?:\[[^\]]*\]\s*)?(RETIRED|RETIRE-LIFTED):/.exec(stripped);
    if (m) { structured = m[1]!; structuredLine = stripped; continue; }
    if (legacy === null && /\bRETIRED\b|do not re-?queue/i.test(stripped)) legacy = stripped;
  }
  if (structured === "RETIRED") return structuredLine;
  if (structured === "RETIRE-LIFTED") return null;
  return legacy;
}

/** manifest.py's `_has_activity_after_claim`: any non-blank line after the row's last CLAIM note,
 *  bar the ATTEST-START manifest.py writes with its claim (delta 16). No CLAIM note, no activity. */
function activityAfterClaim(lines: readonly string[], block: ItemBlock): boolean {
  let claimAt = -1;
  for (let j = block.start; j < block.end; j += 1) {
    if ((lines[j] ?? "").trimStart().startsWith("#") && (lines[j] ?? "").includes("CLAIM: owner=")) claimAt = j;
  }
  if (claimAt === -1) return false;
  let j = claimAt + 1;
  if (j < block.end && (lines[j] ?? "").includes("] ATTEST-START:")) j += 1;
  for (; j < block.end; j += 1) if ((lines[j] ?? "").trim() !== "") return true;
  return false;
}

/** Python's `round(x, 1)` as json.dumps prints it: half-even on an exact tie, always one decimal at least. */
function pyRound1(x: number): string {
  const twenty = x * 20;
  const down = Math.floor(x * 10);
  const value = Number.isInteger(twenty) && Math.abs(twenty) % 2 === 1
    ? (down % 2 === 0 ? down : down + 1) / 10
    : Number(x.toFixed(1)); // toFixed rounds the exact double, as Python does
  return Number.isInteger(value) ? value.toFixed(1) : String(value);
}

/** manifest.py's CLAIM note body (`_claim_note`): the diary line two readers take the owner from. */
function claimNote(seat: string, at: Date): string {
  return `CLAIM: owner=${seat} at=${at.toISOString().replace(/\.\d{3}Z$/, "Z")}`;
}

/** Clear a claim: fields gone, an in_flight row back to todo, and a CLAIM-RELEASED note (delta 14). */
function withoutClaim(lines: readonly string[], id: string, owner: string, why: string): string[] {
  let next = withField(lines, findBlock(locateItems(lines), id), "claimed_by", null);
  next = withField(next, findBlock(locateItems(next), id), "claimed_at", null);
  const block = findBlock(locateItems(next), id);
  if (block.item.status === "in_flight") next = withStatus(next, block, "todo");
  return withNote(next, findBlock(locateItems(next), id), `CLAIM-RELEASED (${why}): owner=${owner}`);
}

function renderItem(lines: readonly string[], block: ItemBlock): string {
  const { item } = block;
  const notes = notesOf(lines, block);
  const body = [
    `${STATUS_MARK[item.status]} ${item.id}  [${item.status}]  phase=${item.phase}`,
    ``,
    `  ${item.title}`,
    ``,
    `  files  : ${item.files.length === 0 ? "(none declared)" : item.files.join(", ")}`,
    `  verify : ${item.verify === "" ? "(none declared)" : item.verify}`,
  ];
  if (item.claimedBy !== undefined) {
    body.push(`  claim  : ${item.claimedBy} since ${item.claimedAt ?? "unknown"}`);
  }
  const depends = parseDepends(notes);
  const req = parseRequires(notes);
  const reviews = parseReviews(notes);
  if (depends.length > 0) body.push(``, `  depends: ${depends.join(", ")}`);
  if (req.ready.length || req.verified.length) {
    body.push(``, `  mandates:`);
    if (req.ready.length) body.push(`    done/ready : ${req.ready.join(", ")}`);
    if (req.verified.length) body.push(`    verified   : ${req.verified.join(", ")}`);
  }
  if (reviews.length > 0) {
    body.push(``, `  reviews: ${reviews.length}/${MAX_REVIEWS_PER_TARGET}`);
    for (const r of reviews) body.push(`    · #${r.n} ${r.verdict} ${r.artifact}`);
  }
  if (notes.length > 0) {
    body.push(``, `  notes (append-only — the construction diary):`);
    for (const note of notes) body.push(`    ${note}`);
  }
  return body.join("\n");
}

/**
 * The next actionable item: an in-flight one if any is open (finish before starting), otherwise
 * the first todo. Blocked items are never "next" — a blocked item needs a ruling, not a builder.
 */
function pickNext(blocks: readonly ItemBlock[]): ItemBlock | null {
  return (
    blocks.find((block) => block.item.status === "in_flight") ??
    blocks.find((block) => block.item.status === "todo") ??
    null
  );
}

/**
 * A dispatch packet. Self-contained by construction (concept #945 §5): laws, the item text, the
 * writable fence, the verify gate, and the reply contract. A packet that makes the reader open
 * the ledger to understand it has already failed — the point is that a fresh context can act.
 */
/**
 * VENDORING DELTA 4 (splice V4-143, 2026-09-18): a law line is `# LAW:` OR `# LAW [date]:`. The
 * splice ledger was written by manifest.py, whose add-law dates the law in the prefix; 58 of its 59
 * laws carry that form. With the `# LAW:` test alone, `laws` printed ONE of them — and `laws` is
 * what SessionStart injects into every seat, so a cutover would have silently dropped 58 laws.
 * One predicate for both readers, so the two can never disagree about what a law is.
 */
const LAW_LINE = /^#\s*LAW(\s*\[[^\]]*\])?:/;

function isLawLine(line: string): boolean {
  return LAW_LINE.test(line.trimStart());
}

// VENDORING DELTA 12 (splice V4-143 D2): one ledger's law sheet, exported so splice's entry
// (.dev/campaigns/manifest.ts) aggregates every ledger for a pathless `laws` with the SAME reader the
// `laws` verb uses — never a second law predicate that could drift from this one.
export function lawSheet(lines: readonly string[]): string[] {
  return headerLines(lines).filter(isLawLine);
}

function renderPacket(lines: readonly string[], block: ItemBlock): string {
  const { item } = block;
  const laws = headerLines(lines)
    .filter(isLawLine)
    .map((line) => `  ${line.replace(/^\s*#\s*/, "")}`);

  return [
    `ITEM ${item.id} — ${item.phase}`,
    ``,
    item.title,
    ``,
    `START (before the first edit):`,
    `  bun .dev/campaigns/manifest.ts <ledger> claim ${item.id} <your seat>`,
    `  bun .dev/campaigns/manifest.ts <ledger> focus ${item.id}      (re-anchors you after a compaction)`,
    ``,
    `WRITABLE FENCE (do not write outside this list; \`stage\` refuses anything else):`,
    item.files.length === 0
      ? `  (none declared — declare files before dispatching, or the fence is meaningless)`
      : item.files.map((file) => `  ${file}`).join("\n"),
    ``,
    `VERIFY (what YOU run before reporting done — scoped, never the full suite):`,
    `  ${item.verify === "" ? "(none declared — do not dispatch without one)" : item.verify}`,
    `  plus: bun run typecheck. Do NOT run the whole suite; it runs in CI once, on the milestone push.`,
    `  Review and the milestone exit gate happen ONCE per milestone, not on this row.`,
    ``,
    ...(() => {
      const notes = notesOf(lines, block);
      const depends = parseDepends(notes);
      const req = parseRequires(notes);
      const parts: string[] = [];
      if (depends.length) {
        parts.push(`DEPENDS (finish these first):`, ...depends.map((d) => `  ${d}`), ``);
      }
      // Mandates are milestone-level under the 2026-09-17 ruling; a packet never teaches the
      // per-row review workflow. Declared slugs still gate `verified` and stay visible in `get`.
      if (req.ready.length || req.verified.length) {
        parts.push(`MILESTONE MANDATES (they gate "verified", not this row): ${[...req.ready, ...req.verified].join(", ")}`, ``);
      }
      return parts;
    })(),
    `LAWS IN FORCE:`,
    laws.length === 0 ? `  (none in header)` : laws.join("\n"),
    ``,
    `REPORTING:`,
    `  1. Record your proof as a receipt (this is what makes your run trustworthy without a re-run):`,
    `     bun .dev/campaigns/manifest.ts <ledger> receipt ${item.id} --cmd "<exact verify command>" \\`,
    `       --exit <code> --tests <count> --touched <plain,paths,you,changed> --tail "<last lines of output>" --seat <your seat>`,
    `     The touched list is what the orchestrator stages; a file not on it is not committed, and a`,
    `     scratch file (*.tmp.ts, .tmp-*) on it is refused — it would be committed. --seat is checked`,
    `     against the claim, which is how a row claimed by one seat and delivered by another is caught.`,
    `  2. Set the row done (refused without a receipt at exit 0):`,
    `     bun .dev/campaigns/manifest.ts <ledger> set-status ${item.id} done`,
    `  3. Send exactly one line: "${item.id} done — see ledger".`,
    `  Long prose over the channel is the anti-pattern; the note IS the report.`,
    ``,
    `PREMISE CHECK:`,
    `  If anything in this packet contradicts the repo, REPORT it — do not obey it. A wrong`,
    `  premise from the orchestrator is still a wrong premise.`,
    ``,
    `DO NOT COMMIT. The orchestrator holds the single gated commit point.`,
  ].join("\n");
}

// ── write commands ────────────────────────────────────────────────────────────────────────────

/** `exit` is null for a receipt manifest.py filed: it records the files and their blobs, not a run. */
interface Receipt { exit: number | null; tests: number; touched: string[]; blobs: string[]; deleted: string[]; dblobs: string[] }

/**
 * ANCHORED AT THE NOTE, NEVER GREEDY THROUGH IT.
 *
 * The first spelling was `/RECEIPT cmd=.* exit=(\d+) tests=(\d+) touched=(\S+)…/`, and a receipt is
 * ONE line that ends in the builder's own `--tail` prose. `cmd=.*` is greedy, so the engine
 * backtracks to the LAST place the fields fit — which is inside the tail whenever the tail happens
 * to contain them. Measured 2026-09-18 on a fixture: a receipt filed `--exit 1 --tests 0
 * --touched src/a.ts` whose tail read "…rerun exit=0 tests=15 touched=src/evil.ts" parses as
 * exit 0, 15 tests, touched=src/evil.ts. That is the whole gate defeated by a sentence: `done` is
 * refused only on a red receipt, and `stage` adds exactly `touched` — so a red run reads green and
 * the staging list comes out of free prose.
 *
 * `cmd` and `tail` are both written with `JSON.stringify`, so they are quoted strings with escapes.
 * Matching `cmd` as one and pinning the fields immediately behind it leaves the tail nowhere to
 * reach, and the `^#  <date>` anchor means a match cannot even START inside the tail. All 73
 * receipts in the live ledger parse under this — enumerated from the file, not assumed.
 */
const RECEIPT_RE =
  /^#\s*(?:\d{4}-\d{2}-\d{2}|\[\d{4}-\d{2}-\d{2}\])\s+RECEIPT cmd="(?:[^"\\]|\\.)*" exit=(\d+) tests=(\d+) touched=(\S+)(?: blobs=(\S*))?(?: deleted=(\S+)(?: dblobs=(\S+))?)?/;

/**
 * VENDORING DELTA 13 (splice V4-143 D2, 2026-09-18): a row note is dated `# [date]`, manifest.py's
 * form (withNote), so both receipt patterns take the bracket too. manifest.py's own receipt,
 * `RECEIPT files=a,b blobs=x,y`, is READ as a receipt with no exit: 252 of the 262 receipt-shaped
 * notes across the 13 ledgers are that form, and an unread one made every closed row an audit
 * finding and every py-receipted in_flight row undeliverable after the cutover. The other 10 are
 * prose ("RECEIPT covers the 10 existing files; ..."), so the bracketed half of the shape test
 * wants a field, not the bare word: a prose note is not a receipt that failed to parse.
 */
const PY_RECEIPT_RE = /^#\s*\[\d{4}-\d{2}-\d{2}\]\s+RECEIPT files=(\S+) blobs=(\S+)$/;

/** A note that is TRYING to be a receipt. Anything matching this must also match RECEIPT_RE or PY_RECEIPT_RE. */
const RECEIPT_SHAPED_RE = /^#\s*(?:\d{4}-\d{2}-\d{2}\s+RECEIPT\b|\[\d{4}-\d{2}-\d{2}\]\s+RECEIPT (?:cmd|files)=)/;

function latestReceipt(notes: readonly string[]): Receipt | undefined {
  for (let i = notes.length - 1; i >= 0; i -= 1) {
    const note = notes[i] ?? "";
    const m = RECEIPT_RE.exec(note);
    if (m) return { exit: Number(m[1]), tests: Number(m[2]), touched: m[3]!.split(",").filter((p) => p !== "-"), blobs: (m[4] ?? "").split(",").filter(Boolean), deleted: (m[5] ?? "").split(",").filter(Boolean), dblobs: (m[6] ?? "").split(",").filter(Boolean) };
    const py = PY_RECEIPT_RE.exec(note);
    if (py) return { exit: null, tests: 0, touched: py[1]!.split(","), blobs: py[2]!.split(","), deleted: [], dblobs: [] };
    // SILENCE HERE WOULD REACH BACK FOR AN OLDER, GREENER RECEIPT. Scanning backwards for the first
    // line that parses means a malformed newest receipt is not an error, it is a fallback — and the
    // row's last RED run would be answered by its previous GREEN one, which is the same lie the
    // anchored parse exists to stop, arriving by a different door. A note that is shaped like a
    // receipt and does not parse is a refusal, and the refusal names the line.
    if (RECEIPT_SHAPED_RE.test(note)) {
      throw new LedgerError(
        `a receipt note does not parse and an older one will NOT be used in its place:\n  ${note.slice(0, 200)}\n` +
          `  The form is: RECEIPT cmd="<json>" exit=<n> tests=<n> touched=<csv> [deleted=<csv>] [tail="<json>"].\n` +
          `  Re-file it with \`receipt <ID> --cmd … --exit … --tests … --touched …\`; never hand-write one.`,
      );
    }
  }
  return undefined;
}

/**
 * The scratch-file convention, spelled the way the other three gates spell it (M8.5): tsconfig.json
 * `**\/*.tmp.ts`, biome.jsonc `!**\/*.tmp.ts` + `!**\/.tmp-*`, eslint.config.ts the same pair. The
 * ledger is the fourth gate and was the one that did not know: the touched list IS the staging list,
 * so a scratch path on it asks the orchestrator to COMMIT a probe file. Measured 2026-09-18, M7.4's
 * first receipt carried `tests/architecture/probe.tmp.ts` among 100 entries and `stage` died on
 * `pathspec … did not match any files` — the lucky outcome, because the probe was already deleted.
 */
function isScratchPath(path: string): boolean {
  const segments = path.split("/");
  return (segments.at(-1) ?? "").endsWith(".tmp.ts") || segments.some((segment) => segment.startsWith(".tmp-"));
}

function repoRootOf(ledgerPath: string): string {
  const r = Bun.spawnSync(["git", "-C", dirname(ledgerPath), "rev-parse", "--show-toplevel"], { stdout: "pipe", stderr: "pipe" });
  if (r.exitCode !== 0) throw new LedgerError(`${ledgerPath} is not inside a git repository`);
  return r.stdout.toString().trim();
}

function git(root: string, ...args: string[]): string {
  const r = Bun.spawnSync(["git", "-C", root, ...args], { stdout: "pipe", stderr: "pipe" });
  if (r.exitCode !== 0) throw new LedgerError(`git ${args.join(" ")} failed: ${r.stderr.toString().trim()}`);
  return r.stdout.toString();
}

/**
 * NO RECEIPT HASHES, NO BYTE COMPARISON AT STAGE, NO LANDED CHECK AT VERIFY — operator ruling
 * 2026-09-18, in the operator's words: "let's remove the unnecessary ceremony for good, the hashes,
 * the verdict, the review per row, that's bullshit we don't need any of that." A receipt records the
 * command, its exit, the test count and the files; `stage` stages those files as they are on disk;
 * `verify-phase` flips done rows with the CI evidence and checks nothing else. The milestone CI run is
 * the gate and a red run becomes fix rows. The operator had ruled the same at 00:59 the same day
 * ("bytes-moved is a warning, not a refusal"); M8.58 reversed it at 08:44 and MOD.35 hardened it at
 * 09:15, and the campaign spent the hours in between re-filing receipts over moved bytes, holding
 * rows at stage, and quiescing every seat to land one commit. That reversal is void and is not to be
 * repeated in this CLI or its successors.
 */

/** Blob hash of the file at HEAD, or null when HEAD does not track it. */
function headBlob(root: string, rel: string): string | null {
  const r = Bun.spawnSync(["git", "-C", root, "rev-parse", "--verify", "--quiet", `HEAD:${rel}`], { stdout: "pipe", stderr: "pipe" });
  return r.exitCode === 0 ? r.stdout.toString().trim() : null;
}

function inFence(rel: string, fence: readonly string[]): boolean {
  return fence.some((f) => f === rel || (f.endsWith("/") && rel.startsWith(f)));
}

/**
 * A FENCE IS A LIST OF WHAT TO INCLUDE AND CANNOT NOTICE WHAT IS MISSING — §24 in the staging path.
 *
 * `stage` refuses a path outside the fence and a path whose bytes moved. An UNTRACKED file that
 * nobody named is neither: it is invisible. Measured 2026-09-18: the orchestrator created
 * `ast_pins.ts` as its own content, amended M8.2's fence to seven files, and never listed the file
 * it had just created — so `architecture_rules.ts` was committed with `import { astPin } from
 * "./ast_pins"` while `ast_pins.ts` stayed untracked, and HEAD could not load for four commits.
 * Everything needed to refuse that was in hand at stage time: the file, its imports, and git.
 *
 * Relative specifiers ONLY (`./`, `../`). A package specifier is a different question with a
 * different answer, and `noUndeclaredDependencies` already taught this repo what a rule that cannot
 * tell "unresolvable" from "undeclared" costs.
 */

const TS_SOURCE_RE = /\.(?:ts|tsx|mts|cts)$/;

/**
 * The candidates bun would try for a relative specifier, in order. A `.js` specifier resolving to a
 * `.ts` file is the TypeScript convention and has to be tried, or a correct import reads as broken.
 */
function resolutionCandidates(specPath: string): string[] {
  const out = [specPath];
  const swapped = specPath.replace(/\.(m|c)?js$/, (_m, p1: string | undefined) => `.${p1 ?? ""}ts`);
  if (swapped !== specPath) out.push(swapped);
  for (const ext of ["ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs", "json"]) {
    out.push(`${specPath}.${ext}`, `${specPath}/index.${ext}`);
  }
  return out;
}

/**
 * Relative imports of `file` that would not resolve inside a commit containing exactly `present`.
 * Returns one line per offender, already phrased for a refusal.
 */
function unresolvableImports(root: string, file: string, present: ReadonlySet<string>): string[] {
  if (!TS_SOURCE_RE.test(file)) return [];
  let text: string;
  try {
    text = readFileSync(join(root, file), "utf8");
  } catch {
    return []; // a deletion, or a file staged from the index: nothing to read, nothing to claim
  }
  /**
   * SCANNED BY BUN'S OWN TRANSPILER, NEVER BY A REGEX OVER THE TEXT — and the first version of this
   * check taught the lesson the expensive way. A regex that matches `from "…"` cannot tell an import
   * from a STRING THAT LOOKS LIKE ONE, and this repository is the worst possible place for that: its
   * architecture tests carry import statements as test DATA, and this very file writes one into a
   * fixture. Run against the live ledger, the regex version produced 16 findings and every one was
   * false — `tests/hooks/architecture-rules.test.ts imports "../../application/kill-switch"` (a
   * string inside an assertion), `ledger.ts imports "./lib/helper"` (this file's own selftest
   * fixture). `scanImports` parses, so a commented-out import, a single-quoted lookalike and a
   * template literal are all correctly nothing.
   *
   * KNOWN NARROWING, stated rather than hidden: `import type` is erased and does not appear here, so
   * a dangling TYPE-only import is invisible to this check. It breaks `tsc`, not the runtime, and
   * the incident that motivated the check was a value import.
   */
  const transpiler = new Bun.Transpiler({ loader: file.endsWith(".tsx") ? "tsx" : "ts" });
  let specifiers: string[];
  try {
    specifiers = transpiler.scanImports(text).map((record) => record.path).filter((path) => path.startsWith("."));
  } catch {
    return []; // unparseable source is a different failure, and one every other gate reports better
  }
  const problems: string[] = [];
  const dir = dirname(file);
  for (const spec of specifiers) {
    const base = join(dir, spec).replace(/\\/g, "/");
    // A FILE, never a directory: git tracks files, so `./event-payloads` matching the DIRECTORY
    // `packages/domain/src/event-payloads` read as untracked while its `index.ts` was tracked all
    // along — four false findings from one missing `isFile()`.
    const onDisk = resolutionCandidates(base).find((candidate) => statSync(join(root, candidate), { throwIfNoEntry: false })?.isFile() === true);
    if (onDisk === undefined) {
      problems.push(`${file} imports "${spec}", which resolves to nothing on disk`);
      continue;
    }
    if (!present.has(onDisk)) {
      problems.push(`${file} imports "${spec}" → ${onDisk}, which is neither tracked nor staged by this row`);
    }
  }
  return problems;
}

/**
 * A FENCE ENTRY THAT NAMES A DIRECTORY HAS TO SAY SO WITH A TRAILING SLASH, and the check belongs
 * where the fence is WRITTEN rather than where it is enforced. `inFence` matches an exact path or a
 * `dir/` prefix, so `files = ["packages/domain"]` covers exactly one path that is not a file and
 * nothing underneath it: a fence that reads as declared, passes every parse, and matches nothing.
 * M8.10 carries `files = ["."]` today (2026-09-18) — a whole-tree row whose fence admits no file in
 * the repository, which `stage` would not reveal until the work was already done. An entry naming a
 * file that does not exist yet is ordinary and is left alone; only an existing DIRECTORY spelled
 * without its slash is refused.
 */
function assertFenceShape(root: string, files: readonly string[]): void {
  const bare = files.filter((file) => !file.endsWith("/") && statSync(join(root, file), { throwIfNoEntry: false })?.isDirectory() === true);
  if (bare.length === 0) return;
  throw new LedgerError(
    `these fence entries name directories but do not end in "/": ${bare.join(", ")}\n` +
      `  A fence matches an exact path or a "dir/" prefix, so as written they cover nothing at all.\n` +
      `  Write them as ${bare.map((file) => `${file === "." ? "<each directory>" : file}/`).join(", ")}${bare.includes(".") ? ' — "." is the whole repository and is never a fence; name the directories the row actually writes.' : ""}`,
  );
}

function assertReceipt(id: string, notes: readonly string[]): void {
  const r = latestReceipt(notes);
  if (r === undefined) throw new LedgerError(`${id}: "done" needs a receipt first — record your verify run with \`receipt ${id} --cmd ... --exit 0 --tests N --touched a,b\``);
  if (r.exit === null) throw new LedgerError(pyReceiptRefusal(id, "done"));
  if (r.exit !== 0) throw new LedgerError(`${id}: the latest receipt exited ${r.exit}; a row is done only on a green run`);
}

/** A manifest.py receipt names the files, never the run, so a gate that needs a green run cannot read one as green. */
function pyReceiptRefusal(id: string, gate: string): string {
  return `${id}: the latest receipt was filed by manifest.py — it records the files and their blobs, not the run, and ${gate} needs the run. ` +
    `Re-file it: \`receipt ${id} --cmd ... --exit 0 --tests N --touched a,b\``;
}

/** Free text is ONE line. A newline in a note is a TOML injection: `[[items]]` inside a note block
 *  became a verified ghost row that `validate` blessed (Eli F5, 2026-09-17). */
function oneLine(name: string, value: string): string {
  if (/[\r\n]/.test(value)) throw new LedgerError(`${name} must be a single line (newlines are refused: they would inject TOML into the ledger)`);
  return value;
}

function withStatus(lines: readonly string[], block: ItemBlock, status: ItemStatus): string[] {
  const next = [...lines];
  for (let index = block.start; index < block.end; index += 1) {
    if (/^\s*status\s*=/.test(next[index] ?? "")) {
      next[index] = `status = ${toml(status)}`;
      return next;
    }
  }
  next.splice(block.end, 0, `status = ${toml(status)}`);
  return next;
}

function withNote(lines: readonly string[], block: ItemBlock, text: string): string[] {
  const next = [...lines];
  next.splice(block.end, 0, `# [${today()}] ${text}`); // manifest.py's form, delta 13
  return next;
}

function withField(lines: readonly string[], block: ItemBlock, key: string, value: string | null): string[] {
  const next = [...lines];
  for (let index = block.start; index < block.end; index += 1) {
    if (new RegExp(`^\\s*${key}\\s*=`).test(next[index] ?? "")) {
      if (value === null) {
        next.splice(index, 1);
        return next;
      }
      next[index] = `${key} = ${toml(value)}`;
      return next;
    }
  }
  if (value !== null) next.splice(block.end, 0, `${key} = ${toml(value)}`);
  return next;
}

/**
 * VENDORING DELTA 8 (splice V4-143 Phase C, 2026-09-18): manifest.py's FENCE NORMALIZER, ported
 * for its behaviour rather than its text (manifest.py:4383 `_fence_prefix`). A fence entry is a
 * path, a directory or a GLOB, and the glob is the case the string comparison here could not see:
 * `gateway/app/src/test/kotlin/**` and `gateway/app/src/test/kotlin/DaemonStopDeadlineTest.kt` are
 * the SAME fence, share no equal string, and neither ends in `/`, so the old check found no
 * overlap. V4-139's own fence carries three such globs, so this is the live shape, not a synthetic
 * one. One trailing glob suffix is stripped, then trailing slashes.
 */
function fencePrefix(entry: string): string {
  const normalized = entry.trim().replace(/\\/g, "/").replace(/\/+$/, "");
  for (const suffix of ["/**", "/*", "**", "*"]) {
    if (normalized.endsWith(suffix)) return normalized.slice(0, -suffix.length).replace(/\/+$/, "");
  }
  return normalized;
}

/** The entries of [a] whose normalized prefix equals, or is a path-ancestor of, one of [b]'s. */
function fenceOverlap(a: readonly string[], b: readonly string[]): string[] {
  const shared: string[] = [];
  const others = b.map(fencePrefix).filter((prefix) => prefix !== "");
  for (const entry of a) {
    const prefix = fencePrefix(entry);
    if (prefix === "") continue;
    if (others.some((other) => prefix === other || prefix.startsWith(`${other}/`) || other.startsWith(`${prefix}/`))) {
      shared.push(entry);
    }
  }
  return shared;
}

// ── milestones, deliveries and review milestones ─────────────────────────────────────────────
//
// THE ONTOLOGY (operator ruling 2026-09-18, in the operator's words: "builders should work through
// items within a milestone without any code review. At milestone delivery, a review is done, if
// work needs to be fixed after that, it is fixed as a review milestone attached to the first one,
// and only one builder work on the fixes, the other continues with the next milestone").
//
//   MILESTONE         a phase whose name does not end in `-review`. Its rows are planned before
//                     dispatch and built by any number of builders with NO review inside it: a
//                     review note on a row of an undelivered milestone is refused.
//   DELIVERY          `deliver <M> --sha <sha>` (orchestrator): every row of M is done, the tree is
//                     pushed at <sha>, and the header records `DELIVERED M at <sha>`. After it, no
//                     row can be added to M. The review happens ONCE, here, on that sha.
//   REVIEW MILESTONE  the phase `<M>-review`, attached to M. It can be opened only after M is
//                     delivered; its rows are the review's fixes; ONE builder works it (a second
//                     seat's claim into it is refused) while the others continue with the next
//                     milestone. There is no review of a review: `<M>-review-review` is refused.
//   FOLLOW-UP         `followup "text"`: a dated header line for a finding that is not a fix row of
//                     an open review milestone — found mid-milestone, or after the review closed.
//
// Why these are walls and not advice, measured on the campaign that ruled them (2026-09-18): a
// plan of 15 rows became 143; the 37 rows of m1–m6 were built in under four hours and the next
// nine hours were rows minted by reviews of rows, audits of fixes and repairs of walls built
// mid-campaign, all through one committing seat. Each verb below refuses one link of that loop.

const REVIEW_SUFFIX = "-review";
const DELIVERED_RE = /^#\s*\d{4}-\d{2}-\d{2}\s+DELIVERED\s+(\S+)\s+at\s+(\S+)/;
const REVIEW_NOTE_RE = /^\s*(?:REVIEW(?:ER)?|VERDICT|FINDING)S?\b/i;

// THE GROWTH CEILING (operator, 2026-09-18, to ledger-lead: "we need a limit on the growth of the
// campaign items, we need a way to control how much work is done and how much the items grow";
// "without proper enforcement to keep agents on track and focused, it will happen, it always
// does"). `plan <N>` records the planned row count in the header; `add` refuses a MILESTONE row
// once the ledger holds 2N of them (review-milestone rows are outside the count: they are already
// one per delivered milestone). A raise is `plan <M>` again — orchestrator-only and dated in the
// header, so it is a recorded decision and never a default. Measured reason: median growth across
// campaigns was 2.85× the plan, and that included the review-born growth the walls above remove.
const PLANNED_RE = /^#\s*PLANNED\s+(\d+)\s+rows\b/;
const CEILING_FACTOR = 2;

/** The planned row count from the header, or null for a ledger that never declared one. */
function plannedRows(lines: readonly string[]): number | null {
  for (const line of headerLines(lines)) {
    const m = PLANNED_RE.exec(line);
    if (m !== null) return Number.parseInt(m[1] ?? "0", 10);
  }
  return null;
}

/** `m4-review` → `m4`; a plain milestone → null. */
function milestoneOf(phase: string): string | null {
  return phase.endsWith(REVIEW_SUFFIX) ? phase.slice(0, -REVIEW_SUFFIX.length) : null;
}

/** The sha a phase was delivered at, read from the header; null while it is still being built. */
function deliveredAt(lines: readonly string[], phase: string): string | null {
  for (const line of headerLines(lines)) {
    const m = DELIVERED_RE.exec(line);
    if (m !== null && m[1] === phase) return m[2] ?? null;
  }
  return null;
}

/*
 * MACHINERY IS ONE SEAT, AND THE ROW SAYS SO WITH ITS FENCE — MOD.45.
 *
 * Operator ruling 2026-09-18, after saying it a million times: `.claude/hooks/**`, `.dev/campaigns/**`,
 * `tests/hooks/**`, `tests/architecture/**`, `.githooks/**`, the lint config and tsconfig belong to
 * scout-campaign-mod alone. Builders work the MAIN CAMPAIGN, and when it has no open rows they are DONE
 * — a closed deliverable is the signal to stop, not the signal to audit the apparatus again. In one hour
 * the orchestrator opened nine machinery rows and dispatched eight of them to builders while m1–m7 sat
 * all-done, the deliverable having finished at 03:43 inside its estimate.
 *
 * ── DERIVED FROM THE FENCE, NEVER FROM A LIST OF ROW IDS ──
 *
 * A list of machinery row ids would need maintaining by the same seat that keeps drifting, and §24 says
 * a check whose denominator comes from a list it authors cannot fail for what the list omits. The fence
 * is already on every row, already validated by `assertFenceShape`, and already the thing `stage` acts
 * on, so it is the one field that cannot be out of date with the work.
 *
 * ── AND AN UNCLASSIFIED PATH FAILS BY NAME, WHICH IS THE PART THAT MATTERS ──
 *
 * MEASURED FIRST, over all 148 rows of the live board: 44 distinct fence roots. A first draft used a
 * machinery allowlist and `every`, and it silently classified MOD.42, MOD.44 and MOD.45 as product —
 * because `tests/campaigns/` and `.dev/earn-artifacts/` were not on the list. THAT IS THE FAILURE MODE
 * THE WALL EXISTS TO PREVENT, reproduced inside the wall on its first draft: an incomplete allowlist
 * plus `every` means an unlisted path votes "product" and the gate quietly stops applying.
 *
 * ── AND THE STRICTNESS LIVES IN A TEST, NOT IN THE CLI ──
 *
 * The second draft made an unclassified path THROW here. It ran, and it broke sixteen selftest checks on
 * fixtures fenced `src/a.ts,tests/x.test.ts` — a perfectly ordinary product fence with no listed root.
 * A classifier that refuses the verb on any path it has not seen is a false-block machine aimed at the
 * main campaign, which is the availability failure the operator has now ruled against four times. So
 * the runtime answer is PERMISSIVE — unclassified means not-machinery, the row stays dispatchable and
 * no seat is stopped — and the completeness check moved to `tests/campaigns/machinery-is-one-seat.test.ts`,
 * which enumerates every fence path in the LIVE ledger and fails BY NAME on any that classifies as
 * neither. That is where §24 puts a denominator gate anyway: the list can still rot, and it rots loudly
 * at test time instead of blocking a builder mid-row.
 */
const MACHINERY_SEAT = "scout-campaign-mod";
const MACHINERY_ROOTS: readonly string[] = [
  ".claude/",
  ".dev/campaigns/",
  ".dev/earn-artifacts/",
  ".githooks/",
  ".github/",
  "tests/architecture/",
  "tests/campaigns/",
  "tests/hooks/",
];
const MACHINERY_EXACT: readonly string[] = ["LAWS.md", "biome.jsonc", "eslint.config.ts", "tsconfig.json"];
const PRODUCT_ROOTS: readonly string[] = [
  "packages/",
  "scripts/",
  "seed/",
  "src/",
  // `tests/` catches a suite that sits directly under it — `tests/inspect.test.ts` is the one the
  // completeness gate found on its first run, across all 148 rows. The named subtrees below are
  // redundant against it BY DESIGN: longest-prefix means the machinery subtrees still win, and if
  // anyone ever deletes this broad root as a catch-all hack, the specific ones still classify.
  "tests/",
  "tests/application/",
  "tests/composition/",
  "tests/daemon/",
  "tests/domain/",
  "tests/fixtures/",
  "tests/helpers/",
  "tests/infrastructure/",
  "tests/laws/",
  "tests/manual/",
  "tests/scripts/",
];
const PRODUCT_EXACT: readonly string[] = ["bun.lock", "bunfig.toml", "package.json"];

/**
 * A fence entry that names no single file: a glob, or a character-class region. Fences legitimately
 * carry these — `tests/campaigns/ledger-receipt.test.ts:124` pins that "a region, a glob, and a file
 * only READ are all exempt" — so they are EXEMPT, not unclassified. The distinction is the whole point
 * of the third value: `exempt` is a decision, `null` is an absence, and only the absence is a defect.
 */
const NON_CONCRETE_FENCE_RE = /[*?[\]]/;

/** `machinery` | `product` | `exempt` for one fence entry, or null when a CONCRETE path is unlisted. */
export function fenceKind(path: string): "machinery" | "product" | "exempt" | null {
  if (NON_CONCRETE_FENCE_RE.test(path)) return "exempt";
  const clean = path.replace(/^\.\//, "").replace(/^\/+/, "");
  if (MACHINERY_EXACT.includes(clean)) return "machinery";
  if (PRODUCT_EXACT.includes(clean)) return "product";
  // LONGEST PREFIX WINS: `tests/` is product (it holds the product suites) while `tests/hooks/` is not,
  // and a shortest-match scan would answer "product" for both.
  const machinery = MACHINERY_ROOTS.filter((root) => clean.startsWith(root));
  const product = PRODUCT_ROOTS.filter((root) => clean.startsWith(root));
  const longest = [...machinery, ...product].sort((a, b) => b.length - a.length)[0];
  if (longest === undefined) return clean === "tests/" ? "product" : null;
  return machinery.includes(longest) ? "machinery" : "product";
}

/**
 * Is this row MACHINERY? Every fence path must be apparatus and at least one must exist.
 *
 * A row that touches the product too is a product row that happens to reach apparatus, and it stays
 * dispatchable — the wall governs seats working the apparatus, not every file it can name. An EMPTY
 * fence is not machinery: it names nothing, so it claims nothing, and `assertFenceShape` is where an
 * empty fence is argued about.
 */
function isMachineryRow(item: { readonly files: readonly string[] }): boolean {
  // Exempt entries are evidence in NEITHER direction, so they are dropped rather than counted against
  // the row: a fence of `.claude/hooks/x.ts` plus `docs/*.md` is still apparatus. What remains must be
  // non-empty and entirely machinery — an unlisted concrete path leaves the row dispatchable, and
  // `tests/campaigns/machinery-is-one-seat.test.ts` is what keeps that from going unnoticed.
  const decided = item.files.map((f) => fenceKind(f)).filter((kind) => kind !== "exempt");
  return decided.length > 0 && decided.every((kind) => kind === "machinery");
}

/**
 * ONE SEAT WORKS THE APPARATUS — refused at the verbs a seat actually calls.
 *
 * NOT ONLY AT `claim`, AND THE REASON IS MEASURED. The row was specified as a wall on `claim`; then
 * `claimedBy` turned out to appear in ZERO committed versions of this board across 155 commits and zero
 * worktree bytes. The ownership verb has never been called once, so a wall there would have refused
 * nothing while its green tests read as protection — the campaign's own signature defect, built into the
 * fix for it. The traffic is `receipt`, `set-status` and `note`: every builder must call them, `done` is
 * refused without a receipt, and `receipt` already threads the seat. The `claim` arm is kept because it
 * costs nothing and goes live the day anyone uses the verb.
 *
 * AN ABSENT SEAT IS REFUSED, NOT WAVED THROUGH. A wall that a seat disarms by omitting one flag is
 * theatre of the same kind. The cost of naming the seat falls on the one seat that does this work.
 */
function assertMachinerySeat(id: string, item: { readonly files: readonly string[] }, rest: readonly string[], verb: string): void {
  if (!isMachineryRow(item)) return;
  const seat = flag(rest, "seat") ?? (process.env.LEDGER_SEAT || null);
  if (seat === MACHINERY_SEAT) return;
  if (seat === null) {
    throw new LedgerError(
      `${id} is a MACHINERY row, and \`${verb}\` on one must name its seat.\n` +
        `  Its fence is apparatus only (${item.files.join(", ")}), which belongs to ${MACHINERY_SEAT}.\n` +
        `  \`--seat <you>\`, or \`export LEDGER_SEAT=<you>\` once per session.`,
    );
  }
  throw new LedgerError(
    `${id} is a MACHINERY row and belongs to ${MACHINERY_SEAT}, not ${seat}.\n` +
      `  Fence: ${item.files.join(", ")} — apparatus only.\n` +
      `  Builders work the MAIN CAMPAIGN. When it has no open rows, builders are DONE; apparatus work is\n` +
      `  not what a seat is given to stay warm (operator ruling 2026-09-18, after eight of nine machinery\n` +
      `  rows were dispatched to builders in one hour while m1-m7 sat all-done).`,
  );
}

/** Is any row of the MAIN CAMPAIGN still open? A closed deliverable is the signal to stop. */
function mainCampaignOpen(blocks: readonly ItemBlock[]): ItemBlock | undefined {
  return blocks.find(
    (b) =>
      (b.item.status === "todo" || b.item.status === "in_flight" || b.item.status === "blocked") &&
      !isMachineryRow(b.item),
  );
}

// ── dispatch ──────────────────────────────────────────────────────────────────────────────────

// VENDORING DELTA 7 (splice V4-143): exported so splice's entry, .dev/campaigns/manifest.ts, runs
// this CLI in-process under the name every seat and law already uses, without renaming this file.
/**
 * VENDORING DELTA 15 (splice V4-143 D2, 2026-09-18): THIN EMIT HOOKS, the rest lives in fleet.ts.
 * manifest.py appends a fleet-journal line for note, set-status and claim (receipt and verify-phase
 * reach it through note and set-status), and torad's gym builds its trajectory corpus from those
 * arcs. This file only says WHAT happened, after the ledger write landed; splice's entry registers
 * the writer. Unregistered — the selftest, a re-vendor, any other entry — it is a no-op.
 */
export type LedgerEvent = {
  ledgerPath: string;
  verb: "note" | "set-status" | "claim";
  itemId: string;
  status?: ItemStatus;
  seat?: string;
  args: readonly string[];
};
export const ledgerEvents: { emit: (event: LedgerEvent) => Promise<void> } = { emit: async () => {} };
/** The row writers fleet.ts's ported verbs (verdict, handover, next --claim) share with this file's own. */
export { claimNote, fenceOverlap, lastClaimOwner, retirementMarker, withField, withNote, withStatus };

export async function main(argv: readonly string[] = Bun.argv.slice(2)): Promise<number> {
  const ledgerPath = argv[0];
  const command = argv[1];

  if (ledgerPath === undefined || command === undefined || command === "help") {
    console.log(USAGE);
    return ledgerPath === undefined ? 1 : 0;
  }

  if (command === "selftest") return await selftest();

  const rest = argv.slice(2);

  /**
   * `verified` IS THE ORCHESTRATOR'S WORD ON THIS PLANE TOO.
   *
   * The matrix got this control in round 3 and the ledger did not, which was backwards: "only the
   * orchestrator sets verified" is the CAMPAIGN law, so the ledger is the plane where it matters
   * most and it was the plane without the gate. Any seat could close its own item as verified.
   *
   *   done      a builder claims it landed (its own scoped tests + typecheck green)
   *   verified  the row's MILESTONE exit gate ran green and the milestone review passed;
   *             set in batch by `verify-phase`, never by re-running gates per row
   *             (operator ruling 2026-09-17: per-row verification ceremony is what made
   *             campaigns slow — builders verify rows with a receipt, the orchestrator commits
   *             locally per row and pushes once per milestone, CI runs the suite on that push,
   *             and the orchestrator verifies the milestone. The campaign never bleeds into CI
   *             row by row.)
   *
   * Same honest limit as everywhere else: on a NOPASSWD host a builder that wants to set this can.
   * What the check buys is that doing so becomes deliberate and self-incriminating rather than the
   * default path.
   */
  const ORCHESTRATOR_ENV = "LEDGER_ORCHESTRATOR";
  const claimsVerified =
    (command === "set-status" && rest[1] === "verified") ||
    (command === "add" && flag(rest, "status") === "verified") ||
    command === "verify-phase" ||
    command === "reattest" ||
    command === "init" ||
    command === "deliver" ||
    command === "plan" ||
    command === "stage";

  if (claimsVerified && (process.env[ORCHESTRATOR_ENV] ?? "") !== "1") {
    throw new LedgerError(
      `"verified" is the orchestrator's word, not a builder's.\n\n` +
        `  done      a builder claims it landed (own scoped tests + typecheck)\n` +
        `  verified  the MILESTONE exit gate ran green and the milestone review passed;\n` +
        `            the orchestrator sets it in batch with verify-phase <P>\n\n` +
        `Set it to "done" and report; the orchestrator verifies. If you ARE the orchestrator,\n` +
        `re-run with ${ORCHESTRATOR_ENV}=1 set inline.\n\n` +
        `Stated plainly: on a NOPASSWD host a builder that wants to set this can. What the check\n` +
        `buys is that doing so is deliberate rather than the default path.`,
    );
  }

  // VENDORING DELTA 5 (splice V4-143, orchestrator ruling 2026-09-18): ONE CLAIM MODEL WHILE TWO
  // CLIS EXIST. manifest.py records a claim as a dated CLAIM note and refuses one whose fence meets a
  // live row's; this CLI records claimed_by/claimed_at and has no such guard. Each is blind to the
  // other's claims — measured: a py claim landed on top of a claim made here. So on a ledger
  // manifest.py also writes, ownership stays with manifest.py; the refusal expires with the .py.
  // The cutover ports the disjointness guard here and migrates in-flight CLAIM notes to fields.
  const CLAIM_VERBS = new Set(["claim", "release", "release-stale"]);
  const claimsHere = CLAIM_VERBS.has(command) || (command === "next" && rest.includes("--claim"));
  if (claimsHere && coexistsWithPython(ledgerPath)) {
    throw new LedgerError(
      `${command} is refused on ${ledgerPath} while .dev/campaigns/manifest.py still writes it: ` +
        `the two CLIs record claims differently and cannot see each other's. Claim with ` +
        `\`python3 .dev/campaigns/manifest.py ${ledgerPath} claim <ID> --session <seat>\` until the cutover.`,
    );
  }
  const READ_ONLY = new Set(["list", "get", "next", "laws", "packet", "phase-status", "touched", "validate"]);
  const attested = rest.includes("--attested") || command === "snapshot";
  if (command === "init") {
    // The single channel has to be able to START a ledger: the Write/Bash guards block every raw
    // write to .dev/campaigns/*.toml, and before this verb a new ledger had no sanctioned birth.
    const title = oneLine("title", positional(rest, 0, "a title line"));
    // NEVER UNBOUNDED BY OMISSION, ENFORCED WHERE THE OMISSION HAPPENS. The ceiling used to be enforced
    // at `add`, which meant every ledger born before the feature lost `add` entirely. A campaign
    // declares its plan at BIRTH: new ledgers cannot be unbounded, and legacy ones grow with a warning
    // rather than a refusal (see the `add` branch). 15 rows became 148 for want of this number.
    const rows = Number.parseInt(flag(rest, "rows") ?? "", 10);
    if (!Number.isInteger(rows) || rows <= 0) {
      throw new LedgerError(
        `init needs the planned row count: \`init "<title>" --rows <N>\` — a campaign declares its plan at birth, never unbounded by omission`,
      );
    }
    if (existsSync(ledgerPath)) throw new LedgerError(`${ledgerPath} already exists; use add-law / add on it`);
    mkdirSync(dirname(ledgerPath), { recursive: true });
    await Bun.write(
      ledgerPath,
      `# ${title}\n# created ${new Date().toISOString().slice(0, 10)} by the ledger CLI; laws are the \`# LAW:\` lines, rows are [[items]]\n` +
        `# PLANNED ${rows} rows — ceiling ${rows * CEILING_FACTOR} milestone rows; \`add\` refuses past it (review-milestone rows excluded)\n\n`,
    );
    const hash = await reattestLedger(ledgerPath);
    console.log(`${ledgerPath}: created, proof ${hash.slice(0, 12)}…`);
    return 0;
  }
  if (command === "reattest") {
    const hash = await reattestLedger(ledgerPath);
    console.log(`${ledgerPath}: proof re-bound to ${hash.slice(0, 12)}… — review \`git diff -- ${ledgerPath}\` and note what was blessed`);
    return 0;
  }
  const lines = READ_ONLY.has(command) && !attested ? await readLinesLoose(ledgerPath) : await readLines(ledgerPath, attested);
  if (command === "snapshot") {
    process.stdout.write(lines.join("\n"));
    return 0;
  }
  const blocks = locateItems(lines);

  switch (command) {
    case "list":
      if (rest.includes("--plain")) {
        const plain = renderPlainList(lines, blocks, flag(rest, "status"), flag(rest, "phase"));
        if (plain !== "") console.log(plain);
        return 0;
      }
      console.log(renderList(blocks, flag(rest, "status"), flag(rest, "phase")));
      return 0;

    case "get": {
      const block = findBlock(blocks, positional(rest, 0, "an item id"));
      if (rest.includes("--raw")) {
        process.stdout.write(`${lines.slice(block.start, block.end).join("\n").trimEnd()}\n`);
        return 0;
      }
      console.log(renderItem(lines, block));
      return 0;
    }

    case "next": {
      const chosen = pickNext(blocks);
      console.log(chosen === null ? "queue empty" : renderItem(lines, chosen));
      return 0;
    }

    case "laws": {
      const laws = lawSheet(lines);
      if (laws.length > 0) console.log(laws.join("\n"));
      return 0;
    }

    case "packet":
      console.log(renderPacket(lines, findBlock(blocks, positional(rest, 0, "an item id"))));
      return 0;

    case "audit": {
      // THE RECORD AGAINST REALITY, AS A COMMAND RATHER THAN A HABIT. Everything checked here is a
      // defect this campaign actually produced on 2026-09-18 and caught by hand, late, through a
      // person: rows whose work was finished and unreceipted sat in `todo`; a row was claimed by the
      // literal string "--seat"; rows were dispatched into files another live row held; a whole-tree
      // row declared `files = ["."]`, which matches nothing. A check someone has to remember to run
      // is a check that runs once somebody is already suspicious.
      const deep = rest.includes("--deep");
      const root = repoRootOf(ledgerPath);
      const all = locateItems(lines);
      const findings: string[] = [];
      const remarks: string[] = [];
      const SEAT_RE = /^[A-Za-z][A-Za-z0-9._-]{2,63}$/;
      // THE INSTRUMENT MUST NOT DIE ON THE DEFECT IT EXISTS TO FIND. `latestReceipt` refuses a
      // receipt-shaped note that does not parse, which is correct for a GATE — `done` and `stage`
      // must fail closed — and wrong here: one malformed note would abort the whole audit and hide
      // every other finding in the ledger. The auditor reports what the gates refuse.
      const receiptOf = (candidate: ItemBlock): Receipt | undefined => {
        try {
          return latestReceipt(notesOf(lines, candidate));
        } catch (error) {
          findings.push(`${candidate.item.id} carries a receipt-shaped note that does not parse — every gate on this row refuses until it is re-filed: ${(error instanceof Error ? error.message : String(error)).split("\n")[1]?.trim().slice(0, 120) ?? ""}`);
          return undefined;
        }
      };
      for (const candidate of all) {
        const item = candidate.item;
        const receipt = receiptOf(candidate);
        const open = item.status === "todo" || item.status === "in_flight";
        if (open && receipt?.exit === 0) {
          findings.push(`${item.id} [${item.status}] carries a GREEN receipt (${receipt.touched.length} file(s)) but is not reported done — the work is finished and the record is not`);
        }
        if (open && receipt !== undefined && receipt.exit === null) {
          remarks.push(`${item.id} [${item.status}] latest receipt was filed by manifest.py (files, no run) — done and stage need one re-filed with --exit`);
        }
        if (open && receipt !== undefined && receipt.exit !== null && receipt.exit !== 0) {
          remarks.push(`${item.id} [${item.status}] latest receipt is RED (exit ${receipt.exit}) — in progress, correctly not done`);
        }
        if ((item.status === "done" || item.status === "verified") && receipt === undefined) {
          findings.push(`${item.id} [${item.status}] closed with NO receipt — nothing records what ran`);
        }
        if (item.claimedBy !== undefined && !SEAT_RE.test(item.claimedBy)) {
          findings.push(`${item.id} is claimed by "${item.claimedBy}", which is not a seat name — \`release ${item.id}\`, then the seat that holds it claims it`);
        }
        if (item.files.length === 0) findings.push(`${item.id} declares no fence — \`stage\` refuses a row without one`);
        const bare = item.files.filter((file) => !file.endsWith("/") && statSync(join(root, file), { throwIfNoEntry: false })?.isDirectory() === true);
        if (bare.length > 0) findings.push(`${item.id} fences ${bare.join(", ")} without a trailing slash — as written it matches nothing; \`amend ${item.id} --files …\``);
        const scratch = receipt === undefined ? [] : [...receipt.touched, ...receipt.deleted].filter(isScratchPath);
        if (scratch.length > 0) findings.push(`${item.id}'s latest receipt names scratch paths: ${scratch.join(", ")} — they would be committed`);
        // THE FENCE CAN MOVE AFTER THE RECEIPT IS FILED. `receipt` checks the fence as it stood at
        // write time, and this campaign amends fences several times a day — so a row can pass its
        // own check and then be made unstageable by an amend it never saw. Measured 2026-09-18:
        // M8.4's fence was narrowed to what its seat was really in, which left its receipt naming
        // a file the new fence does not cover; `stage` would have refused it after the work was
        // finished, which is the exact timing this whole check set exists to move earlier.
        if (receipt !== undefined && item.files.length > 0) {
          const beyondNow = [...receipt.touched, ...receipt.deleted].filter((p) => !inFence(p, item.files));
          if (beyondNow.length > 0) {
            findings.push(`${item.id}'s latest receipt names ${beyondNow.length} path(s) outside its CURRENT fence: ${beyondNow.slice(0, 4).join(", ")}${beyondNow.length > 4 ? ` … (+${beyondNow.length - 4})` : ""} — \`stage\` will refuse; either the fence was amended after the receipt or the receipt reached past it`);
          }
        }
        if (open && item.verify === "") remarks.push(`${item.id} declares no verify command`);
      }
      // fences-are-disjoint, checked over every live PAIR rather than only at the moment of a claim:
      // `claim` warns the seat taking the row, which is the one seat that cannot act on the overlap.
      const live = all.filter((b) => b.item.claimedBy !== undefined && b.item.status !== "done" && b.item.status !== "verified");
      for (let i = 0; i < live.length; i += 1) {
        for (let j = i + 1; j < live.length; j += 1) {
          const a = live[i]!.item;
          const b = live[j]!.item;
          if (a.claimedBy === b.claimedBy) continue;
          const shared = a.files.filter((f) => b.files.some((g) => f === g || (g.endsWith("/") && f.startsWith(g)) || (f.endsWith("/") && g.startsWith(f))));
          if (shared.length > 0) findings.push(`${a.id} (@${a.claimedBy}) and ${b.id} (@${b.claimedBy}) are both live and share ${[...new Set(shared)].join(", ")} — two seats, one file`);
        }
      }
      // The same question across EVERY row, not only the one someone is about to stage: a row that
      // already carries this defect is visible now rather than when it reaches the orchestrator.
      {
        const tracked = new Set(git(root, "ls-files", "-z").split("\0").filter(Boolean));
        for (const candidate of all) {
          if (candidate.item.status === "verified") continue; // already through a milestone gate and CI
          const receipt = receiptOf(candidate);
          if (receipt === undefined || receipt.touched.length === 0) continue;
          const present = new Set([...tracked, ...receipt.touched]);
          for (const p of receipt.deleted) present.delete(p);
          const dangling = receipt.touched.flatMap((file) => unresolvableImports(root, file, present));
          if (dangling.length > 0) {
            findings.push(`${candidate.item.id} would commit a file importing a path the commit will not contain — ${dangling.slice(0, 2).join("; ")}${dangling.length > 2 ? ` … (+${dangling.length - 2})` : ""}`);
          }
        }
      }
      const undispatched = all.filter((b) => b.item.status === "todo" && b.item.claimedBy === undefined).map((b) => b.item.id);
      if (undispatched.length > 0) remarks.push(`undispatched (todo, unclaimed): ${undispatched.join(", ")}`);
      const phases = new Map<string, { open: number; closed: number }>();
      for (const b of all) {
        const row = phases.get(b.item.phase) ?? { open: 0, closed: 0 };
        if (b.item.status === "done" || b.item.status === "verified") row.closed += 1; else row.open += 1;
        phases.set(b.item.phase, row);
      }
      for (const [phase, row] of phases) {
        if (row.open === 0 && all.some((b) => b.item.phase === phase && b.item.status === "done")) {
          remarks.push(`phase ${phase}: all ${row.closed} rows closed and some are still \`done\` — ready for \`verify-phase ${phase}\` once its gate has run`);
        }
      }
      if (deep) {
        // WHICH DONE ROWS HAVE NOT REACHED A COMMIT — asked the only way that is not a false alarm.
        //
        // The first spelling of this compared the receipt's blob against the file's blob AT HEAD and
        // called every difference "awaiting commit". It reported thirty rows, nearly all of them
        // committed hours earlier: a receipt records the bytes the verify RAN against, and any file a
        // later row also touched has legitimately moved on since. A check that cries wolf on thirty
        // rows is a check the orchestrator stops reading, which is the defect this verb exists to
        // remove, so it was not worth shipping to save the git calls.
        //
        // The exact question is whether the receipt's bytes exist in HISTORY, not whether they are
        // the current bytes. `git cat-file -e` is the fast negative — `hash-object` never wrote the
        // object, so it exists only if some `git add` did — and `--find-object` confirms a commit
        // actually carries it (~40ms per blob, which is why this is behind --deep).
        for (const candidate of all) {
          if (candidate.item.status !== "done") continue;
          // Already reported by the pass above; here it is simply a row that cannot be measured.
          let receipt: Receipt | undefined;
          try { receipt = latestReceipt(notesOf(lines, candidate)); } catch { continue; }
          if (receipt === undefined || receipt.blobs.length !== receipt.touched.length) continue;
          const unlanded = receipt.touched.filter((file, index) => {
            const blob = receipt.blobs[index] ?? "";
            if (Bun.spawnSync(["git", "-C", root, "cat-file", "-e", blob], { stdout: "pipe", stderr: "pipe" }).exitCode !== 0) return true;
            const carried = Bun.spawnSync(["git", "-C", root, "log", "--all", "--find-object", blob, "--format=%h", "--", file], { stdout: "pipe", stderr: "pipe" });
            return carried.exitCode !== 0 || carried.stdout.toString().trim() === "";
          });
          const survived = receipt.deleted.filter((file) => Bun.spawnSync(["git", "-C", root, "cat-file", "-e", `HEAD:${file}`], { stdout: "pipe", stderr: "pipe" }).exitCode === 0);
          if (unlanded.length > 0 || survived.length > 0) {
            remarks.push(`${candidate.item.id} is done and these receipt bytes are in no commit: ${[...unlanded, ...survived.map((f) => `${f} (deletion)`)].slice(0, 6).join(", ")}${unlanded.length + survived.length > 6 ? ` … (+${unlanded.length + survived.length - 6})` : ""} — awaiting the commit, OR superseded by a later row that edited the same file before this one landed; \`landed ${candidate.item.id} <sha>\` decides which`);
          }
        }
      }
      for (const line of findings) console.log(`FINDING  ${line}`);
      for (const line of remarks) console.log(`note     ${line}`);
      console.log(`${ledgerPath}: ${all.length} rows · ${findings.length} finding(s) · ${remarks.length} note(s)${deep ? "" : " · --deep also reports which done rows are still uncommitted"}`);
      return findings.length > 0 ? 1 : 0;
    }

    case "validate": {
      const parsed = parseOrThrow(lines.join("\n"), ledgerPath) as { items?: unknown[] };
      const parsedCount = Array.isArray(parsed.items) ? parsed.items.length : 0;
      if (parsedCount !== blocks.length) {
        throw new LedgerError(
          `line scan found ${blocks.length} items but the parser found ${parsedCount} — ` +
            `the scanner and the parser disagree, which means one of them is wrong about this file`,
        );
      }
      const seen = new Map<string, number>();
      for (const block of blocks) seen.set(block.item.id, (seen.get(block.item.id) ?? 0) + 1);
      const dupes = [...seen].filter(([, n]) => n > 1).map(([id, n]) => `${id} ×${n}`);
      if (dupes.length > 0) throw new LedgerError(`duplicate item ids: ${dupes.join(", ")} — only the first of each is addressable`);
      console.log(`${ledgerPath}: valid · ${blocks.length} items`);
      return 0;
    }

    case "set-status": {
      const id = positional(rest, 0, "an item id");
      const status = positional(rest, 1, `a status (${ITEM_STATUSES.join("|")})`);
      if (!isItemStatus(status)) throw new LedgerError(`"${status}" is not a status`);
      await mutate(ledgerPath, (current) => {
        const block = findBlock(locateItems(current), id);
        assertMachinerySeat(id, block.item, rest, "set-status");
        assertItemMandates(id, status, notesOf(current, block));
        if (status === "done") assertReceipt(id, notesOf(current, block));
        return withStatus(current, block, status);
      });
      console.log(`${id} → ${status}`);
      await ledgerEvents.emit({ ledgerPath, verb: "set-status", itemId: id, status, args: rest });
      return 0;
    }

    case "receipt": {
      // THE BUILDER'S PROOF. Structured so the orchestrator never re-runs it: the exact command,
      // its exit code, the test count, the plain-path files touched (which is the staging list),
      // and the output tail. One dated note; `done` is refused until one exists at exit 0.
      const id = positional(rest, 0, "an item id");
      noExtraPositionals(rest, 1);
      const cmd = flag(rest, "cmd"); const exit = flag(rest, "exit"); const tests = flag(rest, "tests");
      const touched = flag(rest, "touched"); const tail = flag(rest, "tail") ?? "";
      const deletedCsv = flag(rest, "deleted") ?? "";
      if (cmd === null || exit === null || tests === null || touched === null) throw new LedgerError("receipt: --cmd, --exit, --tests and --touched are all required (--touched - with --deleted for a pure deletion)");
      if (!/^\d+$/.test(exit) || !/^\d+$/.test(tests)) throw new LedgerError("receipt: --exit and --tests must be integers");
      const files = touched.split(",").map((p) => p.trim()).filter((p) => p !== "" && p !== "-");
      const deleted = deletedCsv.split(",").map((p) => p.trim()).filter(Boolean);
      const badPath = (p: string): boolean => p.startsWith("/") || p.includes("..") || /\s/.test(p);
      if ((files.length === 0 && deleted.length === 0) || files.some(badPath) || deleted.some(badPath)) throw new LedgerError("receipt: --touched (and --deleted) must be repo-relative plain paths without whitespace, comma-separated");
      const scratch = [...files, ...deleted].filter(isScratchPath);
      if (scratch.length > 0) {
        throw new LedgerError(
          `receipt: the touched list is the STAGING list, and these are scratch files by the repo's own convention: ${scratch.join(", ")}\n` +
            `  tsconfig.json, biome.jsonc and eslint.config.ts all spell it "**/*.tmp.ts" and "**/.tmp-*" — a scratch file is excluded from every gate,\n` +
            `  so putting one here asks the orchestrator to commit a probe that nothing checks. Delete it and re-file the receipt without it.`,
        );
      }
      // A path outside the fence is worth a line, not a refusal: `stage` stages what the receipt says.
      const receiptBlock = findBlock(blocks, id);
      assertMachinerySeat(id, receiptBlock.item, rest, "receipt");
      const receiptFence = receiptBlock.item.files;
      const beyond = [...files, ...deleted].filter((p) => !inFence(p, receiptFence));
      if (receiptFence.length > 0 && beyond.length > 0) {
        console.error(`NOTE ${id}: ${beyond.length} path(s) outside this row's fence: ${beyond.slice(0, 8).join(", ")}${beyond.length > 8 ? ", …" : ""} — staged as receipted`);
      }
      // A row claimed by one seat and delivered by another is the collision fences-are-disjoint
      // exists to prevent, and nothing looked: M8.9 was claimed by scout-builder and filed by
      // scout-builder4 in silence (2026-09-18). Identity has to be supplied — the CLI cannot see
      // which session invoked it — so the packet teaches --seat and $LEDGER_SEAT works too.
      const filer = flag(rest, "seat") ?? (process.env.LEDGER_SEAT || null);
      const owner = receiptBlock.item.claimedBy;
      if (owner !== undefined && filer !== null && filer !== owner) {
        console.error(`WARNING ${id}: filed by ${filer}, claimed by ${owner} since ${receiptBlock.item.claimedAt ?? "unknown"} — two seats on one row. Report it before the orchestrator stages; a re-claim or a split is the fix, not a quieter receipt.`);
      }
      if (owner !== undefined && filer === null) {
        console.error(`NOTE ${id}: claimed by ${owner}; pass --seat <your seat> (or export LEDGER_SEAT) so a receipt filed by a different seat is caught here rather than at stage time.`);
      }
      // Paths, not hashes (operator ruling 2026-09-18, see the note above `headBlob`).
      const root = repoRootOf(ledgerPath);
      const missing = files.filter((p) => !existsSync(join(root, p)));
      if (missing.length > 0) throw new LedgerError(`receipt: touched files do not exist: ${missing.join(", ")}`);
      // A deletion must be gone from disk and tracked at HEAD, so `stage` can stage the removal.
      const stillThere = deleted.filter((p) => existsSync(join(root, p)));
      if (stillThere.length > 0) throw new LedgerError(`receipt: --deleted files still exist on disk: ${stillThere.join(", ")}`);
      const untracked = deleted.filter((p) => headBlob(root, p) === null);
      if (untracked.length > 0) throw new LedgerError(`receipt: --deleted files are not tracked at HEAD: ${untracked.join(", ")}`);
      const line =`RECEIPT cmd=${JSON.stringify(oneLine("cmd", cmd))} exit=${exit} tests=${tests} touched=${files.length > 0 ? files.join(",") : "-"}${deleted.length > 0 ? ` deleted=${deleted.join(",")}` : ""}${tail ? ` tail=${JSON.stringify(tail.slice(-400))}` : ""}`;
      await mutate(ledgerPath, (current) => withNote(current, findBlock(locateItems(current), id), line));
      console.log(`${id}: receipt recorded (exit ${exit}, ${tests} tests, ${files.length} files${deleted.length > 0 ? `, ${deleted.length} deleted` : ""})`);
      await ledgerEvents.emit({ ledgerPath, verb: "note", itemId: id, args: rest }); // manifest.py's receipt is a note
      return 0;
    }

    case "touched": {
      const id = positional(rest, 0, "an item id");
      const r = latestReceipt(notesOf(lines, findBlock(locateItems(lines), id)));
      if (r === undefined) throw new LedgerError(`${id} has no receipt`);
      for (const p of r.touched) console.log(p);
      return 0;
    }

    case "stage": {
      // STAGES EXACTLY THE RECEIPT'S FILES, AS THEY ARE ON DISK NOW. It refuses only a dirty index
      // (another row's staged bytes would ride this commit) and a commit that would not load (a
      // staged file importing a path the commit will not contain). No fence refusal, no byte
      // comparison, no moved-bytes refusal — operator ruling 2026-09-18, see the note above
      // `headBlob`. Then the ledger+proof pair, re-added until the two agree, so a concurrent ledger
      // write cannot abort the commit (Eli F3/F4).
      const id = positional(rest, 0, "an item id");
      const block = findBlock(locateItems(lines), id);
      const r = latestReceipt(notesOf(lines, block));
      if (r === undefined) throw new LedgerError(`${id} has no receipt to stage from`);
      if (r.exit === null) throw new LedgerError(pyReceiptRefusal(id, "stage"));
      if (r.exit !== 0) throw new LedgerError(`${id}: latest receipt exited ${r.exit}; nothing is staged from a red run`);
      const fence = block.item.files;
      if (fence.length === 0) throw new LedgerError(`${id} declares no files; a row without a fence cannot be staged`);
      const root = repoRootOf(ledgerPath);
      // A dirty index means someone else's staged bytes would ride this commit: `git mv` stages
      // renames on its own, and one orchestrator commit swept 81 of a peer's into the wrong
      // commit (2026-09-18). The ledger pair is the only staged content `stage` tolerates.
      const ledgerRelEarly = relative(root, ledgerPath);
      const preStaged = git(root, "diff", "--cached", "--name-only", "-z").split("\0").filter((p) => p !== "" && p !== ledgerRelEarly && p !== `${ledgerRelEarly}.cli-sha256`);
      if (preStaged.length > 0) {
        throw new LedgerError(
          `${id}: the index already holds ${preStaged.length} staged path(s) (${preStaged.slice(0, 5).join(", ")}${preStaged.length > 5 ? ", …" : ""}) — stage never rides another row's bytes.\n` +
            `  A refusal without the remedy is a refusal the next seat reads twice, so: if those paths belong to the row you just staged,\n` +
            `    LEDGER_ORCHESTRATOR=1 git commit -m "…"           finish that commit first, then re-run this stage\n` +
            `  if they are someone else's in-flight work that reached the index by accident (\`git mv\` and \`git rm\` stage themselves),\n` +
            `    LEDGER_ORCHESTRATOR=1 git restore --staged -- ${preStaged.slice(0, 3).join(" ")}${preStaged.length > 3 ? " …" : ""}   unstage them; the files on disk are untouched.`,
        );
      }
      const outside = [...r.touched, ...r.deleted].filter((p) => !inFence(p, fence));
      if (outside.length > 0) console.error(`NOTE ${id}: receipt names files outside the fence, staged anyway: ${outside.join(", ")}`);
      const modified = git(root, "diff", "--name-only", "-z", "--", ...fence).split("\0").filter(Boolean);
      const untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z", "--", ...fence).split("\0").filter(Boolean);
      // windows-block-edits-not-creation: a NEW file inside another open row's fence is that row's
      // work in progress beside this one, not an omission. A modified tracked file always is.
      const otherFences = locateItems(lines).filter((b) => b.item.id !== id && b.item.status !== "done" && b.item.status !== "verified").flatMap((b) => [...b.item.files]);
      const covered = (p: string): boolean => r.touched.includes(p) || r.deleted.includes(p);
      const omitted = [...modified.filter((p) => !covered(p)), ...untracked.filter((p) => !covered(p) && !inFence(p, otherFences))];
      // A warning, not a refusal (operator ruling 2026-09-18): the unlisted file stays UNSTAGED,
      // which is the safe outcome; refusing only stalled the row behind a peer's in-flight edit.
      if (omitted.length > 0) console.error(`WARNING ${id}: fenced files changed but not on the receipt, left unstaged: ${omitted.join(", ")}`);
      // A deletion that reappeared on disk is left tracked; the rest are removed from the index.
      const gone = r.deleted.filter((p) => existsSync(join(root, p)));
      if (gone.length > 0) console.error(`NOTE ${id}: deleted files reappeared on disk and stay tracked: ${gone.join(", ")}`);
      const removals = r.deleted.filter((p) => !existsSync(join(root, p)));
      // A REFUSAL RATHER THAN A WARNING, and the operator's own criterion is why: a warning is right
      // when the safe outcome happens anyway (an unlisted file simply stays unstaged), and a refusal
      // is right when the unsafe thing WOULD otherwise happen. Here it does — the commit lands and
      // HEAD cannot load, which is what it did for four commits at 7cb7c5a.
      const tracked = new Set(git(root, "ls-files", "-z").split("\0").filter(Boolean));
      for (const p of removals) tracked.delete(p);
      const present = new Set([...tracked, ...r.touched]);
      const dangling = r.touched.flatMap((file) => unresolvableImports(root, file, present));
      if (dangling.length > 0) {
        throw new LedgerError(
          `${id}: this commit would not load — a staged file imports a path the commit will not contain:\n` +
            dangling.map((line) => `  ${line}`).join("\n") +
            `\n  A fence lists what to INCLUDE and cannot notice what is MISSING, so the file was invisible rather than out-of-fence.\n` +
            `  Add the missing path to this row (re-file the receipt with it on --touched, amending the fence if it is outside), or commit it with the row that owns it first.`,
        );
      }
      if (r.touched.length > 0) git(root, "add", "--", ...r.touched);
      if (removals.length > 0) git(root, "rm", "--cached", "--quiet", "--", ...removals);
      const ledgerRel = relative(root, ledgerPath);
      let consistent = false;
      for (let attempt = 0; attempt < 3 && !consistent; attempt += 1) {
        git(root, "add", "--", ledgerRel, `${ledgerRel}.cli-sha256`);
        const stagedToml = new Bun.CryptoHasher("sha256").update(git(root, "show", `:${ledgerRel}`)).digest("hex");
        consistent = git(root, "show", `:${ledgerRel}.cli-sha256`).trim() === stagedToml;
      }
      if (!consistent) throw new LedgerError("ledger and proof would not settle into a consistent staged pair after 3 tries");
      console.log(`${id}: staged ${r.touched.length} files${removals.length > 0 ? ` + ${removals.length} deletions` : ""} + ledger pair — commit now`);
      return 0;
    }

    case "landed": {
      // LANDED MEASURES THE STATE AT A COMMIT, NOT THAT COMMIT'S DELTA.
      //
      // The first version set-compared the receipt against `git show --name-only`, which answers
      // "did THIS commit change the file" — the wrong question in both directions. It reported a
      // failure that was not one: M8.1 created 12_architecture_rules.ts and M8.7 added its GOVERNS
      // block, `stage` correctly refused to ride another row, the bytes went in with M8.1, and
      // `landed M8.7` then said "the proof did not land" about a file that had landed one commit
      // earlier (2026-09-18). An unexplained failed check that needs prose every time is a check
      // people learn to ignore. And it MISSED a failure that is one: a file present in the commit
      // with bytes that are not the ones the verify ran against passed, because only names were
      // compared.
      //
      // PATHS, NOT BYTES (operator ruling 2026-09-18: receipts carry no hashes). The question is: at
      // this commit, is every receipt file present and every recorded deletion gone? Which commit
      // carried it there is reported, never judged. A diagnostic verb; nothing gates on it.
      const id = positional(rest, 0, "an item id");
      const sha = rest[1] ?? "HEAD";
      noExtraPositionals(rest, 2);
      const block = findBlock(locateItems(lines), id);
      const r = latestReceipt(notesOf(lines, block));
      if (r === undefined) throw new LedgerError(`${id} has no receipt to compare`);
      const root = repoRootOf(ledgerPath);
      const ledgerRel = relative(root, ledgerPath);
      const inCommit = new Set(git(root, "show", "--name-only", "--format=", sha).split("\n").map((l) => l.trim()).filter((l) => l !== "" && l !== ledgerRel && l !== `${ledgerRel}.cli-sha256`));
      const onReceipt = new Set([...r.touched, ...r.deleted]);
      const blobAt = (rev: string, rel: string): string | null => {
        const p = Bun.spawnSync(["git", "-C", root, "rev-parse", "--verify", "--quiet", `${rev}:${rel}`], { stdout: "pipe", stderr: "pipe" });
        return p.exitCode === 0 ? p.stdout.toString().trim() : null;
      };
      const landedIn = (rel: string): string => {
        const p = Bun.spawnSync(["git", "-C", root, "log", "-1", "--format=%h", sha, "--", rel], { stdout: "pipe", stderr: "pipe" });
        return p.exitCode === 0 ? (p.stdout.toString().trim() || "?") : "?";
      };
      const absent: string[] = [];
      const earlier: string[] = [];
      for (const file of r.touched) {
        if (blobAt(sha, file) === null) { absent.push(file); continue; }
        if (!inCommit.has(file)) earlier.push(`${file} in ${landedIn(file)}`);
      }
      const survived = r.deleted.filter((file) => blobAt(sha, file) !== null);
      for (const file of r.deleted) if (!survived.includes(file) && !inCommit.has(file)) earlier.push(`${file} removed in ${landedIn(file)}`);
      const extra = [...inCommit].filter((f) => !onReceipt.has(f));
      if (extra.length > 0) console.error(`WARNING ${id}: in ${sha.slice(0, 12)} but not on the receipt: ${extra.join(", ")}`);
      if (absent.length > 0) { console.error(`${id}: on the receipt but NOT PRESENT at ${sha.slice(0, 12)}: ${absent.join(", ")} — the proof did not land`); return 1; }
      if (survived.length > 0) { console.error(`${id}: recorded as deleted but still present at ${sha.slice(0, 12)}: ${survived.join(", ")} — the removal did not land`); return 1; }
      if (earlier.length > 0) console.log(`${id}: ${earlier.length} receipt file(s) already at ${sha.slice(0, 12)} from an earlier commit — ${earlier.join(", ")} (two rows on one file; the proof landed, one commit up)`);
      // The M3.1 case was neither direction: the row's VERIFY named a test the receipt omitted and
      // the fence did not cover, so nothing listed it and it stayed untracked. Every file the verify
      // command names must be tracked at the commit.
      const named = (block.item.verify.match(/[\w./@-]+\.(?:ts|tsx|js|txt|toml|json|log)\b/g) ?? []).filter((f) => existsSync(join(root, f)));
      const untracked = named.filter((f) => Bun.spawnSync(["git", "-C", root, "cat-file", "-e", `${sha}:${f}`], { stdout: "pipe", stderr: "pipe" }).exitCode !== 0);
      if (untracked.length > 0) { console.error(`${id}: the verify command names files that are NOT in ${sha.slice(0, 12)}: ${untracked.join(", ")} — the proof did not land`); return 1; }
      console.log(`${id}: landed in ${sha.slice(0, 12)} — ${onReceipt.size} receipt file(s) present (paths, not bytes: receipts carry no hashes)${extra.length > 0 ? `, ${extra.length} unlisted` : ""}${named.length > 0 ? `; ${named.length} verify file(s) tracked` : ""}`);
      return 0;
    }

    case "focus": {
      // The active pointer the SessionStart hook reads to re-anchor a compacted seat. Nothing wrote
      // it in production before this verb existed (Eli F2) — and once it did, it wrote it under a
      // key the hook never reads.
      //
      // MEASURED 2026-09-18. The hook resolves the pointer as `ledger-active-<data.session_id>.json`
      // first and `ledger-active-<TMUX_PANE>.json` second, falling back to the literal "default" only
      // when it has neither. `focus` wrote `--seat` names or "default". So every
      // `ledger-active-scout-builder*.json` on disk is a file nothing can read, and every seat
      // without TMUX_PANE re-anchors onto ONE SHARED `ledger-active-default.json` — whichever row
      // the last seat to run `focus` happened to be on. This session is the proof: its SessionStart
      // injected M8.14 as its in-flight re-anchor, a row it had never claimed and another seat held,
      // and the same file now reads M8.4. A compaction is exactly when a seat cannot tell.
      //
      // `CLAUDE_CODE_SESSION_ID` is present in every tool call's environment and is the same value
      // the hook keys by — the `ledger-cursor.<uuid>.json` files beside these are written by the
      // hook from it. So the session-keyed pointer is written whenever it exists, the named one is
      // written as well when a seat is given (it is what a human reads, and the TMUX_PANE path), and
      // "default" survives only for the case that has neither.
      const id = positional(rest, 0, "an item id");
      findBlock(locateItems(lines), id);
      const root = repoRootOf(ledgerPath);
      const dir = join(root, ".claude", "state");
      mkdirSync(dir, { recursive: true });
      const body = JSON.stringify({ ledger_path: resolve(ledgerPath), item_id: id }) + "\n";
      const token = (raw: string): string => raw.replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 128) || "default";
      // `|| null`, not `?? null`: an env var that is SET AND EMPTY is the common shape outside tmux,
      // and `??` let "" through to become the shared "default" pointer with no warning at all —
      // which is the defect this branch exists to make impossible.
      const session = process.env.CLAUDE_CODE_SESSION_ID || "";
      const named = flag(rest, "seat") ?? (process.env.TMUX_PANE || null);
      const written: string[] = [];
      if (session !== "") { await Bun.write(join(dir, `ledger-active-${token(session)}.json`), body); written.push(`session ${token(session).slice(0, 8)}`); }
      if (named !== null) { await Bun.write(join(dir, `ledger-active-${token(named)}.json`), body); written.push(`seat ${token(named)}`); }
      if (written.length === 0) { await Bun.write(join(dir, "ledger-active-default.json"), body); written.push("default (no session id, no seat: this pointer is SHARED with every other such seat)"); }
      console.log(`${id}: active pointer written for ${written.join(" + ")}`);
      return 0;
    }

    case "phase-status": {
      const phase = positional(rest, 0, "a phase");
      const inPhase = locateItems(lines).filter((b) => b.item.phase === phase);
      if (inPhase.length === 0) throw new LedgerError(`no items in phase "${phase}"`);
      for (const b of inPhase) console.log(`${b.item.id}  [${b.item.status}]  ${b.item.title.split("\n")[0]}`);
      const open = inPhase.filter((b) => b.item.status !== "done" && b.item.status !== "verified").length;
      console.log(open === 0 ? `phase ${phase}: all ${inPhase.length} rows done — ready for the milestone gate` : `phase ${phase}: ${open} of ${inPhase.length} rows still open`);
      return 0;
    }

    case "verify-phase": {
      // THE MILESTONE GATE. One command flips every done row in a phase to verified under one
      // flock, with the same dated evidence note on each. It refuses while any row in the phase
      // is still open, so a milestone cannot be half-verified, and it is the ONLY intended path
      // to "verified" in a campaign: rows are never re-gated one by one.
      const phase = positional(rest, 0, "a phase");
      const evidence = oneLine("evidence", positional(rest, 1, "the gate evidence (what ran, where, and its result)"));
      const flipped: string[] = [];
      await mutate(ledgerPath, (current) => {
        const inPhase = locateItems(current).filter((b) => b.item.phase === phase);
        if (inPhase.length === 0) throw new LedgerError(`no items in phase "${phase}"`);
        const open = inPhase.filter((b) => b.item.status !== "done" && b.item.status !== "verified");
        if (open.length > 0) throw new LedgerError(`phase "${phase}" is not ready: ${open.map((b) => `${b.item.id} [${b.item.status}]`).join(", ")} still open`);
        // No receipt-versus-commit check here (operator ruling 2026-09-18, see the note above
        // `headBlob`): the evidence argument IS the milestone's CI run, and that is the whole gate.
        let next = [...current];
        flipped.length = 0;
        for (const id of inPhase.filter((b) => b.item.status === "done").map((b) => b.item.id)) {
          // Re-locate after every write: each note splice moves the lines below it.
          const b = findBlock(locateItems(next), id);
          assertItemMandates(id, "verified", notesOf(next, b));
          next = withStatus(next, b, "verified");
          next = withNote(next, findBlock(locateItems(next), id), `VERIFY-PHASE ${phase} ${today()}: ${evidence}`); // manifest.py's wording, delta 13
          flipped.push(id);
        }
        return next;
      });
      console.log(flipped.length === 0 ? `phase ${phase}: nothing to flip (all already verified)` : `phase ${phase}: ${flipped.join(", ")} → verified`);
      for (const id of flipped) { // manifest.py's order per row: its note, then its status
        await ledgerEvents.emit({ ledgerPath, verb: "note", itemId: id, args: rest });
        await ledgerEvents.emit({ ledgerPath, verb: "set-status", itemId: id, status: "verified", args: rest });
      }
      return 0;
    }

    case "note": {
      const id = positional(rest, 0, "an item id");
      // THE VERB A SEAT REACHES FOR FIRST IS THE ONE THAT HAS TO TEACH. scout-builder4 had a
      // campaign-wide lesson to record, found that `note` wants an item id, and read `amend-header`
      // as the only general surface — then correctly refused to use it, because it replaces a line
      // wholesale and a short replacement eats the law sheet. `add-law` was in the usage text the
      // whole time and a careful seat still did not land on it (2026-09-18). That is a
      // discoverability defect in the tool, so the dead end now names both safe exits — and they
      // are two, not one: `add-law` writes a BINDING rule that is injected into every seat's
      // SessionStart, so filing a lesson there would cost every seat context on every start.
      if (rest.length === 1) {
        throw new LedgerError(
          `note needs an item id: \`note <ID> "text"\`. For something that belongs to the CAMPAIGN rather than a row:\n` +
            `  add-note "text"   a dated line in the header — a lesson, a measurement, a decision with no row\n` +
            `  add-law  "text"   a BINDING rule; it is injected into every seat's SessionStart, so it costs every seat context\n` +
            `Never amend-header for this: it replaces a line in place and a short replacement destroys the one it lands on.`,
        );
      }
      const text = oneLine("note text", positional(rest, 1, "note text"));
      await mutate(ledgerPath, (current) => {
        const block = findBlock(locateItems(current), id);
        /*
         * `note` IS DELIBERATELY NOT WALLED BY THE MACHINERY SEAT, and it was, for one hour, wrongly.
         *
         * MOD.45's wall went onto `receipt`, `set-status` and `note` together. It then refused
         * scout-reviewer filing a finding on a machinery row, telling a REVIEWER that "apparatus work is
         * not what a seat is given to stay warm" — and the findings reached the orchestrator as a chat
         * message instead of the record, which is the one place the campaign requires them to be.
         *
         * `note` AND `receipt` ARE NOT THE SAME ACT. A receipt asserts "I did this work and here is my
         * green run"; `set-status` moves the row; `claim` takes it. Those are claims on the apparatus and
         * they are walled. A note APPENDS AN OBSERVATION and claims nothing — read-only reporting, which
         * every seat owes on anything it sees. Walling it bought nothing either: a seat that cannot
         * `receipt` or `set-status` cannot land the row whatever it writes in the margin.
         *
         * A WALL THAT REFUSES CORRECT WORK IS ANNOUNCING THAT ITS MODEL IS WRONG. This one's model said
         * "touching a machinery row means claiming it", and the same wrong model was letting the real
         * thing through while stopping the report of it.
         */
        // NO REVIEW PER ROW. A review note on a row of a milestone that is not delivered is the
        // first link of the loop the ontology above refuses: the note becomes a fix row, the fix
        // row gets reviewed, and the milestone never delivers. Reviews happen at `deliver`.
        if (REVIEW_NOTE_RE.test(text) && deliveredAt(current, block.item.phase) === null) {
          throw new LedgerError(
            `no review per row: ${id} is in ${block.item.phase}, which is not delivered, so nothing reviews it yet.\n` +
              `  A review happens ONCE, at \`deliver ${block.item.phase} --sha <sha>\`, on the delivered tree; its fixes\n` +
              `  become rows of ${block.item.phase}${REVIEW_SUFFIX}, worked by one builder.\n` +
              `  If this cannot wait for delivery, file it with \`followup "..."\` — a dated header line, not a row.`,
          );
        }
        return withNote(current, block, text);
      });
      console.log(`${id}: note appended`);
      await ledgerEvents.emit({ ledgerPath, verb: "note", itemId: id, args: rest });
      return 0;
    }

    case "claim": {
      const id = positional(rest, 0, "an item id");
      // `focus` spells the seat `--seat S` and `claim` spelled it positionally, while `positional()`
      // counts RAW argv — so `claim M7.7 --seat scout-builder3` recorded the seat name "--seat" and
      // dropped the real one without a word. M7.7 carried that claim for three hours (2026-09-18):
      // the documented positional form then fails with "already claimed by --seat", every
      // fence-overlap warning names a seat that does not exist, and `release-stale` cannot tell it
      // from a live claim. Both spellings are accepted now and a flag-shaped name is refused.
      // `--session` is manifest.py's spelling, the one every seat's habit and packet carries (delta 14).
      const overrideRetired = rest.includes("--override-retired");
      const claimArgs = rest.filter((a) => a !== "--override-retired");
      const seat = oneLine("seat", flag(claimArgs, "seat") ?? flag(claimArgs, "session") ?? positional(claimArgs, 1, "a seat name"));
      if (seat.startsWith("-")) throw new LedgerError(`"${seat}" is a flag, not a seat name — write \`claim ${id} <seat>\` or \`claim ${id} --seat <seat>\``);
      await mutate(ledgerPath, (current) => {
        // manifest.py's retirement guard, checked first as it does (delta 14): 17 open web-console
        // rows carry a retirement marker, and without it the first pull would hand one out.
        const marker = retirementMarker(notesOf(current, findBlock(locateItems(current), id)));
        if (marker !== null && !overrideRetired) {
          throw new LedgerError(
            `${id} carries a RETIREMENT marker in its own notes — refusing without an explicit override.\n  ${marker}\n` +
              `If this is a fresh, operator-approved re-queue, pass --override-retired — or record the lift: note ${id} "RETIRE-LIFTED: <ruling>"`,
          );
        }
        const block = findBlock(locateItems(current), id);
        // The arm the row was specified as. Kept despite the measurement that `claim` has never been
        // called once in 155 commits: it costs nothing and is live the day anyone uses the verb.
        if (isMachineryRow(block.item) && seat !== MACHINERY_SEAT) {
          throw new LedgerError(
            `${id} is a MACHINERY row and belongs to ${MACHINERY_SEAT}, not ${seat}.\n` +
              `  Fence: ${block.item.files.join(", ")} — apparatus only.\n` +
              `  Builders work the MAIN CAMPAIGN; when it has no open rows, builders are DONE.`,
          );
        }
        // DELTA 14 (splice V4-143 D2, orchestrator ruling 2026-09-18): a claim is manifest.py's —
        // "claim ownership, set in_flight, append CLAIM note". build-punch-list.mjs:40 and
        // idle-watch.ts:165 both gate on in_flight and read the owner from that note, so a claim that
        // only wrote the fields left both silent. The FIELDS stay the truth, the note is the diary.
        if (block.item.status === "done" || block.item.status === "verified") {
          throw new LedgerError(`${id} is ${block.item.status} — claim only todo/in_flight items`);
        }
        const holder = block.item.claimedBy ?? (block.item.status === "in_flight" ? lastClaimOwner(current, block) : undefined);
        if (holder !== undefined && holder !== seat) {
          throw new LedgerError(
            `${id} is already claimed by ${holder}${block.item.claimedAt === undefined ? "" : ` since ${block.item.claimedAt}`} — ` +
              `use release-stale if that seat is dead`,
          );
        }
        if (holder === seat && block.item.status === "in_flight" && block.item.claimedBy === seat) return current;
        // fences-are-disjoint had no wall: two live rows sharing a file deadlock `stage` (the other
        // row's edits are "fenced files not on the receipt"). A claim is REFUSED when its fence
        // intersects a live peer's, naming the peer (manifest.py:4105, ITEM 7).
        //
        // VENDORING DELTA 9 (splice V4-143 Phase C, 2026-09-18): WHERE manifest.py REFUSES, THIS
        // REFUSES; where only this CLI had an opinion, its warning stands. Both rulings are real
        // and they govern different rows, which is only visible by reading each CLI's peer set:
        //
        //   IN_FLIGHT peer      manifest.py:4596 counts every in_flight row, blind to claimed_by
        //                       (a field it never writes), and REFUSES the claim (manifest.py:4105).
        //                       That is this ledger's status quo, and NEVER-BELOW-STATUS-QUO governs
        //                       a cutover: a rewrite that turns today's refusal into tomorrow's
        //                       warning ships a regression under the name of a port. An in_flight
        //                       row is someone editing those files right now.
        //   CLAIMED TODO peer   a row a seat holds but has not started. manifest.py cannot see it
        //                       at all; this CLI warns and proceeds, under the operator ruling that
        //                       ceremony never stalls a row, and that arm is proven in the selftest.
        //                       Nothing is lost by keeping it — no edits are in flight to collide.
        //
        // Refusing BOTH was the first cut here and it broke seven proven arms, which is the arms
        // doing their job: the union looked fail-closed and was really a third policy neither CLI
        // implements. The migration below is what keeps the in_flight case honest — an in_flight
        // row whose CLAIM note never became fields still refuses, because status is enough.
        const live = locateItems(current).filter(
          (b) => b.item.id !== id && b.item.status !== "done" && b.item.status !== "verified",
        );
        for (const other of live) {
          const shared = fenceOverlap(block.item.files, other.item.files);
          if (shared.length === 0) continue;
          if (other.item.status === "in_flight") {
            throw new LedgerError(
              `${id}'s fence intersects live row ${other.item.id}'s on ${shared.join(", ")} — ` +
                `fences are disjoint (seat law); a row whose fence overlaps an in_flight one waits.\n` +
                `  ${other.item.id} is in_flight${other.item.claimedBy === undefined ? "" : ` and held by ${other.item.claimedBy}`}.`,
            );
          }
          if (other.item.claimedBy !== undefined && other.item.claimedBy !== seat) {
            console.error(`WARNING ${id}'s fence overlaps ${other.item.id} (claimed by ${other.item.claimedBy}) on ${shared.join(", ")} — re-read before every edit in those paths; report a same-line collision to the orchestrator`);
          }
        }
        // ONE BUILDER WORKS A REVIEW MILESTONE. The others continue with the next milestone; a
        // second seat claiming into `<M>-review` is how fixes fan out into a campaign of their own.
        if (milestoneOf(block.item.phase) !== null) {
          const holder = locateItems(current).find(
            (b) => b.item.id !== id && b.item.phase === block.item.phase && b.item.claimedBy !== undefined && b.item.claimedBy !== seat && b.item.status !== "done" && b.item.status !== "verified",
          );
          if (holder !== undefined) {
            throw new LedgerError(
              `one builder works a review milestone: ${block.item.phase} is held by ${holder.item.claimedBy} (${holder.item.id} is open).\n` +
                `  ${seat} continues with the next milestone; ${holder.item.claimedBy} takes ${id} when ${holder.item.id} is done.\n` +
                `  If ${holder.item.claimedBy} is dead, release-stale frees its rows.`,
            );
          }
        }
        const at = new Date();
        let next = withField(current, block, "claimed_by", seat);
        next = withField(next, findBlock(locateItems(next), id), "claimed_at", at.toISOString());
        const claimed = findBlock(locateItems(next), id);
        if (claimed.item.status !== "in_flight") next = withStatus(next, claimed, "in_flight");
        return withNote(next, findBlock(locateItems(next), id), claimNote(seat, at));
      });
      console.log(`${id} claimed by ${seat} -> in_flight`);
      await ledgerEvents.emit({ ledgerPath, verb: "claim", itemId: id, seat, args: claimArgs }); // a re-claim too, as manifest.py does
      return 0;
    }

    case "migrate-claims": {
      // VENDORING DELTA 10 (splice V4-143 Phase C, orchestrator ruling 2026-09-18): THE CUTOVER
      // MIGRATION. manifest.py records ownership as a dated `CLAIM: owner=SEAT at=ISO` NOTE; this
      // CLI reads the `claimed_by`/`claimed_at` FIELDS and never the notes. So on the day the .py
      // is deleted, every in-flight row it claimed reads as UNCLAIMED here — `release-stale` would
      // find nothing to release, `claim` would hand a live row to a second seat, and the ledger
      // would say a row is in_flight with no one on it. This verb reads each in_flight row's LAST
      // CLAIM note and writes those two fields.
      //
      // IDEMPOTENT AND RE-RUNNABLE ON PURPOSE, not a one-shot: it is safe to run while manifest.py
      // is still live (the fields are inert to that CLI — it has no key whitelist and reads owner
      // from its notes), and a py claim landing afterwards is picked up by running it again. Phase
      // D's last act before deleting the .py is one more run, so no claim taken in the final
      // minutes is lost. A row whose fields already match its note is left untouched, and a field
      // that DISAGREES with the note is reported and not overwritten: that is either a seat mid-
      // handover or the two CLIs having both claimed, and neither is a thing to paper over.
      //
      // claimed_at IS THE MIGRATION TIME, NOT THE NOTE'S, and that is the one non-obvious choice
      // here. The two CLIs judge staleness differently: manifest.py's release-stale asks whether
      // the owner is ALIVE (registry + tmux, per row), while this CLI's is AGE ONLY, in batch,
      // 60 minutes by default. Carried across verbatim, a claim taken at 07:09 is twelve hours
      // "stale" at the cutover, and the first release-stale afterwards frees every live row at
      // once — measured on a copy of this ledger: all five in_flight claims were over an hour old.
      // Starting the lease at the cutover loses nothing, because the CLAIM note stays in the diary
      // with the original time in it, byte for byte.
      const dryRun = rest.includes("--dry-run");
      const leaseStart = new Date().toISOString();
      const migrated: string[] = [];
      const conflicts: string[] = [];
      let already = 0;
      for (const block of blocks) {
        if (block.item.status !== "in_flight") continue;
        const claim = notesOf(lines, block)
          .map((note) => /CLAIM: owner=(\S+) at=(\S+)/.exec(note))
          .filter((match) => match !== null)
          .at(-1);
        if (claim === undefined) continue;
        const [, owner, at] = claim;
        if (owner === undefined || at === undefined) continue;
        if (block.item.claimedBy === owner) {
          already += 1;
          continue;
        }
        if (block.item.claimedBy !== undefined) {
          conflicts.push(`${block.item.id}: field says ${block.item.claimedBy}, last CLAIM note says ${owner}`);
          continue;
        }
        migrated.push(`${block.item.id} -> ${owner} (claimed ${at})`);
        if (dryRun) continue;
        await mutate(ledgerPath, (current) => {
          const located = findBlock(locateItems(current), block.item.id);
          // Re-read under the lock: a claim taken since the scan is the newer truth (Eli F8).
          if (located.item.claimedBy !== undefined) return current;
          const withSeat = withField(current, located, "claimed_by", owner);
          return withField(withSeat, findBlock(locateItems(withSeat), block.item.id), "claimed_at", leaseStart);
        });
      }
      for (const conflict of conflicts) console.error(`CONFLICT ${conflict} — left as it is, resolve it before the cutover`);
      console.log(
        `${dryRun ? "would migrate" : "migrated"} ${migrated.length} claim${migrated.length === 1 ? "" : "s"}` +
          `${migrated.length === 0 ? "" : `: ${migrated.join(", ")}`}` +
          ` (${already} already on fields, ${conflicts.length} conflicting)`,
      );
      return conflicts.length === 0 ? 0 : 1;
    }

    case "release-stale": {
      // manifest.py's form is `release-stale <ID> --by <seat>`, one row. Here it is a batch by age,
      // so that habit — an ID and --by, both ignored — released EVERY claim older than an hour
      // (delta 14). A row id or --by is refused and pointed at the one-row verb.
      if (rest.includes("--by") || noFlagPositionals(rest).length > 0) {
        throw new LedgerError(
          `release-stale takes no row: it releases every claim older than --minutes N (default 60).\n` +
            `  For one row, the manifest.py habit \`release-stale <ID> --by <seat>\`, use \`release <ID>\`.`,
        );
      }
      const minutesText = flag(rest, "minutes") ?? "60";
      const minutes = Number(minutesText);
      if (!Number.isFinite(minutes) || minutesText.trim() === "") throw new LedgerError(`--minutes requires a number (got "${minutesText}")`);
      // DELTA 16: ONE PREDICATE FOR THE READ AND THE WRITE (orchestrator ruling 2026-09-18). A claim is
      // stale when it is at least N minutes old AND nothing was written to its row after its last
      // CLAIM note — manifest.py's stale-claims (G53), whose line-position test is exact on an
      // append-only ledger. The age alone released a seat that was posting notes as it worked.
      // `--dry-run` is stale-claims: the same rows, printed, nothing written.
      const staleNow = (lines: readonly string[], block: ItemBlock): boolean =>
        block.item.claimedAt !== undefined &&
        (Date.now() - Date.parse(block.item.claimedAt)) / 60_000 >= minutes &&
        !activityAfterClaim(lines, block);
      const stale = blocks.filter((block) => staleNow(lines, block));

      if (rest.includes("--dry-run")) {
        for (const block of stale) {
          const idle = (Date.now() - Date.parse(block.item.claimedAt!)) / 60_000;
          console.log(`{"id": ${JSON.stringify(block.item.id)}, "owner": ${JSON.stringify(block.item.claimedBy ?? null)}, "claimed_at": ${JSON.stringify(block.item.claimedAt)}, "minutes_idle": ${pyRound1(idle)}}`);
        }
        console.log(`stale-claims: ${stale.length} item(s) claimed >= ${minutesText}m ago with no owner activity`);
        return stale.length === 0 ? 0 : 1;
      }

      for (const block of stale) {
        await mutate(ledgerPath, (current) => {
          const located = findBlock(locateItems(current), block.item.id);
          // Re-check under the lock: a claim taken, or a note written, since the scan stays (Eli F8).
          if (!staleNow(current, located)) return current;
          return withoutClaim(current, block.item.id, located.item.claimedBy ?? "unknown", `stale, claimed ${located.item.claimedAt}, older than ${minutesText}m`);
        });
      }
      console.log(
        stale.length === 0
          ? `no claims older than ${minutes}m`
          : `released ${stale.map((block) => block.item.id).join(", ")}`,
      );
      return 0;
    }

    case "deliver": {
      // THE MILESTONE'S ONE REVIEW POINT. Orchestrator-gated above (it claims the milestone is on
      // the pushed tree). Every row done, the header records the sha, and from here on the
      // milestone takes no rows: fixes are `<M>-review`, everything else is `followup`.
      const phase = positional(rest, 0, "a phase");
      const sha = oneLine("sha", required(rest, "sha"));
      noExtraPositionals(rest, 1);
      if (sha.trim() === "" || sha.startsWith("-")) throw new LedgerError(`deliver needs the pushed commit: \`deliver ${phase} --sha <sha>\``);
      await mutate(ledgerPath, (current) => {
        const inPhase = locateItems(current).filter((b) => b.item.phase === phase);
        if (inPhase.length === 0) throw new LedgerError(`no items in phase "${phase}"`);
        const already = deliveredAt(current, phase);
        if (already !== null) throw new LedgerError(`${phase} is already delivered at ${already}`);
        const open = inPhase.filter((b) => b.item.status !== "done" && b.item.status !== "verified");
        if (open.length > 0) throw new LedgerError(`${phase} is not ready: ${open.map((b) => `${b.item.id} [${b.item.status}]`).join(", ")} still open`);
        const header = headerLines(current);
        let insertAt = header.length;
        while (insertAt > 0 && (current[insertAt - 1] ?? "").trim() === "") insertAt -= 1;
        const next = [...current];
        next.splice(insertAt, 0, `# ${today()} DELIVERED ${phase} at ${sha} — every row done; the review runs on this sha, its fixes are rows of ${phase}${REVIEW_SUFFIX} (one builder), everything else is followup`);
        return next;
      });
      console.log(`${phase}: delivered at ${sha} — review it on that sha; fixes go to \`add --phase ${phase}${REVIEW_SUFFIX}\`, one builder; the other builders continue with the next milestone`);
      return 0;
    }

    case "plan": {
      // Orchestrator-gated above. Writes or raises the planned count; a raise keeps the old line's
      // number in a dated header note so the decision is on the record.
      const count = Number.parseInt(positional(rest, 0, "the planned row count"), 10);
      noExtraPositionals(rest, 1);
      if (!Number.isInteger(count) || count <= 0) throw new LedgerError(`plan needs a positive row count: \`plan 15\``);
      // A BOX, NOT A `let`: TypeScript does not track an assignment made inside the `mutate` closure, so
      // a later `previous === null` reads as always-true and eslint's no-unnecessary-condition is right
      // to say so. The type was not lying and neither was the code — the model of the flow was.
      const seen: { previous: number | null } = { previous: null };
      await mutate(ledgerPath, (current) => {
        seen.previous = plannedRows(current);
        const next = [...current];
        const line = `# PLANNED ${count} rows — ceiling ${count * CEILING_FACTOR} milestone rows; \`add\` refuses past it (review-milestone rows excluded)`;
        const at = headerLines(current).findIndex((l) => PLANNED_RE.test(l));
        if (at >= 0) {
          next[at] = line;
          next.splice(at + 1, 0, `# ${today()} PLAN CHANGED from ${seen.previous ?? 0} to ${count} rows by the orchestrator`);
          return next;
        }
        let insertAt = headerLines(current).length;
        while (insertAt > 0 && (current[insertAt - 1] ?? "").trim() === "") insertAt -= 1;
        next.splice(insertAt, 0, line);
        return next;
      });
      console.log(seen.previous === null ? `planned ${count} rows — ceiling ${count * CEILING_FACTOR}` : `plan changed ${String(seen.previous)} → ${count} rows — ceiling ${count * CEILING_FACTOR}; recorded in the header`);
      return 0;
    }

    case "review": {
      // THE REVIEW BRIEF, generated from the ledger so a review never runs without a goal. Operator,
      // 2026-09-18: "if you ask a model to review code without a goal, without lenses to look
      // through, or an option to say pass, it will always find something to fix; outside a campaign
      // that is small work, then done, but as a campaign row it brings all the ceremony that creates
      // the loop that never ends." So: the goal is the delivered milestone, the lenses are three and
      // named, PASS is the expected outcome of a green milestone, and everything that is filed goes
      // into ONE row of `<M>-review` or a `followup` line — never one row per finding.
      const phase = positional(rest, 0, "a delivered milestone");
      noExtraPositionals(rest, 1);
      if (milestoneOf(phase) !== null) throw new LedgerError(`${phase} is a review milestone; there is no review of a review. Its close is \`verify-phase ${phase}\`; anything found later is \`followup\`.`);
      const sha = deliveredAt(lines, phase);
      if (sha === null) throw new LedgerError(`${phase} is not delivered, so there is nothing to review yet: builders work its rows without review; \`deliver ${phase} --sha <sha>\` first.`);
      const rows = locateItems(lines).filter((b) => b.item.phase === phase);
      if (rows.length === 0) throw new LedgerError(`no items in phase "${phase}"`);
      const reviewPhase = `${phase}${REVIEW_SUFFIX}`;
      const existing = locateItems(lines).find((b) => b.item.phase === reviewPhase);
      console.log(
        [
          `REVIEW OF ${phase} — delivered at ${sha}. Read THAT tree, not the worktree.`,
          ``,
          `THE GOAL: ${phase} promised these outcomes, each with the command that proves it —`,
          ...rows.map((b) => `  ${b.item.id}  ${b.item.title.split("\n")[0]?.slice(0, 140) ?? ""}\n        verify: ${b.item.verify === "" ? "(none declared)" : b.item.verify}`),
          ``,
          `THE LENSES — a finding is a finding only through one of these, and it names which:`,
          `  1. THE GATE: does each verify line above go RED on a synthetic violation and GREEN at ${sha}?`,
          `  2. THE CLAIM: does the delivered code do what its row's title says, measured, not read?`,
          `  3. THE SEAM: did ${phase} change something outside its rows' fences that the next milestone stands on?`,
          `  Anything seen through no lens is not a finding of this review. It is small work someone does`,
          `  when they are in that file, or a \`followup\` line. Style, naming, comments, "could be simpler",`,
          `  a wall that could be stronger: no lens.`,
          ``,
          `THE OUTCOME, one of two:`,
          `  PASS — the expected result of a green milestone. An empty list is a valid, complete review.`,
          `        File it: \`verify-phase ${phase} "<CI run>"\`.`,
          `  FIX  — ONE row, \`add --phase ${reviewPhase} --title "<the numbered list>" --files <the fence>\`,`,
          `        worked by ONE builder in one sitting, then \`verify-phase ${reviewPhase}\`. A second row`,
          `        in ${reviewPhase} is refused: a list that does not fit one row is not a review's output,`,
          `        it is the next milestone's plan. Everything else: \`followup "..."\`.`,
          existing === undefined ? `` : `\n${reviewPhase} already holds ${existing.item.id} [${existing.item.status}] — amend it, do not add.`,
        ].join("\n"),
      );
      return 0;
    }

    case "followup": {
      // A finding that is NOT a row: found mid-milestone (no review per row), or after a review
      // milestone closed (no review of a review). Dated, in the header, off the critical path.
      const text = oneLine("follow-up text", positional(rest, 0, "follow-up text"));
      noExtraPositionals(rest, 1);
      await mutate(ledgerPath, (current) => {
        const header = headerLines(current);
        let insertAt = header.length;
        while (insertAt > 0 && (current[insertAt - 1] ?? "").trim() === "") insertAt -= 1;
        const next = [...current];
        next.splice(insertAt, 0, `# ${today()} FOLLOW-UP: ${text}`);
        return next;
      });
      console.log("follow-up recorded in the header — not a row, not on the critical path");
      return 0;
    }

    case "add": {
      noExtraPositionals(rest, 0);
      const id = oneLine("id", required(rest, "id"));
      const title = oneLine("title", required(rest, "title"));
      const verify = oneLine("verify", flag(rest, "verify") ?? "");
      const phase = oneLine("phase", required(rest, "phase"));
      const files = (flag(rest, "files") ?? "")
        .split(",")
        .map((piece) => piece.trim())
        .filter((piece) => piece !== "");
      const status = flag(rest, "status") ?? "todo";
      if (!isItemStatus(status)) throw new LedgerError(`"${status}" is not a status`);
      assertFenceShape(repoRootOf(ledgerPath), files);

      await mutate(ledgerPath, (current) => {
        // Inside the lock, not before it: two concurrent adds of one id produced two blocks that
        // `validate` accepted and no verb could address (Eli F6).
        if (locateItems(current).some((block) => block.item.id === id)) throw new LedgerError(`item "${id}" already exists`);
        /*
         * A CLOSED DELIVERABLE IS THE SIGNAL TO STOP, NOT THE SIGNAL TO AUDIT THE APPARATUS AGAIN.
         *
         * The deliverable finished at 03:43 inside its estimate; the next nine hours produced 101
         * apparatus rows, 28 of them in the 07:00 hour alone, because a campaign with no work left
         * looked like a campaign that needed more work found. This is the wall for that hour: while no
         * main-campaign row is open, a machinery row is not born at all. Read inside the lock so a
         * `set-status` racing this `add` is seen.
         *
         * IT REFUSES THE ORCHESTRATOR TOO, deliberately: the seat that opened those rows is the seat
         * this governs, and a wall its author can step around is the prose the campaign already had.
         */
        if (isMachineryRow({ files })) {
          const open = mainCampaignOpen(locateItems(current));
          if (open === undefined) {
            throw new LedgerError(
              `${id} is a MACHINERY row and the main campaign has no open rows — so there is nothing to keep the apparatus ready FOR.\n` +
                `  Fence: ${files.join(", ")} — apparatus only.\n` +
                `  The deliverable is finished. A defect found now is \`followup "..."\`; it becomes a row when it blocks\n` +
                `  the next milestone's work, and that milestone's rows come first.\n` +
                `  (operator ruling 2026-09-18: builders work the main campaign, and when it has no open rows they are DONE.)`,
            );
          }
        }
        // WHERE A ROW MAY BE BORN, by the ontology above. Read inside the lock: a `deliver` racing
        // this `add` must be seen.
        if (phase.endsWith(`${REVIEW_SUFFIX}${REVIEW_SUFFIX}`)) {
          throw new LedgerError(`there is no review of a review: "${phase}" cannot exist. A finding on a review milestone's fix goes to \`followup "..."\`.`);
        }
        const milestone = milestoneOf(phase);
        if (milestone !== null) {
          // ONE ROW PER REVIEW MILESTONE. The review's output is a list one builder works in one
          // sitting; a row per finding is the ceremony that turned 15 rows into 143.
          const held = locateItems(current).find((b) => b.item.phase === phase);
          if (held !== undefined) {
            throw new LedgerError(
              `${phase} already holds ${held.item.id} [${held.item.status}] — a review milestone is ONE row for ONE builder.\n` +
                `  Extend its list with \`amend ${held.item.id} --title "..."\` while it is open; once it is done, a new\n` +
                `  finding is \`followup "..."\` or a row of the next milestone.`,
            );
          }
        }
        if (milestone !== null && deliveredAt(current, milestone) === null) {
          throw new LedgerError(
            `${phase} opens at the delivery of ${milestone}, which is not delivered.\n` +
              `  Rows of ${milestone} are built without review; the review runs once, on the delivered tree:\n` +
              `  \`deliver ${milestone} --sha <sha>\` (orchestrator), then \`add --phase ${phase}\` for each fix.\n` +
              `  Until then a finding is \`followup "..."\`.`,
          );
        }
        const planned = plannedRows(current);
        if (milestone === null && planned === null) {
          /*
           * NEVER UNBOUNDED BY OMISSION — ENFORCED WHERE THE OMISSION HAPPENS, WHICH IS `init`, NOT HERE.
           *
           * This refused instead, and refusing here meant `add` was dead on EVERY ledger that predates the
           * feature, since none of them declares a plan. MEASURED: it took out the survival-stack fixture
           * (`tests/hooks/bun-lifecycle.test.ts:82`), the one that proves the laws re-arrive after a
           * compaction, and it would have taken `add` from every repo vendoring this CLI.
           *
           * A NEW LEDGER CANNOT REACH THIS BRANCH: `init` now requires `--rows` and writes the PLANNED
           * line at birth. So the only ledgers here are legacy ones, and they grow — loudly, on every
           * add — rather than being stopped by a rule that did not exist when they were born.
           */
          console.error(
            "WARNING no plan declared — this ledger has no ceiling: `plan <N>` (orchestrator) records the planned row count and `add` then refuses past 2N",
          );
        }
        if (milestone === null && planned !== null) {
          const ceiling = planned * CEILING_FACTOR;
          const held = locateItems(current).filter((b) => milestoneOf(b.item.phase) === null).length;
          if (held >= ceiling) {
            throw new LedgerError(
              `the campaign planned ${planned} rows and holds ${held} milestone rows — the ceiling is ${ceiling}, and \`add\` refuses past it.\n` +
                `  A finding is \`followup "..."\`; open rows are finished and delivered, not joined by more.\n` +
                `  A raise is a recorded decision: \`plan <N>\` by the orchestrator, dated in the header.`,
            );
          }
        }
        const delivered = deliveredAt(current, phase);
        if (delivered !== null) {
          throw new LedgerError(
            milestone === null
              ? `${phase} was delivered at ${delivered}; no row is added to a delivered milestone.\n` +
                  `  A fix from its review is a row of ${phase}${REVIEW_SUFFIX} (\`add --phase ${phase}${REVIEW_SUFFIX}\`, one builder);\n` +
                  `  anything else is \`followup "..."\` or a row of the next milestone.`
              : `${phase} was delivered at ${delivered} and there is no review of a review: \`followup "..."\`.`,
          );
        }
        const trimmed = [...current];
        while (trimmed.length > 0 && (trimmed.at(-1) ?? "").trim() === "") trimmed.pop();
        return [
          ...trimmed,
          ``,
          `[[items]]`,
          `id = ${toml(id)}`,
          `phase = ${toml(phase)}`,
          `title = ${toml(title)}`,
          `files = [${files.map((file) => toml(file)).join(", ")}]`,
          `status = ${toml(status)}`,
          `verify = ${toml(verify)}`,
          ``,
        ];
      });
      console.log(`added ${id}`);
      return 0;
    }

    case "amend": {
      const id = positional(rest, 0, "an item id");
      const title = flag(rest, "title");
      const verify = flag(rest, "verify");
      const filesCsv = flag(rest, "files");
      const amendedBy = flag(rest, "seat") ?? (process.env.LEDGER_SEAT || null) ?? // an empty LEDGER_SEAT wrote `by=` (delta 13)
        (process.env[ORCHESTRATOR_ENV] === "1" ? "orchestrator" : "unattributed");
      if (title === null && verify === null && filesCsv === null) {
        throw new LedgerError("amend: provide at least one of --title, --verify, --files");
      }
      if (filesCsv !== null) {
        assertFenceShape(repoRootOf(ledgerPath), filesCsv.split(",").map((piece) => piece.trim()).filter((piece) => piece !== ""));
      }
      await mutate(ledgerPath, (current) => {
        let next = [...current];
        let block = findBlock(locateItems(next), id);
        const audit: string[] = [];
        if (title !== null) {
          audit.push(`title was ${toml(block.item.title)}`);
          next = withField(next, block, "title", title);
          block = findBlock(locateItems(next), id);
        }
        if (verify !== null) {
          audit.push(`verify was ${toml(block.item.verify)}`);
          next = withField(next, block, "verify", verify);
          block = findBlock(locateItems(next), id);
        }
        if (filesCsv !== null) {
          const files = filesCsv
            .split(",")
            .map((piece) => piece.trim())
            .filter((piece) => piece !== "");
          audit.push(`files were [${block.item.files.map((file) => toml(file)).join(", ")}]`);
          let replaced = false;
          for (let index = block.start; index < block.end; index += 1) {
            if (/^\s*files\s*=/.test(next[index] ?? "")) {
              next[index] = `files = [${files.map((file) => toml(file)).join(", ")}]`;
              replaced = true;
              break;
            }
          }
          if (!replaced) throw new LedgerError(`item "${id}" has no files line`);
          block = findBlock(locateItems(next), id);
        }
        // The old values are the audit trail: an amend that leaves no trace of what it replaced
        // is a rewrite of history, which is exactly what this CLI exists to prevent.
        // VENDORING DELTA 6 (splice V4-143, orchestrator ruling 2026-09-18): the note also names WHO.
        // Notes are the past and fields the present; the field holds the new value, but "who amended
        // this row" is the question asked when two seats' work disagrees, and no field answers it.
        return withNote(next, block, `amend by=${amendedBy}: ${audit.join("; ")}`);
      });
      console.log(`${id}: amended — old values preserved as a dated note`);
      return 0;
    }

    case "add-law": {
      const text = oneLine("law text", positional(rest, 0, "law text"));
      await mutate(ledgerPath, (current) => {
        const header = headerLines(current);
        let insertAt = header.length;
        while (insertAt > 0 && (current[insertAt - 1] ?? "").trim() === "") insertAt -= 1;
        const next = [...current];
        next.splice(insertAt, 0, `# LAW [${today()}]: ${text}`); // manifest.py's dated form, delta 13
        return next;
      });
      console.log("law appended");
      return 0;
    }

    case "add-note": {
      // The campaign-level counterpart to `note <ID>`: a dated header line that is NOT a law. The
      // gap it fills is real — a lesson with no row had only two surfaces, one that demands an id
      // and one that overwrites — and keeping it out of `# LAW:` is the point rather than a detail:
      // the law lines are injected verbatim into every seat's SessionStart, so a lesson filed as a
      // law is a permanent tax on every context in the campaign.
      const text = oneLine("note text", positional(rest, 0, "note text"));
      noExtraPositionals(rest, 1);
      await mutate(ledgerPath, (current) => {
        const header = headerLines(current);
        let insertAt = header.length;
        while (insertAt > 0 && (current[insertAt - 1] ?? "").trim() === "") insertAt -= 1;
        const next = [...current];
        next.splice(insertAt, 0, `# ${today()} ${text}`);
        return next;
      });
      console.log("campaign note appended to the header");
      return 0;
    }

    case "release": {
      // `release-stale` decides by AGE, so it cannot touch one row without touching every claim
      // older than it — and a claim that is simply WRONG has no age. M7.7 spent three hours claimed
      // by the literal string "--seat" with no lawful repair: the only route was a cutoff that
      // would also have released two live seats' rows. A named release is that repair, and it is
      // also the honest way to hand a row over.
      const id = positional(rest, 0, "an item id");
      noExtraPositionals(rest, 1);
      let previous: string | undefined;
      await mutate(ledgerPath, (current) => {
        const block = findBlock(locateItems(current), id);
        previous = block.item.claimedBy ?? (block.item.status === "in_flight" ? lastClaimOwner(current, block) : undefined);
        if (previous === undefined) return current;
        return withoutClaim(current, id, previous, "named");
      });
      console.log(previous === undefined ? `${id}: no claim to release` : `${id}: released ${previous} — the row is todo and unclaimed; \`claim ${id} <seat>\` takes it`);
      return 0;
    }

    case "amend-header": {
      const old = positional(rest, 0, "the existing header text");
      const replacement = positional(rest, 1, "the replacement text");
      await mutate(ledgerPath, (current) => {
        const headerLength = headerLines(current).length;
        const index = current.findIndex((line, at) => at < headerLength && line.includes(old));
        if (index === -1) throw new LedgerError(`no header line contains "${old}"`);
        const next = [...current];
        next[index] = (next[index] ?? "").replace(old, replacement);
        return next;
      });
      console.log("header amended");
      return 0;
    }

    default: {
      const handled = await handleLedgerEarn(ledgerPath, command, rest, {
        locateItems,
        findBlock,
        flag,
      });
      if (handled) return 0;
      console.error(`unknown command "${command}"\n\n${USAGE}`);
      return 1;
    }
  }
}

// ── selftest ──────────────────────────────────────────────────────────────────────────────────

/**
 * Runs the CLI against a throwaway ledger. Required at every vendoring: a ledger CLI that has
 * never been watched preserve a comment is a ledger CLI that will one day eat the memory.
 */
async function selftest(): Promise<number> {
  // The fixture is a real git repository: `receipt` checks paths exist and `stage` adds to an index.
  const repo = mkdtempSync(join(process.env.TMPDIR ?? "/tmp", "eli-ledger-selftest-"));
  const sh = (...args: string[]): string => { const r = Bun.spawnSync(args, { cwd: repo, stdout: "pipe", stderr: "pipe" }); return r.stdout.toString() + r.stderr.toString(); };
  sh("git", "init", "-q"); sh("git", "config", "user.email", "selftest@example.invalid"); sh("git", "config", "user.name", "selftest");
  mkdirSync(join(repo, ".dev", "campaigns"), { recursive: true }); mkdirSync(join(repo, "src"), { recursive: true }); mkdirSync(join(repo, "tests"), { recursive: true });
  for (const rel of ["src/a.ts", "src/b.ts", "src/c.ts", "src/zz.ts", "tests/x.test.ts"]) await Bun.write(join(repo, rel), `// ${rel}\n`);
  // A base commit: the modified-file path through `stage` is the real one (a fixture of only
  // untracked files never exercised `git diff` against HEAD; picasso-lead's lesson, 2026-09-17).
  sh("git", "add", "-A"); sh("git", "commit", "-q", "-m", "base");
  const path = join(repo, ".dev", "campaigns", "selftest.toml");
  let failures = 0;
  let checks = 0;

  const check = (label: string, ok: boolean, detail = ""): void => {
    checks += 1;
    if (ok) return;
    failures += 1;
    console.error(`  FAIL  ${label}${detail === "" ? "" : `\n        ${detail}`}`);
  };

  await Bun.write(
    path,
    [
      `# selftest ledger`,
      `# PLANNED 400 rows — the fixture declares a plan so its arms can add; the ceiling arms lower it`,
      `# LAW: manifest-is-memory — an item must be resumable from the ledger alone`,
      `# LAW: verified-commits-immediately`,
      `# LAW [2026-09-18]: dated-law-form — manifest.py dates a law in its prefix`,
      ``,
      `[[items]]`,
      `id = "H1"`,
      `phase = "harness"`,
      `title = "first item"`,
      `files = ["dev/a.ts"]`,
      `status = "todo"`,
      `verify = "bun run gate"`,
      `# 2026-07-26 a pre-existing note that must survive every write`,
      ``,
      `[[items]]`,
      `id = "H2"`,
      `phase = "harness"`,
      `title = "second item"`,
      `files = []`,
      `status = "verified"`,
      `verify = ""`,
      ``,
    ].join("\n"),
  );

  const run = async (...args: string[]): Promise<string> => {
    const proc = Bun.spawn(["bun", import.meta.path, path, ...args], {
      env: { ...process.env, LEDGER_ORCHESTRATOR: "" }, // the selftest must not inherit the orchestrator grant
      stdout: "pipe",
      stderr: "pipe",
    });
    const out = await new Response(proc.stdout).text();
    const err = await new Response(proc.stderr).text();
    await proc.exited;
    return out + err;
  };

  console.log("ledger selftest");

  check("list shows both items", (await run("list")).includes("H1") && (await run("list")).includes("H2"));
  check("list --status filters", !(await run("list", "--status", "todo")).includes("H2"));
  check("get returns the item", (await run("get", "H1")).includes("first item"));
  check("get surfaces existing notes", (await run("get", "H1")).includes("must survive"));
  // DELTA 11 ARMS: the machine shapes the repo's callers parse (law-check.mjs, build-punch-list.mjs).
  // The contract is the caller's regex, so the arms assert THAT, not a look-alike.
  {
    const plain = (await run("list", "--plain")).trim().split("\n");
    check("list --plain puts the row id FIRST on every line (callers split on whitespace, token 0)",
      plain.length === 2 && plain.map((line) => line.split(/\s+/)[0]).join(",") === "H1,H2");
    const raw = await run("get", "H1", "--raw");
    check("get --raw is the item block as it sits in the file (callers match ^status = \"...\"$)",
      /^id = "H1"$/m.test(raw) && /^status = "todo"$/m.test(raw) && /^files = \[/m.test(raw) && raw.includes("must survive") && !raw.includes("▸"));
    check("get --raw prints the block and nothing after it",
      raw.trimEnd().endsWith("# 2026-07-26 a pre-existing note that must survive every write"));
  }
  check("next prefers an open item", (await run("next")).includes("H1"));
  check("laws reads the header", (await run("laws")).includes("manifest-is-memory"));
  check("laws reads a dated law too (delta 4: 58 of splice's 59 laws are dated)",
    (await run("laws")).includes("dated-law-form"));
  {
    const bare = join(repo, ".dev", "campaigns", "bare.toml");
    await Bun.write(bare, "# no laws here\n\n[[items]]\nid = \"Z1\"\nphase = \"p\"\ntitle = \"t\"\nfiles = []\nstatus = \"todo\"\nverify = \"\"\n");
    const r = Bun.spawnSync(["bun", import.meta.path, bare, "laws"], { stdout: "pipe", stderr: "pipe" });
    check("laws prints nothing for a header without laws", r.exitCode === 0 && r.stdout.toString().trim() === "");
  }
  check("a note with a newline is refused (TOML injection)", (await run("note", "H1", "line one\n[[items]]\nid = \"GHOST\"")).includes("single line"));
  check("packet is self-contained", (await run("packet", "H1")).includes("WRITABLE FENCE"));
  check("packet carries the laws", (await run("packet", "H1")).includes("manifest-is-memory"));

  await run("set-status", "H1", "in_flight");
  await run("note", "H1", "a note added by the selftest");
  await run("claim", "H1", "builder-1");

  const afterWrites = await Bun.file(path).text();

  // THE LOAD-BEARING ASSERTION. A serializer round-trip would have silently eaten both of these,
  // the file would still parse, every other check here would still pass, and the campaign's
  // memory would be gone. This is the check the whole line-surgical design exists to satisfy.
  check("header comments survive writes", afterWrites.includes("# LAW: manifest-is-memory"));
  check("pre-existing item notes survive writes", afterWrites.includes("must survive every write"));
  check("the new note landed", afterWrites.includes("a note added by the selftest"));
  check("the note is dated in manifest.py's form (delta 13)", afterWrites.includes(`# [${today()}] a note added`));

  check("set-status took effect", (await run("get", "H1")).includes("[in_flight]"));
  check("claim recorded the seat", (await run("get", "H1")).includes("builder-1"));
  check("a second claim by another seat is refused", (await run("claim", "H1", "builder-2")).includes("already claimed"));
  // DELTA 16: stale = old AND silent since the claim; the dry run is stale-claims, the same predicate.
  await run("add", "--id", "ST1", "--phase", "harness", "--title", "claimed and silent", "--verify", "true", "--files", "src/st1.ts");
  await run("add", "--id", "ST2", "--phase", "harness", "--title", "claimed and working", "--verify", "true", "--files", "src/st2.ts");
  await run("claim", "ST1", "seat-silent");
  await run("claim", "ST2", "seat-working");
  await run("note", "ST2", "still going: the parser half is in");
  {
    const before = await Bun.file(path).text();
    const proc = Bun.spawnSync(["bun", import.meta.path, path, "release-stale", "--minutes", "0", "--dry-run"], { env: { ...process.env, LEDGER_ORCHESTRATOR: "" }, stdout: "pipe", stderr: "pipe" });
    const out = proc.stdout.toString();
    check("release-stale --dry-run is stale-claims: the silent claim, not the working one, nothing written, exit 1 (delta 16)",
      proc.exitCode === 1 && /^\{"id": "ST1", "owner": "seat-silent", "claimed_at": "\S+", "minutes_idle": \d+\.\d\}$/m.test(out) &&
        !out.includes('"ST2"') && out.includes("stale-claims: ") && (await Bun.file(path).text()) === before, out);
  }
  check("release-stale spares a fresh claim", (await run("release-stale", "--minutes", "60")).includes("no claims older"));
  check("release-stale releases an old one", (await run("release-stale", "--minutes", "0")).includes("H1"));
  check("and releases what the dry run listed, sparing the row written to since its claim (delta 16)",
    (await run("get", "ST1")).includes("[todo]") && (await run("get", "ST2")).includes("claim  : seat-working"));
  check("release-stale clears back to todo, as manifest.py does, with a CLAIM-RELEASED marker (delta 14)",
    /\[todo\][\s\S]*CLAIM-RELEASED \(stale, claimed \S+, older than 0m\): owner=builder-1/.test(await run("get", "H1")));

  await run("add", "--id", "H3", "--phase", "harness", "--title", "third", "--verify", "bun run gate");
  check("add created the item", (await run("get", "H3")).includes("third"));
  check("duplicate ids are refused", (await run("add", "--id", "H3", "--phase", "p", "--title", "t")).includes("already exists"));

  // Law and header mutations remain line-surgical and preserve the surrounding campaign memory.
  await run("add-law", "silence-is-a-system-bug");
  check("add-law appends to the header", (await run("laws")).includes("silence-is-a-system-bug"));
  check("add-law did not disturb existing laws", (await run("laws")).includes("verified-commits-immediately"));
  check("add-law dates the law in manifest.py's form (delta 13)",
    (await Bun.file(path).text()).includes(`\n# LAW [${today()}]: silence-is-a-system-bug\n`));
  await run("amend-header", "manifest-is-memory", "manifest-is-durable-memory");
  check("amend-header replaces the selected text", (await run("laws")).includes("manifest-is-durable-memory"));

  // `amend` rewrites what a packet renders verbatim, so every use preserves the old value as a
  // dated note and leaves unrelated notes intact.
  await run("amend", "H1", "--verify", "a brand new verify gate");
  check("amend replaces the verify field", (await run("get", "H1")).includes("a brand new verify gate"));
  check("amend preserved the old value as a dated note", (await run("get", "H1")).includes("verify was"));
  check("amend names who made it (delta 6: notes are the past, and the past has an author)",
    /amend by=\S+: verify was/.test(await run("get", "H1")));
  {
    const blank = Bun.spawnSync(["bun", import.meta.path, path, "amend", "H1", "--verify", "gate two"], { env: { ...process.env, LEDGER_ORCHESTRATOR: "", LEDGER_SEAT: "" }, stdout: "pipe", stderr: "pipe" });
    check("an empty LEDGER_SEAT is no seat, not a blank author (delta 13)",
      blank.exitCode === 0 && (await run("get", "H1")).includes("amend by=unattributed: verify was \"a brand new verify gate\""));
  }
  check("amend did not eat prior notes", (await run("get", "H1")).includes("must survive"));
  check("amend with no field flags is refused", (await run("amend", "H1")).includes("at least one of"));

  // The milestone gate: refuses while a row is open, flips every done row at once, notes each.
  await run("add", "--id", "M1", "--phase", "m1", "--title", "row one", "--verify", "bun test tests/x", "--files", "src/a.ts,tests/x.test.ts");
  await run("add", "--id", "M2", "--phase", "m1", "--title", "row two", "--verify", "bun test tests/y", "--files", "src/b.ts,src/c.ts");
  // fences-are-disjoint has a wall: a claim whose fence intersects a live claimed row is refused.
  await run("add", "--id", "X1", "--phase", "m9", "--title", "overlapping row", "--verify", "", "--files", "src/c.ts,src/d.ts");
  await run("claim", "M2", "seat-1");
  // DELTA 14: a claim sets in_flight (manifest.py), so the overlap a claim used to warn about now
  // takes delta 9's refusal. Two live seats on one file arise only when a fence moves AFTER both
  // claims, which is how the audit arm below is set up.
  check("a claim sets in_flight and writes manifest.py's CLAIM note, beside the fields (delta 14)",
    /\[in_flight\][\s\S]*claim  : seat-1[\s\S]*# \[\d{4}-\d{2}-\d{2}\] CLAIM: owner=seat-1 at=\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z/.test(await run("get", "M2")));
  check("claim is refused on a fence that overlaps a claimed row, which is in_flight (delta 14)",
    (await run("claim", "X1", "seat-2")).includes("fences are disjoint") && !(await run("get", "X1")).includes("seat-2"));
  await run("amend", "X1", "--files", "src/d.ts");
  await run("claim", "X1", "seat-2");
  await run("amend", "X1", "--files", "src/c.ts,src/d.ts");
  check("claim admits a disjoint fence", (await run("claim", "M1", "seat-2")).includes("claimed by seat-2"));
  {
    // manifest.py's retirement guard and its two spellings, and its --session spelling (delta 14).
    await run("add", "--id", "RT1", "--phase", "m9", "--title", "a retired row", "--verify", "", "--files", "src/rt1.ts");
    await run("note", "RT1", "operator ruling: do not requeue, superseded");
    check("claim refuses a row a legacy retirement note retired (delta 14)",
      (await run("claim", "RT1", "--session", "seat-rt")).includes("RETIREMENT marker") && (await run("get", "RT1")).includes("[todo]"));
    await run("note", "RT1", "RETIRE-LIFTED: operator re-queued it");
    check("a structured RETIRE-LIFTED note lifts it, and --session names the seat (delta 14)",
      (await run("claim", "RT1", "--session", "seat-rt")).includes("claimed by seat-rt"));
    await run("add", "--id", "RT2", "--phase", "m9", "--title", "retired, structured", "--verify", "", "--files", "src/rt2.ts");
    await run("note", "RT2", "RETIRED: folded into RT1");
    check("a structured RETIRED note refuses, and --override-retired passes it (delta 14)",
      (await run("claim", "RT2", "seat-rt2")).includes("RETIREMENT marker") &&
        (await run("claim", "RT2", "seat-rt2", "--override-retired")).includes("claimed by seat-rt2"));
    check("release-stale refuses manifest.py's one-row form instead of releasing every old claim (delta 14)",
      (await run("release-stale", "RT1", "--by", "seat-rt")).includes("takes no row") && (await run("get", "RT1")).includes("claim  : seat-rt"));
  }
  check("a second claim by the holder writes nothing (delta 14)", await (async () => {
    const before = await Bun.file(path).text();
    await run("claim", "M1", "seat-2");
    return (await Bun.file(path).text()) === before;
  })());

  // DELTA 9 ARMS (V4-143 Phase C). The refusal case is an IN_FLIGHT peer, which is what
  // manifest.py refuses today and what this CLI must not lose at the cutover.
  await run("add", "--id", "X2", "--phase", "m9", "--title", "row over an in_flight fence", "--verify", "", "--files", "src/e.ts");
  await run("add", "--id", "X3", "--phase", "m9", "--title", "the in_flight peer", "--verify", "", "--files", "src/e.ts");
  await run("set-status", "X3", "in_flight");
  check("claim is REFUSED on a fence that intersects an in_flight row (manifest.py parity)",
    (await run("claim", "X2", "seat-3")).includes("fences are disjoint") && !(await run("get", "X2")).includes("seat-3"));
  check("the refusal names the row and the shared path, not just the rule",
    /X3.*src\/e\.ts|src\/e\.ts.*X3/s.test(await run("claim", "X2", "seat-3")));
  // THE GLOB CASE, which is why this is a port and not a warn-to-refuse flip: `src/g/**` and
  // `src/g/deep.ts` are the same fence, share no equal string, and neither ends in `/`. The old
  // string comparison found no overlap and admitted the claim. V4-139's own fence carries three
  // such globs, so this shape is live in this ledger, not synthetic.
  await run("add", "--id", "G1", "--phase", "m9", "--title", "a glob fence", "--verify", "", "--files", "src/g/**");
  await run("set-status", "G1", "in_flight");
  await run("add", "--id", "G2", "--phase", "m9", "--title", "a file under that glob", "--verify", "", "--files", "src/g/deep.ts");
  check("a glob fence and a file beneath it are ONE fence (delta 8)",
    (await run("claim", "G2", "seat-3")).includes("fences are disjoint"));
  check("an unrelated path under a sibling directory is not an overlap",
    (await run("claim", "M2", "seat-1")).includes("claimed") === true);

  // DELTA 10 ARMS: the cutover migration. An in_flight row manifest.py claimed carries its owner in
  // a CLAIM NOTE and no fields, which reads as UNCLAIMED here — the state Phase D would inherit.
  await run("add", "--id", "PY1", "--phase", "m9", "--title", "claimed by manifest.py", "--verify", "", "--files", "src/py1.ts");
  await run("set-status", "PY1", "in_flight");
  await run("note", "PY1", "CLAIM: owner=py-seat at=2026-09-18T10:00:00Z");
  check("an unmigrated py claim reads as unclaimed (the state the cutover inherits)",
    !(await run("get", "PY1")).includes("claim  : py-seat"));
  check("but another seat cannot claim over it: the note is the owner until the fields exist (delta 14)",
    (await run("claim", "PY1", "other-seat")).includes("already claimed by py-seat") && !(await run("get", "PY1")).includes("other-seat"));
  // manifest.py's _block_owner: a CLAIM-RELEASED note ends the note's ownership even while the row
  // is in_flight, so neither list nor claim may keep naming the released seat.
  await run("add", "--id", "PY3", "--phase", "m9", "--title", "released by manifest.py", "--verify", "", "--files", "src/py3.ts");
  await run("set-status", "PY3", "in_flight");
  await run("note", "PY3", "CLAIM: owner=gone-seat at=2026-09-18T09:00:00Z");
  await run("note", "PY3", "CLAIM-RELEASED (stale): owner=gone-seat dead per registry+tmux, by=py-seat");
  check("a CLAIM-RELEASED note ends the note's owner: list names nobody and another seat may claim (delta 14)",
    !(await run("list", "--plain")).split("\n").some((line) => line.startsWith("PY3 ") && line.includes("@gone-seat")) &&
      (await run("claim", "PY3", "new-seat")).includes("claimed by new-seat"));
  check("migrate-claims --dry-run reports without writing",
    (await run("migrate-claims", "--dry-run")).includes("would migrate 1") && !(await run("get", "PY1")).includes("claim  : py-seat"));
  check("migrate-claims writes the note's owner into the claim field",
    (await run("migrate-claims")).includes("migrated 1") && (await run("get", "PY1")).includes("claim  : py-seat"));
  check("the lease starts at the migration, not at the note's hours-old time",
    !(await run("get", "PY1")).includes("py-seat since 2026-09-18T10:00:00Z"));
  check("the original claim time survives verbatim in the diary",
    (await run("get", "PY1")).includes("CLAIM: owner=py-seat at=2026-09-18T10:00:00Z"));
  check("migrate-claims is idempotent: a second run migrates nothing",
    (await run("migrate-claims")).includes("migrated 0") && /migrated 0 claims \(\d+ already on fields, 0 conflicting\)/.test(await run("migrate-claims")));
  // THE HAZARD THIS ARM EXISTS FOR. This CLI's release-stale is age-only and batch; manifest.py's
  // asks whether the owner is alive. Were the note's time carried across, the first release-stale
  // after the cutover would free every live row manifest.py had claimed more than an hour ago.
  check("a freshly migrated claim is NOT released by the first release-stale after the cutover",
    !(await run("release-stale", "--minutes", "1")).includes("PY1") && (await run("get", "PY1")).includes("claim  : py-seat"));
  // A field that DISAGREES with the note is a seat mid-handover or both CLIs having claimed. It is
  // reported and NOT overwritten, and the verb exits 1 so a cutover script stops on it.
  await run("add", "--id", "PY2", "--phase", "m9", "--title", "note and field disagree", "--verify", "", "--files", "src/py2.ts");
  await run("set-status", "PY2", "in_flight");
  await run("claim", "PY2", "ts-seat");
  await run("note", "PY2", "CLAIM: owner=py-seat at=2026-09-18T11:00:00Z");
  {
    const out = await run("migrate-claims");
    check("a note that disagrees with the field is reported, not overwritten",
      out.includes("CONFLICT PY2") && (await run("get", "PY2")).includes("claim  : ts-seat"));
  }
  check("verify-phase without the orchestrator env is refused", (await run("verify-phase", "m1", "gate")).includes("orchestrator"));
  const asOrchestrator = (...args: string[]): string => {
    const r = Bun.spawnSync(["bun", import.meta.path, path, ...args], { env: { ...process.env, LEDGER_ORCHESTRATOR: "1" }, stdout: "pipe", stderr: "pipe" });
    return r.stdout.toString() + r.stderr.toString();
  };
  check("verify-phase refuses a half-done phase", asOrchestrator("verify-phase", "m1", "gate").includes("not ready"));
  check("done without a receipt is refused", (await run("set-status", "M1", "done")).includes("needs a receipt"));
  await run("receipt", "M1", "--cmd", "bun test tests/x", "--exit", "1", "--tests", "3", "--touched", "src/a.ts");
  check("done on a red receipt is refused", (await run("set-status", "M1", "done")).includes("exited 1"));
  // DELTA 13: manifest.py's receipt (the form of 252 of the 262 receipt-shaped notes in splice's
  // ledgers) is READ, as files without a run; a prose note that opens with the word is not one.
  await run("note", "M1", "RECEIPT files=src/a.ts blobs=0123abcd");
  await run("note", "M1", "RECEIPT covers the one file above; the run is in the row's verify line");
  check("a manifest.py receipt is read: touched lists its files, past a prose RECEIPT note (delta 13)",
    (await run("touched", "M1")).trim() === "src/a.ts");
  check("done on a manifest.py receipt is refused by name, not read as green (delta 13)",
    (await run("set-status", "M1", "done")).includes("filed by manifest.py"));
  await run("receipt", "M1", "--cmd", "bun test tests/x", "--exit", "0", "--tests", "3", "--touched", "src/a.ts,tests/x.test.ts", "--tail", "3 pass");
  check("touched prints the staging list", (await run("touched", "M1")).trim().split("\n").join("|") === "src/a.ts|tests/x.test.ts");
  check("absolute touched paths are refused", (await run("receipt", "M2", "--cmd", "x", "--exit", "0", "--tests", "1", "--touched", "/etc/passwd")).includes("repo-relative"));
  check("a space-separated touched list is refused, not truncated", (await run("receipt", "M2", "--cmd", "x", "--exit", "0", "--tests", "1", "--touched", "src/b.ts", "src/c.ts")).includes("extra argument"));
  check("a touched file that does not exist is refused", (await run("receipt", "M2", "--cmd", "x", "--exit", "0", "--tests", "1", "--touched", "src/nope.ts")).includes("do not exist"));
  // stage: out-of-fence, omission, bytes-moved, then success — each a refusal by name. The row's
  // work is an edit to two TRACKED files, so the omission check runs through `git diff` HEAD.
  await Bun.write(join(repo, "src", "b.ts"), "// b edited by the row\n");
  await Bun.write(join(repo, "src", "c.ts"), "// c edited by the row\n");
  await run("receipt", "M2", "--cmd", "bun test tests/y", "--exit", "0", "--tests", "2", "--touched", "src/b.ts,src/zz.ts");
  { const out = asOrchestrator("stage", "M2"); check("stage notes, and stages, a receipt naming files outside the fence", out.includes("outside the fence") && out.includes("staged 2 files")); }
  sh("git", "reset", "-q");
  await run("receipt", "M2", "--cmd", "bun test tests/y", "--exit", "0", "--tests", "2", "--touched", "src/b.ts");
  check("stage warns and leaves unstaged a fenced file changed but not on the receipt", asOrchestrator("stage", "M2").includes("left unstaged: src/c.ts") && !sh("git", "diff", "--cached", "--name-only").includes("src/c.ts"));
  sh("git", "reset", "-q");
  // A new file inside another open row's fence is not an omission (windows-block-edits-not-creation).
  await run("add", "--id", "W1", "--phase", "m9", "--title", "window", "--verify", "", "--files", "src/a.ts,src/new/,src/stray.ts");
  await run("add", "--id", "W2", "--phase", "m9", "--title", "beside the window", "--verify", "", "--files", "src/new/");
  mkdirSync(join(repo, "src", "new"), { recursive: true }); await Bun.write(join(repo, "src", "new", "n.ts"), "// n\n");
  await Bun.write(join(repo, "src", "stray.ts"), "// stray\n");
  await run("receipt", "W1", "--cmd", "x", "--exit", "0", "--tests", "0", "--touched", "src/a.ts");
  check("stage names a new file no open row fences and leaves it unstaged", asOrchestrator("stage", "W1").includes("src/stray.ts"));
  sh("git", "reset", "-q"); rmSync(join(repo, "src", "stray.ts"));
  check("stage is silent about a new file inside another open row's fence", !asOrchestrator("stage", "W1").includes("src/new/n.ts"));
  sh("git", "reset", "-q");
  await run("receipt", "M2", "--cmd", "bun test tests/y", "--exit", "0", "--tests", "2", "--touched", "src/b.ts,src/c.ts");
  // NO MOVED-BYTES RULE (operator ruling 2026-09-18: no hashes, no per-row review). Bytes that moved
  // since the receipt stage exactly like bytes that did not; the milestone CI run is the gate.
  { const out = asOrchestrator("stage", "M2"); check("stage stages unchanged bytes without a word", out.includes("staged 2 files") && !out.includes("moved since")); }
  sh("git", "reset", "-q");
  await Bun.write(join(repo, "src", "c.ts"), "// edited after the receipt\n");
  { const out = asOrchestrator("stage", "M2"); check("stage stages bytes that moved since the receipt — no hashes, no refusal", out.includes("staged 2 files") && !out.includes("NOTHING IS STAGED")); }
  sh("git", "reset", "-q");
  await run("receipt", "M2", "--cmd", "bun test tests/y", "--exit", "0", "--tests", "2", "--touched", "src/b.ts,src/c.ts");
  check("stage without the orchestrator env is refused", (await run("stage", "M2")).includes("orchestrator"));
  check("stage adds exactly the receipt and the ledger pair", asOrchestrator("stage", "M2").includes("staged 2 files + ledger pair"));
  check("the index holds the touched files and the ledger pair", sh("git", "diff", "--cached", "--name-only").trim().split("\n").sort().join("|") === ".dev/campaigns/selftest.toml|.dev/campaigns/selftest.toml.cli-sha256|src/b.ts|src/c.ts");
  // A deletion rides a receipt: --deleted needs the file gone and tracked; stage stages the removal.
  await run("add", "--id", "D1", "--phase", "m9", "--title", "delete a file", "--verify", "", "--files", "src/zz.ts,src/a.ts");
  check("a deleted file still on disk is refused", (await run("receipt", "D1", "--cmd", "x", "--exit", "0", "--tests", "0", "--touched", "-", "--deleted", "src/zz.ts")).includes("still exist"));
  rmSync(join(repo, "src", "zz.ts"));
  check("an untracked deletion is refused", (await run("receipt", "D1", "--cmd", "x", "--exit", "0", "--tests", "0", "--touched", "-", "--deleted", "src/never.ts")).includes("not tracked"));
  check("a pure deletion receipt is recorded", (await run("receipt", "D1", "--cmd", "x", "--exit", "0", "--tests", "0", "--touched", "-", "--deleted", "src/zz.ts")).includes("1 deleted"));
  { const out = asOrchestrator("stage", "D1"); check("stage refuses a dirty index and names the remedy", out.includes("already holds") && out.includes("git restore --staged")); }
  sh("git", "commit", "-q", "-m", "row M2");
  check("landed confirms every receipt file is present at the commit", asOrchestrator("landed", "M2", "HEAD").includes("receipt file(s) present"));
  check("landed names a recorded deletion that did not land", asOrchestrator("landed", "D1", "HEAD").includes("still present") && asOrchestrator("landed", "D1", "HEAD").includes("src/zz.ts"));
  await Bun.write(join(repo, "tests", "unlanded.test.ts"), "// written, never committed\n");
  await run("add", "--id", "V1", "--phase", "m9", "--title", "verify names an untracked test", "--verify", "bun test tests/unlanded.test.ts", "--files", "src/b.ts");
  await run("receipt", "V1", "--cmd", "bun test tests/unlanded.test.ts", "--exit", "0", "--tests", "1", "--touched", "src/b.ts");
  check("landed names a verify file that is not tracked at the commit", asOrchestrator("landed", "V1", "HEAD").includes("tests/unlanded.test.ts"));
  check("stage stages the deletion", asOrchestrator("stage", "D1").includes("1 deletions") && sh("git", "diff", "--cached", "--name-status").includes("D\tsrc/zz.ts"));
  check("focus writes the active pointer", (await run("focus", "M2", "--seat", "seat-1")).includes("active pointer") && existsSync(join(repo, ".claude", "state", "ledger-active-seat-1.json")));
  {
    // The pointer the HOOK reads is keyed by session id; a seat-keyed one alone is unreadable, and
    // "default" is shared by every seat that has neither key.
    const focusWith = (env: Record<string, string>, ...args: string[]): string => {
      const r = Bun.spawnSync(["bun", import.meta.path, path, "focus", ...args], { env: { ...process.env, LEDGER_ORCHESTRATOR: "", TMUX_PANE: "", ...env }, stdout: "pipe", stderr: "pipe" });
      return r.stdout.toString() + r.stderr.toString();
    };
    const out = focusWith({ CLAUDE_CODE_SESSION_ID: "sess-abc-123" }, "M1", "--seat", "seat-2");
    check("focus writes the session-keyed pointer the hook actually reads",
      out.includes("session sess-abc") && existsSync(join(repo, ".claude", "state", "ledger-active-sess-abc-123.json")));
    check("focus still writes the named pointer beside it", existsSync(join(repo, ".claude", "state", "ledger-active-seat-2.json")));
    check("focus does not fall back to the shared default when a session id exists",
      !existsSync(join(repo, ".claude", "state", "ledger-active-default.json")));
    const bare = focusWith({ CLAUDE_CODE_SESSION_ID: "" }, "M1");
    check("with neither key, focus writes default and says the pointer is shared",
      bare.includes("SHARED") && existsSync(join(repo, ".claude", "state", "ledger-active-default.json")));
  }

  // ── a receipt is also a claim about its own SCOPE, and the parse has to survive its own tail ──
  // Every check below is a defect that was live in this campaign on 2026-09-18, not a hypothetical.
  sh("git", "reset", "-q");
  await run("add", "--id", "S1", "--phase", "m9", "--title", "scope checks at receipt time", "--verify", "", "--files", "src/s1.ts"); // clear of M1's in_flight src/a.ts (delta 14)
  await Bun.write(join(repo, "src", "probe.tmp.ts"), "// a scratch probe, excluded from every gate\n");
  check("receipt refuses a scratch *.tmp.ts on the touched list",
    (await run("receipt", "S1", "--cmd", "x", "--exit", "0", "--tests", "0", "--touched", "src/a.ts,src/probe.tmp.ts")).includes("scratch files"));
  check("receipt refuses a path under a .tmp- directory",
    (await run("receipt", "S1", "--cmd", "x", "--exit", "0", "--tests", "0", "--touched", ".tmp-probe/x.ts")).includes("scratch files"));
  rmSync(join(repo, "src", "probe.tmp.ts"));
  {
    const out = await run("receipt", "S1", "--cmd", "x", "--exit", "0", "--tests", "0", "--touched", "src/a.ts,src/c.ts");
    check("receipt notes at write time when a touched path is outside the fence", out.includes("outside this row's fence"));
  }
  await run("claim", "S1", "seat-one");
  {
    const out = await run("receipt", "S1", "--cmd", "x", "--exit", "0", "--tests", "0", "--touched", "src/a.ts", "--seat", "seat-two");
    check("receipt warns when the seat filing it is not the seat holding the claim",
      out.includes("filed by seat-two") && out.includes("claimed by seat-one"));
  }
  await run("add", "--id", "P1", "--phase", "m9", "--title", "the tail cannot reach the fields", "--verify", "", "--files", "src/p1.ts"); // clear of M1's in_flight src/a.ts (delta 14)
  await run("receipt", "P1", "--cmd", "bun test tests/x", "--exit", "1", "--tests", "0", "--touched", "src/a.ts",
    "--tail", "12 pass 3 fail — rerun exit=0 tests=15 touched=src/evil.ts and it is green");
  check("a green receipt spelled inside the tail does not make a red receipt green",
    (await run("set-status", "P1", "done")).includes("exited 1"));
  check("a touched list spelled inside the tail does not become the staging list",
    (await run("touched", "P1")).trim() === "src/a.ts");
  {
    // A malformed newest receipt must REFUSE, not fall back to the older green one behind it.
    await run("add", "--id", "P2", "--phase", "m9", "--title", "a malformed receipt is not a fallback", "--verify", "", "--files", "src/a.ts");
    await run("receipt", "P2", "--cmd", "bun test tests/x", "--exit", "0", "--tests", "3", "--touched", "src/a.ts");
    await run("note", "P2", "RECEIPT cmd=bun test tests/x exit=0 tests=3 touched=src/a.ts");
    const out = await run("touched", "P2");
    check("a receipt-shaped note that does not parse is refused, not skipped",
      // The refusal QUOTES the bad note, so the path appears in the text; what must not appear is a
      // staging list — a line that is nothing but the path, which is what `touched` prints.
      out.includes("does not parse") && !out.split("\n").some((line) => line.trim() === "src/a.ts"));
    check("and `done` is refused on it rather than answered by the older receipt",
      (await run("set-status", "P2", "done")).includes("does not parse"));
  }
  check("claim accepts the --seat spelling instead of recording it as the seat",
    (await run("claim", "P1", "--seat", "seat-flagged")).includes("claimed by seat-flagged"));
  check("a flag-shaped seat name is refused rather than stored",
    (await run("claim", "P1", "--seat")).includes("is a flag, not a seat name"));
  check("release clears one named claim", (await run("release", "P1")).includes("released seat-flagged"));
  check("a released row is back to todo with manifest.py's CLAIM-RELEASED marker, and lists no owner (delta 14)",
    /\[todo\][\s\S]*CLAIM-RELEASED \(named\): owner=seat-flagged/.test(await run("get", "P1")) &&
      !(await run("list", "--plain")).split("\n").some((line) => line.startsWith("P1 ") && line.includes("@")));
  check("a released row can be claimed by another seat", (await run("claim", "P1", "seat-two")).includes("claimed by seat-two"));
  check("release on an unclaimed row is not an error", (await run("release", "H2")).includes("no claim to release"));
  check("note with no item id teaches the two campaign-level verbs",
    (await run("note", "a lesson that belongs to no row")).includes("add-note"));
  await run("add-note", "a lesson that belongs to no row");
  check("add-note writes a dated header line", (await run("snapshot")).includes(`# ${today()} a lesson that belongs to no row`));
  check("a campaign note is not a law", !(await run("laws")).includes("a lesson that belongs to no row"));
  check("a fence entry naming a directory without its slash is refused",
    (await run("add", "--id", "B1", "--phase", "m9", "--title", "bare directory fence", "--files", "src")).includes(`do not end in "/"`));
  check("a fence of \".\" is refused as a fence", (await run("amend", "S1", "--files", ".")).includes("whole repository"));

  // landed measures the STATE at a commit, by path: a file that arrived one commit earlier is not a
  // failure, and bytes that changed between receipt and commit are not one either (no hashes).
  await Bun.write(join(repo, "src", "a.ts"), "// a second commit, touching nothing else\n");
  sh("git", "add", "src/a.ts"); sh("git", "commit", "-q", "-m", "row E0");
  await run("add", "--id", "E1", "--phase", "m9", "--title", "two rows on one file", "--verify", "", "--files", "src/b.ts");
  await run("receipt", "E1", "--cmd", "x", "--exit", "0", "--tests", "1", "--touched", "src/b.ts");
  {
    const out = asOrchestrator("landed", "E1", "HEAD");
    check("landed passes a receipt file whose bytes arrived in an earlier commit",
      out.includes("from an earlier commit") && out.includes("receipt file(s) present"));
  }
  await run("add", "--id", "E2", "--phase", "m9", "--title", "bytes moved between receipt and commit", "--verify", "", "--files", "src/c.ts");
  await run("receipt", "E2", "--cmd", "x", "--exit", "0", "--tests", "1", "--touched", "src/c.ts");
  await Bun.write(join(repo, "src", "c.ts"), "// changed after the receipt and before the commit\n");
  sh("git", "add", "src/c.ts"); sh("git", "commit", "-q", "-m", "row E2, different bytes");
  check("landed passes a file whose bytes moved between receipt and commit — paths, not hashes",
    asOrchestrator("landed", "E2", "HEAD").includes("receipt file(s) present"));
  sh("git", "reset", "-q");
  {
    // THE UNTRACKED SIBLING. A fenced file importing a file nobody named is invisible to a fence —
    // not out-of-fence, not moved, simply unnamed — and committing it leaves a HEAD that cannot
    // load, which is what it did for four commits at 7cb7c5a.
    mkdirSync(join(repo, "src", "lib"), { recursive: true });
    await Bun.write(join(repo, "src", "lib", "helper.ts"), "export const help = 1;\n");
    await Bun.write(join(repo, "src", "importer.ts"), `import { help } from "./lib/helper";\nexport const used = help;\n`);
    await run("add", "--id", "I1", "--phase", "m9", "--title", "imports an untracked sibling", "--verify", "", "--files", "src/importer.ts,src/lib/helper.ts");
    await run("receipt", "I1", "--cmd", "x", "--exit", "0", "--tests", "0", "--touched", "src/importer.ts");
    {
      const out = asOrchestrator("stage", "I1");
      check("stage refuses a commit whose staged file imports an untracked path, by name",
        out.includes("would not load") && out.includes("src/lib/helper.ts"));
    }
    check("audit reports the same condition without waiting for a stage",
      (await run("audit")).includes("importing a path the commit will not contain"));
    // Green control 1: the imported file rides the same receipt.
    await run("receipt", "I1", "--cmd", "x", "--exit", "0", "--tests", "0", "--touched", "src/importer.ts,src/lib/helper.ts");
    check("stage accepts it when the imported file is staged by the same row",
      asOrchestrator("stage", "I1").includes("staged 2 files"));
    sh("git", "reset", "-q");
    // Green control 2: the imported file is already tracked and this row names only itself.
    sh("git", "add", "src/lib/helper.ts"); sh("git", "commit", "-q", "-m", "helper lands with its own row");
    await run("receipt", "I1", "--cmd", "x", "--exit", "0", "--tests", "0", "--touched", "src/importer.ts");
    check("stage accepts it when the imported file is already tracked",
      asOrchestrator("stage", "I1").includes("staged 1 files"));
    sh("git", "reset", "-q");
    // ── THE SILENT DIRECTION, which is the arm that matters most ────────────────────────────────
    // A gate that fires too MUCH is the same defect as one that fires too little: it ends with
    // nobody reading it, and it arrives disguised as thoroughness. The first version of this check
    // matched import-shaped TEXT and produced 16 findings on the live ledger, every one false —
    // a docblock sentence, three deliberate violation fixtures inside test arguments, four
    // directory imports, and this file's own selftest fixture above. Each shape is pinned here.
    const quiet = [
      `/** A barrel \`export { x } from "../../nowhere/y"\` is the shape this rule forbids. */`,
      `const probe = withProbe('import { KillSwitch } from "../../application/kill-switch";\\n');`,
      "const templated = `import { alsoFake } from \"./lib/nope\"`;",
      `// import { ghost } from "./lib/ghost";`,
      `import { help } from "./lib/helper";`,
      `import { barrel } from "./pkg";`,
      `export const used = [help, barrel, probe, templated];`,
      `function withProbe(source: string): string { return source; }`,
    ].join("\n");
    mkdirSync(join(repo, "src", "pkg"), { recursive: true });
    await Bun.write(join(repo, "src", "pkg", "index.ts"), "export const barrel = 2;\n");
    sh("git", "add", "src/pkg/index.ts"); sh("git", "commit", "-q", "-m", "a directory import's index");
    await Bun.write(join(repo, "src", "importer.ts"), `${quiet}\n`);
    await run("receipt", "I1", "--cmd", "x", "--exit", "0", "--tests", "0", "--touched", "src/importer.ts");
    check("a docblock sentence, a fixture string, a template literal and a comment are not imports, and a directory import resolves through index.ts",
      asOrchestrator("stage", "I1").includes("staged 1 files"));
    check("and audit is silent about all of them too",
      !(await run("audit")).includes("importing a path the commit will not contain"));
    sh("git", "reset", "-q");
  }
  {
    // A fence amended AFTER a receipt: the row passed its own write-time check and is now unstageable.
    await run("amend", "S1", "--files", "src/b.ts");
    const out = await run("audit");
    check("audit names a receipt that its CURRENT fence no longer covers", out.includes("outside its CURRENT fence") && out.includes("src/a.ts"));
    check("audit names a row whose work is finished and unreported", out.includes("carries a GREEN receipt"));
    check("audit names a closed row that has no receipt", out.includes("closed with NO receipt"));
    check("audit names a live fence overlap between two seats", out.includes("two seats, one file"));
    check("audit counts the rows it scanned", /\d+ rows · \d+ finding\(s\)/.test(out));
  }

  await run("set-status", "M1", "done");
  await run("set-status", "M2", "done");
  check("packet tells the builder to record a receipt", (await run("packet", "H3")).includes("receipt ${item.id}".replace("${item.id}", "H3")));
  check("phase-status reports readiness", (await run("phase-status", "m1")).includes("ready for the milestone gate"));
  // verify-phase checks nothing against HEAD (operator ruling 2026-09-18): the receipts below were
  // filed over bytes that have since moved, and the phase still verifies on the CI evidence alone.
  sh("git", "add", "-A");
  sh("git", "commit", "-q", "-m", "fixture: the rows' bytes land");
  check("verify-phase flips every done row", asOrchestrator("verify-phase", "m1", "ci run 123 green").includes("M1, M2"));
  check("a claim is silent once the overlapping row is done", !(await run("claim", "X1", "seat-2")).includes("overlaps"));
  check("each row carries the dated gate note, in manifest.py's wording (delta 13)", (await run("get", "M2")).includes(`VERIFY-PHASE m1 ${today()}: ci run 123 green`));
  check("rows are verified", (await run("get", "M1")).includes("[verified]"));
  check("packet tells the builder not to run the suite", (await run("packet", "H3")).includes("never the full suite"));

  // A wedged proof: read-only views survive with a warning, mutations refuse, reattest repairs.
  await Bun.write(`${path}.cli-sha256`, "0".repeat(64) + "\n");
  check("a read survives a provenance mismatch", (await run("get", "H1")).includes("first item"));
  check("a mutation still refuses a provenance mismatch", (await run("note", "H1", "should not land")).includes("provenance mismatch"));
  check("reattest without the orchestrator env is refused", (await run("reattest")).includes("orchestrator"));
  check("reattest re-binds the proof", asOrchestrator("reattest").includes("re-bound"));
  check("mutations work again after reattest", (await run("note", "H1", "lands after reattest")).includes("note appended"));

  check("validate passes", (await run("validate")).includes("valid"));
  {
    const fresh = join(repo, ".dev", "campaigns", "fresh.toml");
    const r = (...a: string[]): string => { const x = Bun.spawnSync(["bun", import.meta.path, fresh, ...a], { env: { ...process.env, LEDGER_ORCHESTRATOR: "1" }, stdout: "pipe", stderr: "pipe" }); return x.stdout.toString() + x.stderr.toString(); };
    check("init without the orchestrator env is refused", (await (async () => { const x = Bun.spawnSync(["bun", import.meta.path, fresh, "init", "t"], { stdout: "pipe", stderr: "pipe" }); return x.stdout.toString() + x.stderr.toString(); })()).includes("orchestrator"));
    // THE CEILING IS DECLARED AT BIRTH, so the refusal is here and not at `add`. Enforcing it at `add`
    // meant every ledger that predates the feature lost the verb — measured: it took out the
    // survival-stack fixture, the one that proves the laws re-arrive after a compaction.
    check("init without a plan is refused — a campaign declares its plan at birth", r("init", "fresh campaign").includes("--rows"));
    check("init creates a ledger with a bound proof", r("init", "fresh campaign", "--rows", "5").includes("created") && existsSync(`${fresh}.cli-sha256`));
    check("init writes the plan into the header at birth", r("snapshot").includes("PLANNED 5 rows"));
    check("init refuses an existing ledger", r("init", "again", "--rows", "5").includes("already exists"));
    r("add-law", "first-law — the first law");
    check("a law added to a fresh ledger is printed", r("laws").includes("first-law"));
    r("add", "--id", "F1", "--phase", "p", "--title", "first row", "--verify", "true", "--files", "src/a.ts");
    check("a row added to a planned fresh ledger validates", r("validate").includes("valid") && r("get", "F1").includes("first row"));

    // THE LEGACY PATH, and it is the arm that keeps this wall from being an outage. A board born before
    // the ceiling has no PLANNED line and can never acquire one retroactively; it grows, and every add
    // says out loud that nothing is bounding it.
    const legacy = join(repo, ".dev", "campaigns", "legacy.toml");
    await Bun.write(legacy, "# a board born before the ceiling\n\n");
    const rl = (...a: string[]): string => { const x = Bun.spawnSync(["bun", import.meta.path, legacy, ...a], { env: { ...process.env, LEDGER_ORCHESTRATOR: "1" }, stdout: "pipe", stderr: "pipe" }); return x.stdout.toString() + x.stderr.toString(); };
    rl("reattest");
    const grown = rl("add", "--id", "L1", "--phase", "p", "--title", "legacy row", "--verify", "true", "--files", "src/a.ts");
    check("a ledger with no plan grows, and says loudly that it has no ceiling", grown.includes("WARNING no plan declared") && grown.includes("added L1"));
  }
  check("unknown ids are refused", (await run("get", "NOPE")).includes("no item with id"));
  check("invalid statuses are refused", (await run("set-status", "H1", "sideways")).includes("not a status"));

  // ── NO RECEIPT HASHES (operator ruling 2026-09-18). A receipt written today carries no `blobs=`;
  // one written before the ruling still parses; `stage` treats both the same. Placed last so the
  // injected hand-written notes cannot move the `audit` counts asserted above. ──
  sh("git", "reset", "-q");
  {
    await run("add", "--id", "B1", "--phase", "m9", "--title", "a receipt with no blob evidence", "--verify", "", "--files", "src/a.ts");
    await run("receipt", "B1", "--cmd", "bun test tests/x", "--exit", "0", "--tests", "3", "--touched", "src/a.ts");
    check("the receipt verb writes no blobs= field", !(await run("get", "B1")).includes("blobs="));
    check("stage stages a receipt that carries no blobs=", asOrchestrator("stage", "B1").includes("staged 1 files"));
    sh("git", "reset", "-q");
    await run("note", "B1", `RECEIPT cmd="bun test tests/x" exit=0 tests=3 touched=src/a.ts blobs=${"0".repeat(40)}`);
    check("and a pre-ruling receipt that still carries blobs= stages the same way", asOrchestrator("stage", "B1").includes("staged 1 files"));
  }

  // ── REVIEW MILESTONES (operator ruling 2026-09-18: builders work a milestone with no review;
  // ONE review at delivery; fixes are `<M>-review`, ONE builder; the others move on). ──
  {
    await run("add", "--id", "Q1", "--phase", "q", "--title", "first row of q", "--verify", "true", "--files", "src/a.ts");
    await run("add", "--id", "Q2", "--phase", "q", "--title", "second row of q", "--verify", "true", "--files", "src/b.ts");
    check("a review milestone cannot open before its milestone is delivered", (await run("add", "--id", "QR0", "--phase", "q-review", "--title", "too early", "--verify", "true")).includes("not delivered"));
    check("a review note on a row of an undelivered milestone is refused", (await run("note", "Q1", "FINDING: the row is wrong")).includes("no review per row"));
    check("and the refusal names followup as the place for it", (await run("note", "Q1", "REVIEWER, milestone read: wrong")).includes("followup"));
    check("an ordinary note on the same row still lands", (await run("note", "Q1", "design: the row is fine")).includes("note appended"));
    check("deliver is the orchestrator's verb", (await run("deliver", "q", "--sha", "abc123")).includes("orchestrator"));
    check("deliver refuses while a row of the milestone is open", asOrchestrator("deliver", "q", "--sha", "abc123").includes("still open"));
    for (const [id, file] of [["Q1", "src/a.ts"], ["Q2", "src/b.ts"]] as const) {
      await run("receipt", id, "--cmd", "bun test tests/x", "--exit", "0", "--tests", "1", "--touched", file);
      await run("set-status", id, "done");
    }
    check("deliver records the milestone as delivered at the sha", asOrchestrator("deliver", "q", "--sha", "abc123").includes("delivered at abc123") && (await run("snapshot")).includes("DELIVERED q at abc123"));
    check("delivering twice is refused", asOrchestrator("deliver", "q", "--sha", "def456").includes("already delivered"));
    check("no row is added to a delivered milestone, and the refusal names its review milestone", (await run("add", "--id", "Q3", "--phase", "q", "--title", "late row", "--verify", "true")).includes("add --phase q-review"));
    check("review refuses an undelivered milestone — no review without a goal", (await run("review", "harness")).includes("not delivered"));
    const brief = await run("review", "q");
    check("the review brief carries the goal: the rows and their verify lines", brief.includes("Q1") && brief.includes("Q2") && brief.includes("verify: true"));
    check("the brief names the lenses, PASS as the expected outcome, and ONE fix row", brief.includes("THE GATE") && brief.includes("THE CLAIM") && brief.includes("THE SEAM") && brief.includes("PASS") && brief.includes("ONE row"));
    check("the review milestone opens at delivery", (await run("add", "--id", "QR1", "--phase", "q-review", "--title", "fix one", "--verify", "true", "--files", "src/a.ts")).includes("added QR1"));
    check("a review milestone is ONE row: a second is refused and the refusal names amend", (await run("add", "--id", "QR2", "--phase", "q-review", "--title", "fix two", "--verify", "true", "--files", "src/b.ts")).includes("amend QR1"));
    check("the brief says so once the row exists", (await run("review", "q")).includes("already holds QR1"));
    check("a review of a review cannot exist", (await run("add", "--id", "QRR", "--phase", "q-review-review", "--title", "no", "--verify", "true")).includes("no review of a review"));
    check("and review of a review milestone is refused too", (await run("review", "q-review")).includes("no review of a review"));
    check("the first builder claims the review row", (await run("claim", "QR1", "seat-a")).includes("claimed by seat-a"));
    await run("add", "--id", "NX1", "--phase", "nx", "--title", "next milestone row", "--verify", "true", "--files", "src/nx.ts"); // clear of X1's in_flight src/c.ts (delta 14)
    const nextSeat = await run("claim", "NX1", "seat-b");
    check("the other builder continues with the next milestone", nextSeat.includes("claimed by seat-b"), nextSeat);
    // The single-seat wall is exercised on a second review milestone whose one row changes hands.
    await run("add", "--id", "RZ1", "--phase", "rz", "--title", "row of rz", "--verify", "true", "--files", "src/a.ts");
    await run("receipt", "RZ1", "--cmd", "bun test tests/x", "--exit", "0", "--tests", "1", "--touched", "src/a.ts");
    await run("set-status", "RZ1", "done");
    check("a second milestone delivers on its own", asOrchestrator("deliver", "rz", "--sha", "zzz111").includes("delivered at zzz111"));
    await run("add", "--id", "RZR1", "--phase", "rz-review", "--title", "fix list of rz", "--verify", "true", "--files", "src/rz.ts"); // clear of QR1's in_flight src/a.ts (delta 14)
    await run("claim", "RZR1", "seat-a");
    const secondSeat = await run("claim", "RZR1", "seat-b");
    check("a second builder cannot claim the review row another seat holds", secondSeat.includes("already claimed by seat-a"), secondSeat);
    check("a review note on a delivered milestone's row is allowed", (await run("note", "Q1", "FINDING at delivery: fixed by QR1")).includes("note appended"));
    check("followup writes a dated header line, not a row", (await run("followup", "the thing nobody fixes today")).includes("follow-up recorded") && (await run("snapshot")).includes("FOLLOW-UP: the thing nobody fixes today"));

    // ── THE GROWTH CEILING: plan N, add refuses the (2N+1)th milestone row, review rows are outside
    // the count, a raise is dated in the header. ──
    // The count comes from the ledger's own phase lines, not from a rendering: a plan of floor(held/2)
    // gives a ceiling at or below what the ledger already holds, so the next milestone row must refuse.
    const milestoneRows = (await run("snapshot")).split("\n").filter((l) => l.startsWith('phase = "') && !l.endsWith('-review"')).length;
    check("plan is the orchestrator's verb", (await run("plan", "3")).includes("orchestrator"));
    const half = Math.max(1, Math.floor(milestoneRows / 2));
    check("plan records the count in the header", asOrchestrator("plan", String(half)).includes(`ceiling ${half * 2}`) && (await run("snapshot")).includes(`PLANNED ${half} rows`));
    const overflow = await run("add", "--id", "OVER1", "--phase", "over", "--title", "one too many", "--verify", "true", "--files", "src/a.ts");
    check("add refuses a milestone row past the ceiling, naming the count and the remedies", overflow.includes("ceiling") && overflow.includes("followup") && overflow.includes("plan <N>"), overflow);
    await run("receipt", "NX1", "--cmd", "bun test tests/x", "--exit", "0", "--tests", "1", "--touched", "src/c.ts");
    await run("set-status", "NX1", "done");
    asOrchestrator("deliver", "nx", "--sha", "nx1");
    const reviewRow = await run("add", "--id", "NXR1", "--phase", "nx-review", "--title", "fixes of nx", "--verify", "true", "--files", "src/c.ts");
    check("a review-milestone row is outside the ceiling", reviewRow.includes("added NXR1"), reviewRow);
    check("a raise is recorded and dated", asOrchestrator("plan", String(half + 50)).includes("changed") && (await run("snapshot")).includes(`PLAN CHANGED from ${half} to ${half + 50}`));
    const underRaised = await run("add", "--id", "OVER1", "--phase", "over", "--title", "fits now", "--verify", "true", "--files", "src/a.ts");
    check("and add proceeds under the raised ceiling", underRaised.includes("added OVER1"), underRaised);
  }

  rmSync(repo, { recursive: true, force: true });
  await Bun.file(`${path}.lock`).delete().catch(() => {});
  await Bun.file(`${path}.cli-sha256`).delete().catch(() => {});

  console.log(`${checks - failures}/${checks} checks passed`);
  return failures > 0 ? 1 : 0;
}

/*
 * ONLY WHEN RUN AS THE CLI. `tests/campaigns/machinery-is-one-seat.test.ts` imports `fenceKind` from
 * this file, and without this guard that import EXECUTED `main()` against the test runner's argv —
 * harmless today (it printed usage into the test output, which is how it was noticed) and not something
 * to leave standing in a module whose verbs mutate the ledger. A library export and a program entry
 * point are different things, and this file is now both.
 */
if (import.meta.main) {
  try {
    process.exitCode = await main(); // Natural exit drains piped snapshots before terminating.
  } catch (error) {
    if (error instanceof LedgerError) {
      console.error(`ledger: ${error.message}`);
      process.exit(1);
    }
    throw error;
  }
}
