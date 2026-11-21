// df-sandbox: `tools/desktop-sandbox` runs the installed Claude Desktop in a sandbox of its own (its own HOME and
// XDG_CONFIG_HOME, D-Bus session, basic password store, private X display and a splice daemon from this build), and
// its `check` drives one whole round with no person: splice's app-mode configuration applied, Desktop opened on it
// and seen asking the sandbox's door, a screenshot of what it shows, and `off` taking it back out. It never writes
// the person's own Desktop, Claude, splice or keyring folders, and refuses a sandbox that would.

import { describe, expect, setDefaultTimeout, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, statSync, symlinkSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { ROOT, jar } from "../server/lib/harness.ts";

setDefaultTimeout(900_000);

const TOOL = join(ROOT, "tools", "desktop-sandbox", "main.ts");

function sandbox(args: string[], env: Record<string, string> = {}) {
  const r = spawnSync("bun", [TOOL, ...args], { cwd: ROOT, encoding: "utf8", timeout: 840_000, env: { ...process.env, SPLICE_JAR: jar(), ...env } });
  return { code: r.status ?? -1, out: `${r.stdout ?? ""}${r.stderr ?? ""}` };
}

/** What the person's own Desktop data folders hold now: each one's entries, or absent. */
function personal(): Record<string, string[] | null> {
  const home = homedir();
  const dirs = [join(home, ".config", "Claude-3p"), join(home, ".config", "Claude", "claude_desktop_config.json")];
  return Object.fromEntries(dirs.map((d) => [d, existsSync(d) ? (statSync(d).isDirectory() ? readdirSync(d).sort() : [String(statSync(d).mtimeMs)]) : null]));
}

describe("df-sandbox", () => {
  test("the wrapper names its commands", () => {
    const r = sandbox(["--help"]);
    expect(r.code).toBe(0);
    for (const verb of ["start", "shot", "click", "type", "key", "splice", "relaunch", "stop", "check"]) expect([verb, r.out.includes(verb)]).toEqual([verb, true]);
  });

  test("a sandbox whose folder resolves into the person's own Desktop data is refused, and nothing is made", () => {
    const fake = mkdtempSync(join(tmpdir(), "df-sandbox-home-"));
    try {
      mkdirSync(join(fake, ".config", "Claude"), { recursive: true });
      const link = join(fake, "looks-harmless");
      symlinkSync(join(fake, ".config", "Claude"), link);
      const r = sandbox(["start", "--root", join(link, "sandbox")], { HOME: fake, XDG_CONFIG_HOME: join(fake, ".config") });
      expect(r.code).not.toBe(0);
      expect(r.out).toMatch(/refus/i);
      expect(readdirSync(join(fake, ".config", "Claude"))).toEqual([]);
    } finally {
      rmSync(fake, { recursive: true, force: true });
    }
  });

  test("check: Desktop opens on splice's app-mode configuration in the sandbox, asks its door, shows a screen, and off takes it out", () => {
    const before = personal();
    const r = sandbox(["check"]);
    expect([r.code, r.out.slice(-3000)]).toEqual([0, expect.stringContaining("PASS")]);
    for (const step of ["configuration applied", "Desktop asked the door", "screenshot", "off"]) expect([step, r.out.includes(step)]).toEqual([step, true]);
    expect(personal()).toEqual(before);
  });
});
