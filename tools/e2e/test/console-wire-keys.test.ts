import { expect, test } from "bun:test";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { findRepoRoot } from "../../gate/src/lib/repo.ts";

test("the relocated console probe discovers its sources from an unrelated cwd", () => {
  const replay = mkdtempSync(join(tmpdir(), "console-wire-keys-replay-"));
  try {
    const probe = join(findRepoRoot(import.meta.dir), "tools/e2e/probes/console-wire-keys.ts");
    const child = Bun.spawnSync([process.execPath, probe, "--replay", replay], {
      cwd: replay,
      stdio: ["ignore", "pipe", "pipe"],
    });
    // An empty replay must reach the payload checks, then fail for missing captures.
    expect(child.exitCode).toBe(1);
    expect(child.stdout.toString()).toContain("console-wire-keys: FAIL");
    expect(child.stderr.toString()).not.toContain("Cannot read file");
  } finally {
    rmSync(replay, { recursive: true, force: true });
  }
}, 30_000);
