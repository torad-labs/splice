// `gate run` is the ONE entry to the gate of record. A slot phase ended by a signal cancels the run; the CLI refuses
// arguments it does not know.
import { describe, expect, test } from "bun:test";
import { join } from "node:path";
import { cancelledBySignal } from "../src/commands/run.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();

describe("gate run", () => {
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

  test("argv other than --java-home-only is refused with exit 2", () => {
    for (const argv of [["--bogus"], ["--java-home-only", "extra"], ["check"]]) {
      const proc = Bun.spawnSync([process.execPath, join(repoRoot, "tools", "gate", "index.ts"), "run", ...argv], { stdout: "pipe", stderr: "pipe" });
      expect(proc.exitCode, `argv ${argv.join(" ")}`).toBe(2);
    }
  });
});
