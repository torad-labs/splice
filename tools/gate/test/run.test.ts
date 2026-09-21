// `gate run` is the gate of record, and its invocation is not a preference: `clean`,
// `--no-build-cache` and the slot are each recorded in checks/gate.sh as correctness. gate.sh:84 is
// the oracle — read, not copied — so that changing one entry and not the other turns this red.
import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { GATE_OF_RECORD_LABEL, GATE_OF_RECORD_TASKS, run } from "../src/commands/run.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();

describe("gate run", () => {
  test("is the invocation checks/gate.sh:84 makes, label included", () => {
    const gate = readFileSync(join(repoRoot, "checks", "gate.sh"), "utf8");
    const line = /^run "gradle clean check" bash checks\/gradle-slot\.sh (\S+) (.+)$/m.exec(gate);
    expect(line, "checks/gate.sh must still route the gate of record through the slot").not.toBeNull();
    expect(line![1]).toBe(GATE_OF_RECORD_LABEL);
    expect(line![2]!.trim().split(/\s+/)).toEqual([...GATE_OF_RECORD_TASKS]);
  });

  test("--no-daemon comes from the slot, not from the verb — it must not be passed twice", () => {
    expect(GATE_OF_RECORD_TASKS).not.toContain("--no-daemon");
    const slotScript = readFileSync(join(repoRoot, "checks", "gradle-slot.sh"), "utf8");
    expect(slotScript).toContain("--no-daemon");
  });

  test("takes no arguments — the gate of record is one fixed invocation", async () => {
    const original = console.error;
    console.error = () => {};
    try {
      expect(await run([":app:test"])).toBe(2);
    } finally {
      console.error = original;
    }
  });
});
