#!/usr/bin/env bun
/** WALL for INF-04 — no item may remain in W12-unscheduled.
 *
 *  CX-05, CX-06, CX-10 and CX-17 have full subsections in §8 of the audit but were assigned to NO wave
 *  in its §10 plan. They were parked in W12-unscheduled so they stay visible rather than being silently
 *  lost. Slotting them is an OPERATOR decision (CX-17 depends on CX-01 in W4).
 *
 *  EXIT 0 = W12-unscheduled is empty.  EXIT 1 = items are still unslotted.
 *  --selftest = positive control (gate check C6).
 *
 *  V4-154: DERIVED from the inf_01/inf_02 pair, not re-authored. Same three-part body — a pure
 *  detect(), a three-arm selftest, and a main that prints one summary line and one verdict line —
 *  with the registry swapped for the campaign BOARD itself and the predicate swapped from "has no
 *  wall" to "sits in the park phase". What the derivation must preserve is the part that is not text:
 *  the exit codes and the LAST LINE of each output, which are the whole contract the runner reads.
 *
 *  THE POLARITY LAW governs it — a wall whose item is todo/in_flight must FAIL — so this file is not
 *  accepted by being green, it is accepted by AGREEING with what the Python said on the same tree.
 */
import { resolve } from "node:path";

const ROOT = resolve(import.meta.dir, "../../../..");
const BOARD = resolve(ROOT, "dev/campaigns/proxy-hardening.toml");
const PARK = "W12-unscheduled";

type Item = Record<string, unknown>;

export function detect(items: Item[]): string[] {
  if (items.length === 0) {
    return ["ledger has no items — refusing to pass vacuously"];
  }
  return items.filter((i) => String(i.phase ?? "") === PARK).map((i) => String(i.id));
}

function selftest(): number {
  const fails: string[] = [];
  if (detect([{ id: "CX-05", phase: PARK }, { id: "NF-01", phase: "W1" }]).length === 0) {
    fails.push("a parked item must be RED");
  }
  if (detect([{ id: "CX-05", phase: "W4-correctness-walls" }]).length > 0) {
    fails.push("a fully-slotted ledger must be GREEN");
  }
  if (detect([]).length === 0) {
    fails.push("an empty ledger must be RED, never a vacuous pass");
  }
  if (fails.length > 0) {
    process.stdout.write("INF-04 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write("INF-04 SELFTEST OK — red while any item sits in W12, green once all are slotted, red on empty\n");
  return 0;
}

async function main(): Promise<number> {
  if (process.argv.includes("--selftest")) return selftest();
  // A parse failure is deliberately NOT swallowed: an unreadable board is a broken denominator, and
  // detect([]) then reads RED rather than reporting a clean park.
  const items = (Bun.TOML.parse(await Bun.file(BOARD).text()) as { items?: Item[] }).items ?? [];
  const parked = detect(items);
  process.stdout.write(`INF-04: ${items.length} items | parked in ${PARK}: ${parked.length}\n`);
  if (parked.length > 0) {
    process.stdout.write(`INF-04 WALL RED: unslotted — ${parked.join(", ")}. Operator decision; §10 omitted them.\n`);
    return 1;
  }
  process.stdout.write(`INF-04 WALL GREEN: ${PARK} is empty.\n`);
  return 0;
}

if (import.meta.main) {
  process.exit(await main());
}
