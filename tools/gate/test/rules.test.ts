// `gate rules` must be the SAME ast-grep invocations `npm run gate:rules` runs — not an
// equivalent set. package.json:15 is the oracle, read here rather than copied, so that editing the
// npm script and not the CLI (or the reverse) turns this red instead of producing two checkers that
// drift and then agree with each other while disagreeing with the tree.
import { describe, expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { EXCLUSIONS, ROUTED_CONFIG } from "../src/commands/rules.ts";
import { astGrepBin } from "../src/lib/astgrep.ts";
import { layout } from "../src/lib/repo.ts";
import { readRules, readSgConfig } from "../src/lib/rules.ts";

const { repoRoot } = layout();
const script = (JSON.parse(readFileSync(join(repoRoot, "package.json"), "utf8")) as { scripts: Record<string, string> })
  .scripts["gate:rules"]!;

describe("gate rules", () => {
  test("runs exactly the legs package.json:15 runs, in order", async () => {
    const { LEGS } = (await import("../src/commands/rules.ts")) as unknown as { LEGS?: string[][] };
    const legs = LEGS ?? legsFromSource();
    const fromNpm = script.split("&&").map((leg) => leg.trim().split(/\s+/));
    expect(fromNpm.every((leg) => leg[0] === "ast-grep")).toBe(true);
    expect(legs).toEqual(fromNpm.map((leg) => leg.slice(1)));
  });

  test("the config the npm script implies exists, and it names no other", () => {
    // the routed config is the implicit one: `ast-grep scan` with no --config walks up to it
    expect(existsSync(join(repoRoot, ROUTED_CONFIG))).toBe(true);
    expect(script).not.toContain("--config");
  });

  test("prefers the pinned node_modules ast-grep, as npm's PATH does", () => {
    const local = join(repoRoot, "node_modules", ".bin", "ast-grep");
    const chosen = astGrepBin(repoRoot);
    expect(existsSync(local) ? chosen : Bun.which("ast-grep")).toBe(chosen);
  });

  test("the coverage exclusion table it reads exists and speaks only for routed rules", async () => {
    expect(existsSync(join(repoRoot, EXCLUSIONS))).toBe(true);
    const ids = new Set(readRules(readSgConfig(join(repoRoot, ROUTED_CONFIG)).ruleDirs).map((r) => r.id));
    const table = (await import(join(repoRoot, EXCLUSIONS))) as { default: { exclusion: { rule?: string; rules?: string[] }[] } };
    for (const row of table.default.exclusion) {
      for (const id of [...(row.rules ?? []), ...(row.rule ? [row.rule] : [])]) expect(ids).toContain(id);
    }
  });
});

/** LEGS is module-private on purpose; read it from the source so the test cannot drift from it. */
function legsFromSource(): string[][] {
  const source = readFileSync(join(import.meta.dir, "..", "src", "commands", "rules.ts"), "utf8");
  const block = /const LEGS: readonly \(readonly string\[\]\)\[\] = \[(.*?)\n\];/s.exec(source);
  expect(block, "rules.ts must still declare LEGS the way this test reads it").not.toBeNull();
  return block![1]!
    .split("\n")
    .map((line) => line.trim().replace(/,$/, ""))
    .filter((line) => line.startsWith("["))
    .map((line) => [...line.matchAll(/"([^"]*)"/g)].map((m) => m[1]!));
}
