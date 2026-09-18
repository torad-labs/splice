#!/usr/bin/env bun
/** WALL for INF-02 — every campaign standing law must name a mechanical enforcer.
 *
 *  The campaign's own worklist, made into an item so the ledger owns it. Before this existed the gate
 *  reported "79 walls to build" while the LEDGER had zero items about walls: work visible only in a
 *  gate's output is work nobody is assigned (2026-07-26 self-review finding #5).
 *
 *  EXIT 0 = zero rows in law_registry.toml have wall = "".  EXIT 1 = the worklist is non-empty.
 *  --selftest = positive control (gate check C6).
 *
 *  V4-154: DERIVED FROM inf_01_every_item_walled.ts, not re-authored. This file and its sibling
 *  differ in exactly three things — the registry they read (law_registry.toml, keyed `law`), the
 *  field they grade (`tag` rather than `id`), and the two message prefixes — so the conversion is a
 *  scripted substitution over the proven reference rather than a second piece of thinking. What the
 *  derivation has to preserve is the part that is NOT text: the exit codes, the detection semantics
 *  and the LAST LINE of each output, because those are the whole contract the runner reads.
 *
 *  THE POLARITY LAW still governs it — a wall whose item is todo/in_flight must FAIL — so this file
 *  is not accepted by being green, it is accepted by AGREEING with what the Python file said on the
 *  same tree. Unusually for this family, INF-02's item IS todo, so a green here would be the broken
 *  conversion rather than the good one.
 */
import { resolve } from "node:path";

const ROOT = resolve(import.meta.dir, "../../../..");
const REG = resolve(ROOT, ".dev/campaigns/proxy-hardening/walls/law_registry.toml");

type Row = Record<string, unknown>;

export function detect(rows: Row[]): string[] {
  if (rows.length === 0) {
    return ["law_registry.toml has no rows — refusing to pass vacuously"];
  }
  return rows.filter((r) => !String(r.wall ?? "").trim()).map((r) => String(r.tag));
}

function selftest(): number {
  const fails: string[] = [];
  if (detect([{ tag: "A", wall: "" }, { tag: "B", wall: "x.py" }]).length === 0) {
    fails.push("a registry with an unwalled row must be RED");
  }
  if (detect([{ tag: "A", wall: "x.py" }, { tag: "B", wall: "y.py" }]).length > 0) {
    fails.push("a fully-walled registry must be GREEN");
  }
  if (detect([]).length === 0) {
    fails.push("an empty registry must be RED, never a vacuous pass");
  }
  if (fails.length > 0) {
    process.stdout.write("INF-02 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write("INF-02 SELFTEST OK — red while any row is unwalled, green when all are, red on empty\n");
  return 0;
}

async function readRows(): Promise<Row[]> {
  const text = await Bun.file(REG).text();
  if (text.trim() === "") return [];
  // A parse failure is deliberately NOT swallowed: an unreadable registry is a broken denominator,
  // and detect([]) then reads RED.
  const parsed = Bun.TOML.parse(text) as { law?: Row[] };
  return parsed.law ?? [];
}

async function main(): Promise<number> {
  if (process.argv.includes("--selftest")) return selftest();
  const rows = await readRows();
  const un = detect(rows);
  process.stdout.write(`INF-02: ${rows.length} law rows | unlawed ${un.length}\n`);
  if (un.length > 0) {
    process.stdout.write(
      `INF-02 WALL RED: ${un.length} item(s) still have no wall: ${un.slice(0, 12).join(", ")}` +
        (un.length > 12 ? " …" : "") + "\n",
    );
    return 1;
  }
  process.stdout.write("INF-02 WALL GREEN: every campaign item names an enforcing wall.\n");
  return 0;
}

if (import.meta.main) {
  process.exit(await main());
}
