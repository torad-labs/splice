// `gate run` is the gate of record, and its invocation is not a preference: `clean`,
// `--no-build-cache` and the slot are each recorded in run.ts as correctness. While checks/gate.sh
// still exists (it dies at the end of PR 5) its gradle leg must ENTER through this verb — one
// boundary, read here rather than assumed — so a second spelling of the gate of record turns red.
import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { GATE_OF_RECORD_TASKS, run } from "../src/commands/run.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();

describe("gate run", () => {
  test("checks/gate.sh's gradle leg enters through this verb and spells no tasks of its own", () => {
    const gate = readFileSync(join(repoRoot, "checks", "gate.sh"), "utf8");
    expect(gate).toMatch(/^run "gradle clean check" bun tools\/gate run$/m);
    expect(gate).not.toContain("gradle-slot.sh");
  });

  test("the gate of record is clean check without the build cache", () => {
    expect([...GATE_OF_RECORD_TASKS]).toEqual(["--no-build-cache", "clean", "check"]);
  });

  test("--no-daemon comes from the slot, not from the verb — it must not be passed twice", () => {
    expect(GATE_OF_RECORD_TASKS).not.toContain("--no-daemon");
    const slot = readFileSync(join(import.meta.dir, "..", "src", "lib", "slot.ts"), "utf8");
    expect(slot).toContain('"--no-daemon"');
  });

  test("takes no arguments but --java-home-only — the gate of record is one fixed invocation", async () => {
    const original = console.error;
    console.error = () => {};
    try {
      expect(await run([":app:test"])).toBe(2);
      expect(await run(["--java-home-only", "extra"])).toBe(2);
    } finally {
      console.error = original;
    }
  });
});
