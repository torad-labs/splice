#!/usr/bin/env bun
// console/tools — the console's own dev tooling as one CLI: `bun console/tools <verb> …`.
// Dispatch only. Every verb is a file under src/ that keeps its own argv reading and its own
// `isMain` guard, so the dispatcher runs it IN PROCESS by making that file argv[1] and importing it
// — no child, no shell, and a verb's library exports stay importable by the others as before.
// The verb table lives in src/lib/verbs.ts, shared with the coverage census.
import { VERBS } from './src/lib/verbs.ts';

const USAGE = `usage: bun console/tools <verb> [args]

  look '<url>' …                freeze the address, run the rendered rules and the gate, report the two distributions
  gate [--captures DIR] …       the mechanical half of a design review; 'gate sheets <milestone>' the contact sheets
  capture '<url>' <out.png> …   one proven screenshot; '--sweep <dir>' scores frames on disk
  snapshot '<url>' [out.html]   one self-contained HTML freeze of an address
  comp [--frame WxH] …          the approved comp's constants measured on the live console
  exit [--only a,b] [--json]    the milestone exit gate over every leg
  leak                          no fixture byte ships in the built bundle
  scan <path…>                  the structural walls over a path they actually read
  scale '<url>' [--sizes …]     what the console looks like across frames
  coverage [--selftest]         every checker here carries a disposition the tree agrees with
  theme '<url>' …               both rooms captured; '--selftest' proves the seeding
  typography [--address …]      the type and spacing ladders against the comp
  fixtures [--json]             the address-to-fixture table checked against the pages
`;

if (import.meta.main) {
  const [verb, ...rest] = process.argv.slice(2);
  if (verb === undefined || verb === '--help' || verb === '-h' || verb === 'help') {
    console.log(USAGE);
    process.exit(verb === undefined ? 2 : 0);
  }
  const file = VERBS[verb];
  if (file === undefined) {
    console.error(`console/tools: no such verb "${verb}" — expected one of ${Object.keys(VERBS).join(', ')}`);
    process.exit(2);
  }
  const target = new URL(file, import.meta.url);
  // the verb's file reads process.argv the way it always did: itself at [1], its own args after
  process.argv = [process.argv[0], Bun.fileURLToPath(target), ...rest];
  await import(target.href);
}
