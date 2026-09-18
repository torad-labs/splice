// NEW: V4-143 — the campaign-ledger CLI's entry, under the name every seat, law and hook already uses.
//
// The CLI itself is the canonical bun ledger suite, VENDORED beside this file (ledger.ts,
// ledger-core.ts, ledger-earn.ts, earn-core.ts, review.ts; lineage and every delta in VENDORED.md).
// This file only runs it: keeping the vendored modules byte-for-byte their upstream layout is what
// makes the next re-vendor a diff rather than a hand-merge.
//
// Usage is the canonical CLI's: `bun .dev/campaigns/manifest.ts <ledger.toml> <command> [args]`.
import { LedgerError } from "./ledger-core.ts";
import { main } from "./ledger.ts";

try {
  process.exitCode = await main();
} catch (error) {
  if (error instanceof LedgerError) {
    console.error(`ledger: ${error.message}`);
    process.exit(1);
  }
  throw error;
}
