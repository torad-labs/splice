#!/usr/bin/env bun
// tools/gate — the repository's gate CLI. Dispatch only: every verb's behaviour lives in
// src/commands/<verb>.ts, and everything two verbs share lives in src/lib/.
import { allowlist, usage as allowlistUsage } from "./src/commands/allowlist.ts";
import { audit, usage as auditUsage } from "./src/commands/audit.ts";
import { ledger, usage as ledgerUsage } from "./src/commands/ledger.ts";
import { noPython, usage as noPythonUsage } from "./src/commands/no-python.ts";
import { rules, usage as rulesUsage } from "./src/commands/rules.ts";
import { sentinel, usage as sentinelUsage } from "./src/commands/sentinel.ts";
import { run, usage as runUsage } from "./src/commands/run.ts";
import { slot, usage as slotUsage } from "./src/commands/slot.ts";
import { title, usage as titleUsage } from "./src/commands/title.ts";

const VERBS = {
  run: { usage: runUsage, exec: (argv: string[]) => run(argv) },
  slot: { usage: slotUsage, exec: (argv: string[]) => slot(argv) },
  sentinel: { usage: sentinelUsage, exec: (argv: string[]) => sentinel(argv) },
  rules: { usage: rulesUsage, exec: (argv: string[]) => rules(argv) },
  title: { usage: titleUsage, exec: (argv: string[]) => title(argv) },
  "no-python": { usage: noPythonUsage, exec: (argv: string[]) => noPython(argv) },
  ledger: { usage: ledgerUsage, exec: (argv: string[]) => ledger(argv) },
  audit: { usage: auditUsage, exec: (argv: string[]) => audit(argv) },
  allowlist: { usage: allowlistUsage, exec: (argv: string[]) => allowlist(argv) },
} satisfies Record<string, { usage: string; exec: (argv: string[]) => number | Promise<number> }>;

const [verb, ...argv] = process.argv.slice(2);

if (!verb || verb === "--help" || verb === "-h" || verb === "help") {
  console.log("usage: bun tools/gate <verb> [args]\n");
  for (const { usage } of Object.values(VERBS)) console.log(`  ${usage}`);
  process.exit(verb ? 0 : 2);
}

const selected = VERBS[verb as keyof typeof VERBS];
if (!selected) {
  console.error(`gate: no such verb "${verb}" — expected one of ${Object.keys(VERBS).join(", ")}`);
  process.exit(2);
}
process.exit(await selected.exec(argv));
