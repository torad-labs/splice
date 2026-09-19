#!/usr/bin/env bun
/**
 * Attest that no campaign ledger has silently lost memory (DR-181).
 *
 * WHY THIS EXISTS.
 *
 * On 2026-09-01 `dev/campaigns/drift-repair.toml` went from 164 rows to 15 and lost 2604 lines. It
 * was committed and pushed, and the FULL gate passed 13 of 13 legs on that tip. Nothing was broken
 * in the sense any leg could observe: the tree compiled, every test ran, every wall was green. No
 * leg read the file at all, so a green gate said nothing whatsoever about campaign memory — and the
 * campaign ledger is the one artifact in this repo whose whole purpose is to survive the session
 * that wrote it.
 *
 * That is a section-24 denominator failure inside our own tooling. Thirteen checks, each with a
 * denominator drawn from the source tree, and the destroyed artifact was in none of them. A check
 * cannot fail for a thing it does not enumerate.
 *
 * WHAT THIS MEASURES.
 *
 * Two instruments per ledger, both monotonic under every verb a campaign actually uses:
 *
 *     rows   `[[items]]` blocks — a work unit. Added by `add`, removed only by `remove`.
 *     lines  total file lines — the NOTES. Rows are the skeleton; the dated notes under them are
 *            the reasoning, the verdicts, and the resume pointers, and they were the overwhelming
 *            majority of the 2604 lines lost. A truncation that preserved row count while deleting
 *            every note would still destroy the campaign, so row count alone is not enough.
 *
 * THE DENOMINATOR COMES FROM THE DIRECTORY, NOT FROM THE FLOOR FILE. Ledgers are enumerated by
 * RECURSIVELY globbing dev/campaigns/**\/*.toml. A ledger present on disk but absent from the floor
 * file FAILS by name — absence is not a disposition. A ledger named in the floor file but gone from
 * disk fails too: a whole campaign vanishing is the loudest truncation there is, and a check that
 * only iterated its own allowlist would report success over an empty directory.
 *
 * DR-189 MADE THAT GLOB RECURSIVE, and the reason is the whole point of the paragraph above. The
 * first version globbed one level, which quietly meant "campaign memory" was defined as "files that
 * happen to sit at the top of the directory". Three memory-bearing registries live one level down —
 * proxy-hardening's wall_registry.toml (88 rows), law_registry.toml (19 rows) and the oracle's
 * expectations.toml — and each carries an explicit never-delete law in its own header. Truncating
 * each and running every leg that reads it showed wall_registry and expectations are caught by the
 * campaign wall gate, which grades them against an EXTERNAL denominator; law_registry is not, because
 * its wall (inf_02_every_law_walled.ts) iterates the very rows it checks and so cannot fail for a row
 * that was deleted. 19 laws to 1 with every leg green — the same tautology this file was written
 * about, one directory down. Keys are paths relative to dev/campaigns/, so two registries sharing a
 * basename cannot collide into one floor entry.
 *
 * THE FLOOR LIVES OUTSIDE dev/campaigns/. If the high-water marks were stored in the ledgers, the
 * same accident would take both and the check would agree with the wreckage. Two hand-authored lists
 * that check each other are not a check against reality.
 *
 * RAISING IS FREE, LOWERING IS DELIBERATE. `--check` is what the gate runs. `--record` re-records the
 * floors upward, which is the gate's own remedy for the ordinary case of "the campaign grew".
 * Lowering a floor demands `--allow-shrink`, so a shrink can never be absorbed as routine
 * maintenance: it becomes a typed act and a reviewable diff line sitting next to the deletion that
 * caused it. That diff is precisely the review signal that was missing.
 *
 * THERE IS NO BARE MODE, AND THAT IS DELIBERATE. The bare invocation used to re-record the floors —
 * it WROTE the file it guards on any invocation it did not recognise. A checker that silently edits
 * its subject when mis-invoked is worse than a false green: the diff it leaves behind is
 * indistinguishable from a deliberate re-record, so the accident is committed as maintenance. The
 * mode is now required, and misuse exits non-zero without opening the floor file for writing.
 *
 * Usage:
 *     bun checks/campaign-ledger-floor.ts --record                    # re-record (raise only)
 *     bun checks/campaign-ledger-floor.ts --check                     # verify; the gate leg
 *     bun checks/campaign-ledger-floor.ts --record --allow-shrink     # re-record, permitting a decrease
 */
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const LEDGER_DIR = resolve(ROOT, "dev", "campaigns");
const FLOOR = resolve(ROOT, "checks", "config", "campaign-ledger-floor.json");
const SELF = "bun checks/campaign-ledger-floor.ts";

// The same shape manifest.py's own HDR matches, so this counts what the CLI calls an item.
const HDR = /^\[\[items?\]\]\s*$/;

const USAGE = `usage: ${SELF} --check | --record [--allow-shrink]`;

type Floor = Record<string, { rows: number; lines: number }>;

/** Python's `str.splitlines()` count, which is NOT `split("\n").length`: a trailing newline does not
 *  create an empty final line there, so the naive port would report one line too many for every
 *  ledger that ends with a newline — which is all of them — and every floor would move. */
function lineCount(text: string): number {
  const newlines = text.match(/\n/g)?.length ?? 0;
  return text.length > 0 && !text.endsWith("\n") ? newlines + 1 : newlines;
}

function measure(path: string): { rows: number; lines: number } {
  const text = readFileSync(path, "utf8");
  return { rows: text.split("\n").filter((l) => HDR.test(l)).length, lines: lineCount(text) };
}

/** Every ledger under the directory — the denominator, read from the source.
 *
 *  DR-189: recursive, and keyed by the path relative to LEDGER_DIR rather than by basename. A
 *  top-level ledger's key is unchanged by that (its relative path IS its name), so the existing
 *  floor entries carry over; a nested one gets its full relative path and cannot collide with a
 *  sibling campaign's file of the same name. */
function survey(): Floor {
  const out: Floor = {};
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith(".toml")) out[relative(LEDGER_DIR, full)] = measure(full);
    }
  };
  walk(LEDGER_DIR);
  return out;
}

function loadFloor(): Floor {
  return existsSync(FLOOR) ? (JSON.parse(readFileSync(FLOOR, "utf8")) as Floor) : {};
}

function violations(current: Floor, floor: Floor): string[] {
  const found: string[] = [];
  const names = [...new Set([...Object.keys(current), ...Object.keys(floor)])].sort();
  for (const name of names) {
    if (!(name in floor)) {
      found.push(
        `${name}: on disk with no recorded floor — every ledger needs a disposition. ` +
          `Run \`${SELF} --record\` to record it.`,
      );
      continue;
    }
    if (!(name in current)) {
      found.push(`${name}: RECORDED BUT GONE — a whole campaign ledger has disappeared.`);
      continue;
    }
    for (const unit of ["rows", "lines"] as const) {
      const have = current[name][unit];
      const want = floor[name][unit] ?? 0;
      if (have < want) {
        found.push(
          `${name}: ${unit} fell ${want - have} below the recorded floor ` +
            `(${have} < ${want}) — campaign memory was lost, not added to.`,
        );
      }
    }
  }
  return found;
}

function main(argv: string[]): number {
  const check = argv.includes("--check");
  const record = argv.includes("--record");
  const allowShrink = argv.includes("--allow-shrink");
  // ONE MODE, and `--allow-shrink` is a modifier of `--record` rather than a mode of its own: alone
  // it used to mean "record, permitting a decrease", which is the most dangerous invocation this
  // file has and should never be reachable by leaving a flag out of a command line.
  const modes = (check ? 1 : 0) + (record ? 1 : 0);
  if (modes !== 1 || (allowShrink && !record)) {
    process.stderr.write(`${USAGE}\n`);
    process.stderr.write(
      "  exactly one of --check or --record is required, and --allow-shrink only modifies --record.\n",
    );
    return 2;
  }

  const current = survey();
  const floor = loadFloor();

  if (Object.keys(current).length === 0) {
    process.stderr.write(`campaign-ledger-floor: no ledgers found in ${LEDGER_DIR}\n`);
    return 1;
  }

  if (check) {
    const found = violations(current, floor);
    if (found.length > 0) {
      process.stderr.write("campaign-ledger-floor: FAIL\n");
      for (const v of found) process.stderr.write(`  ${v}\n`);
      process.stderr.write(
        "\nA ledger is the memory of a campaign. If this shrink is deliberate (a `remove`,\n" +
          "a retired campaign), re-record it explicitly:\n" +
          `  ${SELF} --record --allow-shrink\n`,
      );
      return 1;
    }
    process.stdout.write(
      `campaign-ledger-floor: OK (${Object.keys(current).length} ledgers at or above their floors)\n`,
    );
    return 0;
  }

  let source = current;
  if (!allowShrink) {
    const shrinks = violations(current, floor).filter(
      (v) => v.includes("below the recorded floor") || v.includes("GONE"),
    );
    if (shrinks.length > 0) {
      process.stderr.write("campaign-ledger-floor: refusing to lower a floor without --allow-shrink\n");
      for (const v of shrinks) process.stderr.write(`  ${v}\n`);
      return 1;
    }
    source = {};
    for (const n of Object.keys(current)) {
      source[n] = {
        rows: Math.max(current[n].rows, floor[n]?.rows ?? 0),
        lines: Math.max(current[n].lines, floor[n]?.lines ?? 0),
      };
    }
  }

  mkdirSync(dirname(FLOOR), { recursive: true });
  // sortKeys, two-space indent, trailing newline: the committed floor's exact bytes, so a re-record
  // that changed nothing produces no diff.
  const sorted: Floor = {};
  for (const k of Object.keys(source).sort()) {
    // INNER KEYS SORTED TOO: Python wrote this file with sort_keys=True, which sorts at every level,
    // so an insertion-ordered {rows, lines} would reorder every entry and turn a no-op re-record into
    // a whole-file diff.
    sorted[k] = { lines: source[k].lines, rows: source[k].rows };
  }
  writeFileSync(FLOOR, JSON.stringify(sorted, null, 2) + "\n", "utf8");
  process.stdout.write(
    `campaign-ledger-floor: recorded ${Object.keys(source).length} ledgers -> ${relative(ROOT, FLOOR)}\n`,
  );
  return 0;
}

process.exit(main(process.argv.slice(2)));
