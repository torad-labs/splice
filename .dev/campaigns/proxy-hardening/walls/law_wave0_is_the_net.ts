#!/usr/bin/env bun
/** LAW ENFORCER — WAVE-0-IS-THE-NET.
 *
 *  THE LAW  CX-19 (the response-side oracle) is the only regression net for W4-correctness-walls and
 *  W5-overflow-skew. Nothing in those waves lands ahead of it.
 *
 *  WHY A WALL  This is a dependency the plan states in prose and nothing checks. The failure is
 *  quiet and expensive: a W4 dialect change lands, the suite is green because nothing pins the emitted
 *  SSE, and the regression surfaces weeks later in a live session. #924 — make the ordering
 *  structural, not a sentence someone has to remember.
 *
 *  POLARITY NOTE — a LAW enforcer, not an item wall. GREEN today (nothing in W4/W5 is done); red means
 *  the ordering was broken. That inversion is WHY the agreement oracle matters more here than
 *  anywhere: this file is expected to be green, so a conversion that broke it and a conversion that
 *  worked look identical from the exit code alone. Only the byte-for-byte comparison against the
 *  Python tells them apart.
 *
 *  EXIT 0 = no W4/W5 item is done/verified while CX-19 is unfinished.  EXIT 1 = the net was skipped.
 *  --selftest = positive control.
 *
 *  V4-154: converted to TypeScript (bun). The negative cases in the selftest are the ones that carry
 *  the wall, so they were transcribed one for one rather than re-thought.
 */
import { resolve } from "node:path";

const ROOT = resolve(import.meta.dir, "../../../..");
const BOARD = resolve(ROOT, ".dev/campaigns/proxy-hardening.toml");

const NET = "CX-19";
const GATED_PHASES = new Set(["W4-correctness-walls", "W5-overflow-skew"]);
const DONE = new Set(["done", "verified"]);

type Item = Record<string, unknown>;

/** items: [{id, phase, status}] — pure. */
export function detect(items: Item[]): string[] {
  const net = items.find((i) => i.id === NET);
  if (net === undefined) {
    return [
      `${NET} is not in the ledger — the regression net for ${[...GATED_PHASES].sort().join(", ")} ` +
        "has vanished; refusing to pass vacuously",
    ];
  }
  if (DONE.has(String(net.status))) return [];
  const landed = items.filter((i) => GATED_PHASES.has(String(i.phase)) && DONE.has(String(i.status)));
  return landed.map(
    (i) =>
      `${i.id} [${i.phase}] is ${i.status} while ${NET} is still ${net.status} — ` +
      "it landed with no response-side regression net",
  );
}

function selftest(): number {
  const fails: string[] = [];

  const kase = (name: string, items: Item[], wantRed: boolean): void => {
    const got = detect(items);
    if (wantRed && got.length === 0) fails.push(`${name}: must be RED`);
    if (!wantRed && got.length > 0) fails.push(`${name}: must be GREEN, got ${JSON.stringify(got)}`);
  };

  const netTodo = { id: NET, phase: "W0-net", status: "todo" };
  const netDone = { id: NET, phase: "W0-net", status: "verified" };
  const w4Done = { id: "CX-01", phase: "W4-correctness-walls", status: "done" };
  const w4Todo = { id: "CX-01", phase: "W4-correctness-walls", status: "todo" };
  const w8Done = { id: "JW-09", phase: "W8-operator-surface", status: "verified" };

  kase("nothing landed yet", [netTodo, w4Todo], false);
  kase("W4 landed before the net", [netTodo, w4Done], true);
  kase("W4 landed after the net", [netDone, w4Done], false);
  kase("ungated phase may land anytime", [netTodo, w8Done], false);
  kase("net missing entirely", [w4Todo], true);

  if (fails.length > 0) {
    process.stdout.write("WAVE-0 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "WAVE-0 SELFTEST OK — red when a gated-phase item lands ahead of the net, green once the net " +
      "is verified, ungated phases unaffected, missing net is red\n",
  );
  return 0;
}

async function main(): Promise<number> {
  if (process.argv.includes("--selftest")) return selftest();
  const items = (Bun.TOML.parse(await Bun.file(BOARD).text()) as { items?: Item[] }).items ?? [];
  const problems = detect(items);
  const net = items.find((i) => i.id === NET) ?? {};
  process.stdout.write(
    `WAVE-0-IS-THE-NET: ${NET} is '${net.status ?? "absent"}'; ` +
      `gated phases = ${[...GATED_PHASES].sort().join(", ")}\n`,
  );
  if (problems.length > 0) {
    process.stdout.write("LAW VIOLATED — the regression net was skipped:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write("LAW HONORED: no gated-phase item has landed ahead of the net.\n");
  return 0;
}

if (import.meta.main) {
  process.exit(await main());
}
