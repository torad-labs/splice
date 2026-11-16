// desktop-terminal: with Desktop on, the terminal is as it was. A head started from a shell that carries the
// file's switch, as a shell inside a Code tab session does, still runs on its head, and splice writes one file in
// the `claude` command's folder.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { readdirSync } from "node:fs";
import { join } from "node:path";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ask, launch } from "../speed/lib/session.ts";
import { type Door, doorTurn, fileEnv, recipe, rows, slottedHead, turnOn } from "./lib/desktop.ts";

/** Claude Code's provider switches and what goes with them: any of these reaching a session sends it past its head. */
const SWITCHES = [
  "CLAUDE_CODE_USE_BEDROCK",
  "CLAUDE_CODE_USE_MANTLE",
  "CLAUDE_CODE_USE_VERTEX",
  "CLAUDE_CODE_USE_FOUNDRY",
  "ANTHROPIC_BEDROCK_BASE_URL",
  "ANTHROPIC_BEDROCK_MANTLE_BASE_URL",
  "AWS_BEARER_TOKEN_BEDROCK",
];

let astra: Upstream;
let s: Splice;
let d: Door;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  s = await Splice.boot(slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }));
  d = turnOn(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
});

/** Every file under `dir`, by its path from it. */
function under(dir: string): string[] {
  const out: string[] = [];
  const walk = (at: string, prefix: string) => {
    for (const e of readdirSync(at, { withFileTypes: true })) {
      if (e.isDirectory()) walk(join(at, e.name), `${prefix}${e.name}/`);
      else out.push(`${prefix}${e.name}`);
    }
  };
  walk(dir, "");
  return out.sort();
}

describe("desktop-terminal", () => {
  test("a launch's recipe unsets Claude Code's provider switches and the door's own variables", async () => {
    const r = await recipe(s, "claude-astra");
    for (const name of SWITCHES) expect([name, r.unset.includes(name)]).toEqual([name, true]);
    for (const name of SWITCHES) expect([name, name in r.env]).toEqual([name, false]);
  });

  test("a head started from a shell inside a Code tab session runs on its head, not through the door", async () => {
    // the shell of a Code tab session carries the file's env block
    const carried = fileEnv(s);
    const saved = Object.fromEntries(Object.keys(carried).map((k) => [k, process.env[k]]));
    Object.assign(process.env, carried);
    try {
      const l = await launch(s, "claude-astra");
      for (const name of SWITCHES) expect([name, l.env[name]]).toEqual([name, undefined]);
      expect(l.dir).not.toBe(join(s.home, ".claude"));
      const session = crypto.randomUUID();
      const before = astra.completions().length;
      expect((await ask(l, session, "from a shell in the Code tab")).status).toBe(200);
      expect(astra.completions().length).toBe(before + 1);
      let found = await rows(s, "astra", session);
      await until("the head's row of the session", async () => (found = await rows(s, "astra", session)).length > 0);
      expect(found[0]!.door ?? null).toBeNull();
    } finally {
      for (const [k, v] of Object.entries(saved)) {
        if (v === undefined) delete process.env[k];
        else process.env[k] = v;
      }
    }
  });

  test("a head with a config folder of its own carries nothing of the door in its settings", async () => {
    const l = await launch(s, "claude-astra");
    const env = (l.settings.env ?? {}) as Record<string, string>;
    for (const name of SWITCHES) expect([name, env[name]]).toEqual([name, undefined]);
    expect(JSON.stringify(l.settings)).not.toContain(d.key);
  });

  test("splice writes one file in the claude command's folder, the settings file", async () => {
    expect((await doorTurn(d.url, { key: d.key, model: d.model, session: crypto.randomUUID() })).status).toBe(200);
    await launch(s, "claude-astra");
    expect(s.cli(["desktop", "rotate-key"]).code).toBe(0);
    expect(under(join(s.home, ".claude"))).toEqual(["settings.json"]);
    expect(s.cli(["desktop", "off"]).code).toBe(0);
    expect(under(join(s.home, ".claude")).filter((f) => f !== "settings.json")).toEqual([]);
  });
});
