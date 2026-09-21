// `gate run` is the ONE entry to the gate of record. `clean`, `--no-build-cache`, `gateOfRecord`,
// `--continue` and the slot are each recorded in run.ts as correctness; this file pins them, pins
// package.json's `gate` script to this verb, and reads the ladder table the graph is built from so
// that a row naming a script that does not exist, or an npm script package.json does not declare,
// is red here before it is a red leg twenty minutes into a gate run.
import { describe, expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { AFTER_THE_SLOT, GATE_OF_RECORD_LABEL, GATE_OF_RECORD_TASKS, cancelledBySignal } from "../src/commands/run.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
const read = (rel: string) => readFileSync(join(repoRoot, rel), "utf8");
const scripts = (JSON.parse(read("package.json")) as { scripts: Record<string, string> }).scripts;
interface Leg { task: string; why: string; command: string[] }

describe("gate run", () => {
  test("package.json's gate script is this verb, and checks/gate.sh is gone", () => {
    expect(scripts.gate).toBe("bun tools/gate run");
    expect(existsSync(join(repoRoot, "checks", "gate.sh"))).toBe(false);
  });

  // #170 review: a slot phase ended by `kill <gate pid>` returned 143 and the runner walked on into
  // the OSS readiness scripts, which launch more gradle builds — a cancellation that resumed work.
  test("a signalled slot phase cancels the run; a red or refused one is a verdict", () => {
    expect(cancelledBySignal(143), "SIGTERM").toBe(true);
    expect(cancelledBySignal(130), "SIGINT").toBe(true);
    expect(cancelledBySignal(129), "SIGHUP").toBe(true);
    expect(cancelledBySignal(1), "BUILD FAILED is a verdict the post-slot phase still follows").toBe(false);
    expect(cancelledBySignal(2), "the slot's no-tasks refusal").toBe(false);
    expect(cancelledBySignal(75), "the slot timeout").toBe(false);
    expect(cancelledBySignal(0)).toBe(false);
  });

  test("the invocation is pinned: no build cache, clean, the whole ladder, every leg reported", () => {
    expect([...GATE_OF_RECORD_TASKS]).toEqual(["--no-build-cache", "clean", "gateOfRecord", "--continue"]);
    expect(GATE_OF_RECORD_LABEL).toBe("gate-of-record");
    expect(read("tools/gate/src/lib/slot.ts")).toContain("--no-daemon");
  });

  test("the root build applies the ladder plugin, and the plugin registers the table this CLI ships", () => {
    expect(read("build.gradle.kts")).toMatch(/^\s*id\("splice\.gate-ladder"\)/m);
    expect(read("build-logic/src/main/kotlin/splice.gate-ladder.gradle.kts")).toContain('"tools/gate/config/ladder.json"');
  });

  test("every ladder row is a leg: a unique task name, a reason, and an argv whose target exists", () => {
    const legs = (JSON.parse(read("tools/gate/config/ladder.json")) as { legs: Leg[] }).legs;
    expect(legs.length).toBeGreaterThan(0);
    expect(new Set(legs.map((l) => l.task)).size).toBe(legs.length);
    for (const leg of legs) {
      expect(leg.task, `${leg.task}: a Gradle task name`).toMatch(/^[a-z][A-Za-z0-9]*$/);
      expect(leg.why.length, `${leg.task}: needs a reason`).toBeGreaterThan(0);
      expect(["bun", "bash", "npm"], `${leg.task}: runtime`).toContain(leg.command[0]!);
      if (leg.command[0] === "npm") {
        const script = leg.command[leg.command.length - 1]!;
        expect(scripts[script], `${leg.task}: package.json declares no '${script}' script`).toBeDefined();
      } else {
        const target = leg.command[1] === "test" ? leg.command[2]! : leg.command[1]!;
        expect(existsSync(join(repoRoot, target)), `${leg.task}: ${target} does not exist`).toBe(true);
      }
    }
  });

  test("the legs after the slot are exactly the ones that take the slot themselves", () => {
    expect(AFTER_THE_SLOT.map((l) => l.label)).toEqual(["release rehearsal"]);
    for (const leg of AFTER_THE_SLOT) expect(existsSync(join(repoRoot, leg.command[1]!))).toBe(true);
    // and the reason holds today: the rehearsal runs gradle through the slot
    expect(read("tools/release/src/commands/verify.ts")).toMatch(/"slot",\s*SLOT_LABEL/);
  });

  test("argv other than --java-home-only is refused with exit 2", () => {
    for (const argv of [["--bogus"], ["--java-home-only", "extra"], ["check"]]) {
      const proc = Bun.spawnSync([process.execPath, join(repoRoot, "tools", "gate", "index.ts"), "run", ...argv], { stdout: "pipe", stderr: "pipe" });
      expect(proc.exitCode, `argv ${argv.join(" ")}`).toBe(2);
    }
  });
});
