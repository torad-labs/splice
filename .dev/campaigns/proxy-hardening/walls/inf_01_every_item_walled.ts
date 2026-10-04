#!/usr/bin/env bun
/** WALL for INF-01 — every campaign item must name an enforcing wall.
 *
 *  The campaign's own worklist, made into an item so the ledger owns it. Before this existed the gate
 *  reported "79 walls to build" while the LEDGER had zero items about walls: work visible only in a
 *  gate's output is work nobody is assigned (2026-07-26 self-review finding #5).
 *
 *  EXIT 0 = zero rows in wall_registry.toml have wall = "".  EXIT 1 = the worklist is non-empty.
 *  --selftest = positive control (gate check C6).
 *
 *  V4-154: converted from Python to TypeScript (bun) as the FIRST wall of the burn-down, so the
 *  runner's new `.ts` dispatch has one real wall to drive before 35 more follow it. The conversion is
 *  behaviour-preserving by construction: the exit codes, the detection semantics and the LAST LINE of
 *  each output are byte-identical, because those are the whole contract the runner reads —
 *  campaign_wall_gate.py keeps only the final line and the return code, so a conversion that changed
 *  the prose while keeping those would still be correct and one that changed those while keeping the
 *  prose would not. THE POLARITY LAW still governs it: a wall whose item is todo/in_flight must FAIL,
 *  so this file is NOT accepted by being green — it is accepted by agreeing with what the Python file
 *  said on the same tree.
 */
import { resolve } from "node:path";

const ROOT = resolve(import.meta.dir, "../../../..");
const REG = resolve(ROOT, ".dev/campaigns/proxy-hardening/walls/wall_registry.toml");

type Row = Record<string, unknown>;

export function detect(rows: Row[]): string[] {
  if (rows.length === 0) {
    return ["wall_registry.toml has no rows — refusing to pass vacuously"];
  }
  return rows.filter((r) => !String(r.wall ?? "").trim()).map((r) => String(r.id));
}

function selftest(): number {
  const fails: string[] = [];
  if (detect([{ id: "A", wall: "" }, { id: "B", wall: "x.py" }]).length === 0) {
    fails.push("a registry with an unwalled row must be RED");
  }
  if (detect([{ id: "A", wall: "x.py" }, { id: "B", wall: "y.py" }]).length > 0) {
    fails.push("a fully-walled registry must be GREEN");
  }
  if (detect([]).length === 0) {
    fails.push("an empty registry must be RED, never a vacuous pass");
  }
  if (fails.length > 0) {
    process.stdout.write("INF-01 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write("INF-01 SELFTEST OK — red while any row is unwalled, green when all are, red on empty\n");
  return 0;
}

async function readRows(): Promise<Row[]> {
  const text = await Bun.file(REG).text();
  if (text.trim() === "") return [];
  // Bun.TOML.parse over the whole file, mirroring tomllib.loads. A parse failure is deliberately NOT
  // swallowed: an unreadable registry is a broken denominator, and detect([]) then reads RED.
  const parsed = Bun.TOML.parse(text) as { item?: Row[] };
  return parsed.item ?? [];
}

async function main(): Promise<number> {
  if (process.argv.includes("--selftest")) return selftest();
  const rows = await readRows();
  const un = detect(rows);
  process.stdout.write(`INF-01: ${rows.length} registry rows | unwalled ${un.length}\n`);
  if (un.length > 0) {
    process.stdout.write(
      `INF-01 WALL RED: ${un.length} item(s) still have no wall: ${un.slice(0, 12).join(", ")}` +
        (un.length > 12 ? " …" : "") + "\n",
    );
    return 1;
  }
  process.stdout.write("INF-01 WALL GREEN: every campaign item names an enforcing wall.\n");
  return 0;
}

if (import.meta.main) {
  process.exit(await main());
}
