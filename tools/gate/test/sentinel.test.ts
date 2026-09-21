// The run sentinel's red proofs. Liveness claims are the easiest thing in this repo to assert
// vacuously: a probe that can never report HELD reports FREE forever and looks like a passing test.
// So the control case is asserted FIRST in every arm that matters, and the SIGKILL arm exists
// because SIGKILL is the designed-for case here — earlyoom is active and buildgate sets
// `choom -n 800`, which nominates a long gradle run as the first thing to die under pressure.
import { afterEach, describe, expect, test } from "bun:test";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { acquireRunSentinel, probeRunSentinel, releaseForTests, sentinelPath } from "../src/lib/sentinel.ts";

const workspaces: string[] = [];
function scratchSentinel(): string {
  const dir = mkdtempSync(join(tmpdir(), "gate-sentinel-"));
  workspaces.push(dir);
  const path = join(dir, "gate-run.lock");
  process.env.SPLICE_GATE_SENTINEL = path;
  return path;
}

afterEach(() => {
  releaseForTests();
  delete process.env.SPLICE_GATE_SENTINEL;
  for (const dir of workspaces.splice(0)) rmSync(dir, { recursive: true, force: true });
});

/** A holder in its own process, so it can be killed. Resolves once it reports it holds the lock. */
async function holderProcess(path: string): Promise<{ pid: number; kill: (sig: NodeJS.Signals) => void }> {
  const proc = Bun.spawn(
    [
      "bun",
      "-e",
      `process.env.SPLICE_GATE_SENTINEL=${JSON.stringify(path)};` +
        `const { acquireRunSentinel } = await import(${JSON.stringify(join(import.meta.dir, "../src/lib/sentinel.ts"))});` +
        `if (acquireRunSentinel("cafebabe") !== null) process.exit(3);` +
        `console.log("HELD"); await new Promise(() => {});`,
    ],
    { stdout: "pipe", stderr: "ignore" },
  );
  const reader = proc.stdout.getReader();
  const deadline = Date.now() + 15_000;
  let seen = "";
  while (!seen.includes("HELD") && Date.now() < deadline) {
    const { value, done } = await reader.read();
    if (done) break;
    seen += new TextDecoder().decode(value);
  }
  expect(seen, "the holder must report HELD before an arm measures it").toContain("HELD");
  return { pid: proc.pid, kill: (sig) => proc.kill(sig === "SIGKILL" ? 9 : 15) };
}

describe("the gate run sentinel", () => {
  test("the path is outside the worktree — a marker inside it would fail the gate's own precondition", () => {
    delete process.env.SPLICE_GATE_SENTINEL;
    const path = sentinelPath();
    expect(path).not.toContain("/mythos/repo");
    expect(path.endsWith("/splice/gate-run.lock"), `${path} must be splice's own runtime dir`).toBe(true);
  });

  test("FREE when nothing holds it — the control, without which every HELD below is vacuous", () => {
    scratchSentinel();
    expect(probeRunSentinel()).toBeNull();
  });

  test("HELD while a run is open, and the open run is named", () => {
    scratchSentinel();
    expect(probeRunSentinel(), "control: free before we take it").toBeNull();
    expect(acquireRunSentinel("deadbeef")).toBeNull();
    const open = probeRunSentinel();
    expect(open).not.toBeNull();
    expect(open!.headAtStart).toBe("deadbeef");
    expect(open!.pid).toBe(process.pid);
    expect(open!.start).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  });

  // The regression arm for the ordering bug that building this found in its own design. The design
  // said write-atomically-then-lock (temp + rename). A rename onto the final path replaces the
  // INODE, so a second run would orphan the holder's lock and acquire the new one — both believing
  // they held it, which is the single thing the sentinel exists to prevent. Restore that ordering
  // and this arm goes red.
  test("a second run does NOT acquire while one is open, and is told what is open", () => {
    scratchSentinel();
    expect(acquireRunSentinel("first")).toBeNull();
    const refused = acquireRunSentinel("second");
    expect(refused, "the second acquire must be refused, not granted a fresh inode").not.toBeNull();
    expect(refused!.headAtStart).toBe("first");
    expect(probeRunSentinel()!.headAtStart, "the first run still owns it").toBe("first");
  });

  test("a SIGKILLed run releases it — staleness is inexpressible, not merely detectable", async () => {
    const path = scratchSentinel();
    expect(probeRunSentinel(), "control: free before the holder starts").toBeNull();
    const holder = await holderProcess(path);
    expect(probeRunSentinel(), "while the holder is ALIVE it must read HELD").not.toBeNull();
    holder.kill("SIGKILL");
    const deadline = Date.now() + 10_000;
    while (Date.now() < deadline && probeRunSentinel() !== null) await Bun.sleep(50);
    expect(probeRunSentinel(), "the kernel drops an flock on SIGKILL; no trap can").toBeNull();
  }, 40_000);

  test("the fd does not leak to spawned children — a Gradle daemon must not pin it forever", async () => {
    const path = scratchSentinel();
    const proc = Bun.spawn(
      [
        "bun",
        "-e",
        `process.env.SPLICE_GATE_SENTINEL=${JSON.stringify(path)};` +
          `const { acquireRunSentinel } = await import(${JSON.stringify(join(import.meta.dir, "../src/lib/sentinel.ts"))});` +
          `if (acquireRunSentinel("x") !== null) process.exit(3);` +
          `Bun.spawn(["sleep", "20"], { stdio: ["ignore", "ignore", "ignore"] });`,
      ],
      { stdout: "ignore", stderr: "ignore" },
    );
    await proc.exited;
    const deadline = Date.now() + 10_000;
    while (Date.now() < deadline && probeRunSentinel() !== null) await Bun.sleep(50);
    expect(probeRunSentinel(), "the long-lived grandchild must not still be holding it").toBeNull();
  }, 40_000);
});
