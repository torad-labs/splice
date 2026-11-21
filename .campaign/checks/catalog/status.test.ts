// gw-status: `splice status` and `splice doctor` say, for every head the install still has, that custom heads are
// deprecated and will be removed in a coming release, what to type instead (`claude`, then `/model <selector>`), and
// how to retire it (`splice claude retire <command>`); `splice claude door` first only where `claude` is not on the
// Splice gateway.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { existsSync, lstatSync, rmSync } from "node:fs";
import { join } from "node:path";
import { lane, toDoor } from "../door/lib/claude.ts";
import { Provider } from "../harness/lib/capability.ts";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { claudeHeadToml } from "./lib/heads.ts";

setDefaultTimeout(240_000);

const HEADS = [
  { command: "claude-astra", selector: "claude-astra--gpt-6-astra" },
  { command: "claude-borealis", selector: "claude-borealis--borealis-large" },
];
const DEPRECATED = /deprecated and will be removed in a coming release/i;

const logins = `/tmp/claude-1000/catalog-status-${process.pid}`;
let backend: CodexBackend;
let borealis: Upstream;
let anthropic: Provider;
let s: Splice;
let bin: string;

beforeAll(async () => {
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [] }]);
  borealis = new Upstream("borealis", "borealis-large").start();
  anthropic = new Provider("anthropic").start();
  s = await Splice.create(
    claudeHeadToml({ port: await freePort(), url: anthropic.url }) +
      gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
      mockHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, model: "borealis-large" }),
    gptEnv(backend.url),
  );
  await s.start();
  // A head the install still has: its command link splice laid in the install's bin folder. `splice install` lays
  // both heads' links in the home's .local/bin, as it does on a machine; no head is launched, so no head folder holds
  // a settings.json of splice's, and the link alone is what makes each head one the install still has.
  bin = join(s.home, ".local", "bin");
  expect(s.cli(["install"]).code).toBe(0);
  for (const h of HEADS) expect([h.command, lstatSync(join(bin, h.command)).isSymbolicLink()]).toEqual([h.command, true]);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  backend?.stop();
  borealis?.stop();
  anthropic?.stop();
  rmSync(logins, { recursive: true, force: true });
});

/** The line of `verb`'s output that names `command`'s deprecation. */
function lineOf(verb: string, command: string): string {
  const r = s.cli([verb]);
  const text = (r.out + r.err).replace(/\x1b\[[0-9;]*m/g, "");
  return text.split("\n").find((l) => l.includes(command) && DEPRECATED.test(l)) ?? "";
}

describe("gw-status", () => {
  test("on the gateway, status and doctor name every head as deprecated, with what to type and how to retire it", () => {
    toDoor(s);
    for (const verb of ["status", "doctor"]) {
      for (const h of HEADS) {
        const line = lineOf(verb, h.command);
        expect([verb, h.command, line !== ""]).toEqual([verb, h.command, true]);
        expect([verb, h.command, line.includes(`/model ${h.selector}`)]).toEqual([verb, h.command, true]);
        expect([verb, h.command, line.includes(`splice claude retire ${h.command}`)]).toEqual([verb, h.command, true]);
        expect([verb, h.command, line.includes("splice claude door")]).toEqual([verb, h.command, false]);
      }
    }
  });

  test("with claude on its head, they say splice claude door comes first", () => {
    expect(s.cli(["claude", "head"]).code).toBe(0);
    expect(lane(s).lane).toBe("head");
    for (const verb of ["status", "doctor"]) {
      const line = lineOf(verb, "claude-astra");
      expect([verb, line.includes("splice claude door")]).toEqual([verb, true]);
    }
  });

  test("a head whose command link is gone and whose folder holds no settings of splice's is not listed", () => {
    toDoor(s);
    rmSync(join(bin, "claude-borealis"), { force: true });
    expect(existsSync(join(s.home, ".claude-borealis", "settings.json"))).toBe(false);
    for (const verb of ["status", "doctor"]) {
      expect([verb, "claude-borealis", lineOf(verb, "claude-borealis")]).toEqual([verb, "claude-borealis", ""]);
      expect([verb, "claude-astra", lineOf(verb, "claude-astra") !== ""]).toEqual([verb, "claude-astra", true]);
    }
    rmSync(join(bin, "claude-astra"), { force: true });
    for (const verb of ["status", "doctor"]) {
      for (const h of HEADS) expect([verb, h.command, lineOf(verb, h.command)]).toEqual([verb, h.command, ""]);
    }
  });
});
