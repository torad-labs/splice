// `gate rules` is the ONE entry for the ast-grep walls: package.json's gate:rules is this verb, so
// there is no second spelling of the legs to drift from. The two ast-grep invocations the npm script
// used to spell are pinned here literally — the oracle is the recorded contract, not a copy.
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
  test("package.json's gate:rules is this verb, coverage proof included", () => {
    expect(script.trim()).toBe("bun tools/gate rules --prove-coverage");
  });

  test("runs the two ast-grep invocations the npm script used to spell, in order", () => {
    expect(legsFromSource()).toEqual([["scan"], ["test", "--skip-snapshot-tests"]]);
  });

  test("the implicit config exists at the root, and the verb names no other", () => {
    // `ast-grep scan` with no --config walks up to it; a nested config would leave every anchored
    // glob selecting nothing, silently (measured on ast-grep 0.45.2).
    expect(existsSync(join(repoRoot, ROUTED_CONFIG))).toBe(true);
    expect(ROUTED_CONFIG).toBe("sgconfig.yml");
    expect(legsFromSource().flat()).not.toContain("--config");
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
