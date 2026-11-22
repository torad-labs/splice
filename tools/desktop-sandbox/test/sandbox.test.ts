// Unit tests for tools/desktop-sandbox CLI verbs and personal-folder refusal guard.

import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readdirSync, rmSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const TOOL = resolve(import.meta.dir, "..", "main.ts");

function runSandbox(args: string[], env: Record<string, string> = {}) {
  const r = spawnSync("bun", [TOOL, ...args], {
    encoding: "utf8",
    timeout: 30_000,
    env: { ...process.env, ...env },
  });
  return { code: r.status ?? -1, out: `${r.stdout ?? ""}${r.stderr ?? ""}` };
}

describe("desktop-sandbox", () => {
  test("--help names every required verb", () => {
    const r = runSandbox(["--help"]);
    expect(r.code).toBe(0);
    for (const verb of ["start", "shot", "click", "type", "key", "splice", "relaunch", "stop", "check"]) {
      expect(r.out).toContain(verb);
    }
  });

  test("refuses any sandbox root resolving into personal Desktop or Claude data without creating files", () => {
    const fake = mkdtempSync(join(tmpdir(), "desktop-sandbox-refusal-"));
    try {
      mkdirSync(join(fake, ".config", "Claude"), { recursive: true });
      const link = join(fake, "harmless-link");
      symlinkSync(join(fake, ".config", "Claude"), link);
      const r = runSandbox(["start", "--root", join(link, "sandbox")], {
        HOME: fake,
        XDG_CONFIG_HOME: join(fake, ".config"),
      });
      expect(r.code).not.toBe(0);
      expect(r.out).toMatch(/refus/i);
      expect(readdirSync(join(fake, ".config", "Claude"))).toEqual([]);
    } finally {
      rmSync(fake, { recursive: true, force: true });
    }
  });
});
