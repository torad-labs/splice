// NEW: V4-143 — the campaign-ledger CLI's entry, under the name every seat, law and hook already uses.
//
// The CLI itself is the canonical bun ledger suite, VENDORED beside this file (ledger.ts,
// ledger-core.ts, ledger-earn.ts, earn-core.ts, review.ts; lineage and every delta in VENDORED.md).
// This file only runs it: keeping the vendored modules byte-for-byte their upstream layout is what
// makes the next re-vendor a diff rather than a hand-merge.
//
// Usage is the canonical CLI's: `bun .dev/campaigns/manifest.ts <ledger.toml> <command> [args]`.
//
// WHAT THIS FILE DECIDES BEFORE THE CLI RUNS: whether a ledger was named at all (V4-143 D2). The
// first argument is a ledger path when it ends in `.toml`, exactly manifest.py's rule. Without one:
//
//   laws                every campaign's laws — each .dev/campaigns/*.toml's own `laws`, in code-point
//                       order of the file names, identical lines kept once, first occurrence wins.
//                       manifest.py's aggregate byte for byte (SessionStart injected it into every
//                       seat until the 2026-09-22 hook audit; a row's `packet` carries its laws).
//   help / -h / --help  the usage text at exit 0: the caller asked for usage, and got it.
//   anything else       REFUSED, exit 1, with the ledgers that exist. A ledger with no command is
//                       refused the same way.
//
// WHY THE REFUSAL, not the canonical CLI's usage-at-exit-0: `manifest.ts laws` bound `laws` as the
// ledger path, found no command, and printed the usage at exit 0 — 75 lines that passed the
// SessionStart hook's only guard (non-zero status or empty output), so every seat would have been
// handed the help screen as its campaign laws with nothing red anywhere. A missing ledger must be
// an error the caller cannot mistake for a payload.
import { readdirSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { FLEET_USAGE, fleetSelftest, journalLedgerEvent, runFleetVerb } from "./fleet.ts";
import { LedgerError, readLinesLoose } from "./ledger-core.ts";
import { lawSheet, ledgerEvents, main } from "./ledger.ts";

const HERE = dirname(import.meta.path);
const HELP = new Set(["help", "-h", "--help"]);

/** Every ledger beside this file, in code-point order of the name (manifest.py's `sorted`). */
function ledgers(): string[] {
  const names = readdirSync(HERE).filter((name) => name.endsWith(".toml"));
  return names.sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).map((name) => join(HERE, name));
}

async function aggregateLaws(): Promise<number> {
  const seen = new Set<string>();
  const laws: string[] = [];
  for (const ledger of ledgers()) {
    for (const law of lawSheet(await readLinesLoose(ledger))) {
      if (seen.has(law)) continue;
      seen.add(law);
      laws.push(law);
    }
  }
  if (laws.length > 0) console.log(laws.join("\n"));
  return 0;
}

function refuse(first: string): number {
  const listing = ledgers()
    .map((ledger) => `  bun .dev/campaigns/manifest.ts ${relative(process.cwd(), ledger)} ${first.endsWith(".toml") ? "<command>" : first} ...`)
    .join("\n") || "  (none found)";
  const what = first.endsWith(".toml")
    ? `${first} was named as the ledger but no command was given.`
    : `\`${first}\` addresses ONE campaign ledger and no ledger was given.`;
  console.error(
    `error: ${what} Pass the ledger as the FIRST argument, then the command:\n\n${listing}\n\n` +
      "(`laws` is the one verb that takes no ledger: it prints every campaign's laws.)",
  );
  return 1;
}

async function entry(): Promise<number> {
  const [first, command] = Bun.argv.slice(2);
  if (first === undefined) return await main(); // usage, exit 1
  if (!first.endsWith(".toml")) {
    if (first === "laws") return await aggregateLaws();
    if (HELP.has(first) && command === undefined) return await usage(); // usage, exit 0
    return refuse(first);
  }
  if (command === undefined) return refuse(first);
  if (command === "help") return await usage();
  if (command === "selftest") {
    const vendored = await main();
    return (await fleetSelftest()) || vendored;
  }
  // The fleet journal (fleet.ts): the vendored CLI reports each write, splice's entry records it.
  ledgerEvents.emit = journalLedgerEvent;
  const fleet = await runFleetVerb(first, command, Bun.argv.slice(4));
  if (fleet !== null) return fleet;
  return await main();
}

async function usage(): Promise<number> {
  const code = await main();
  console.log(`\n${FLEET_USAGE}`);
  return code;
}

try {
  process.exitCode = await entry();
} catch (error) {
  if (error instanceof LedgerError) {
    console.error(`ledger: ${error.message}`);
    process.exit(1);
  }
  throw error;
}
