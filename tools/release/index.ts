#!/usr/bin/env bun
// tools/release — the repository's release CLI. Dispatch only, the same shape as tools/gate and
// tools/e2e: every verb's behaviour lives in src/commands/<verb>.ts, and everything two verbs share
// lives in src/lib/. The staging half is NOT here: `:app:stageRelease` is a Gradle task, because it
// produces the published artifacts and Gradle is what knows when they are stale.
//
// Exit codes: 0 accepted / promoted / verified, 1 a check said no, 2 the CLI was asked for something
// it does not have. A signalled child reports as a shell would (tools/gate/src/lib/status.ts).
import { layout } from "../gate/src/lib/repo.ts";
import { accept, usage as acceptUsage } from "./src/commands/accept.ts";
import { promote, usage as promoteUsage } from "./src/commands/promote.ts";
import { verify, usage as verifyUsage } from "./src/commands/verify.ts";

const { repoRoot } = layout();

const VERBS = {
  accept: { usage: acceptUsage, exec: (argv: string[]) => accept(argv, repoRoot) },
  promote: { usage: promoteUsage, exec: (argv: string[]) => promote(argv, repoRoot) },
  verify: { usage: verifyUsage, exec: (argv: string[]) => verify(argv, repoRoot) },
} satisfies Record<string, { usage: string; exec: (argv: string[]) => number | Promise<number> }>;

const [verb, ...argv] = process.argv.slice(2);

if (!verb || verb === "--help" || verb === "-h" || verb === "help") {
  console.log("usage: bun tools/release <verb> [args]\n");
  for (const { usage } of Object.values(VERBS)) console.log(`  ${usage}`);
  process.exit(verb ? 0 : 2);
}

const selected = VERBS[verb as keyof typeof VERBS];
if (!selected) {
  console.error(`release: no such verb "${verb}" — expected one of ${Object.keys(VERBS).join(", ")}`);
  process.exit(2);
}
process.exit(await selected.exec(argv));
