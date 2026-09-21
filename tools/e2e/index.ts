#!/usr/bin/env bun
// tools/e2e — the repository's end-to-end CLI. Dispatch only, the same shape as tools/gate:
// every verb's behaviour lives in src/commands/<verb>.ts, and everything two verbs share lives in
// src/lib/. Exit codes come back from the verb; a signalled child reports as a shell would
// (128+signum, tools/gate/src/lib/status.ts), never `exitCode ?? 1`.
import { oracle, usage as oracleUsage } from "./src/commands/oracle.ts";

const VERBS = {
  oracle: { usage: oracleUsage, exec: (argv: string[]) => oracle(argv) },
} satisfies Record<string, { usage: string; exec: (argv: string[]) => number | Promise<number> }>;

const [verb, ...argv] = process.argv.slice(2);

if (!verb || verb === "--help" || verb === "-h" || verb === "help") {
  console.log("usage: bun tools/e2e <verb> [args]\n");
  for (const { usage } of Object.values(VERBS)) console.log(`  ${usage}`);
  process.exit(verb ? 0 : 2);
}

const selected = VERBS[verb as keyof typeof VERBS];
if (!selected) {
  console.error(`e2e: no such verb "${verb}" — expected one of ${Object.keys(VERBS).join(", ")}`);
  process.exit(2);
}
process.exit(await selected.exec(argv));
