// modes-switch: `PUT /api/modes/<name>` turns a mode on or off for every head (`on`) and per head (`heads`), writing
// `[modes.<name>]` in splice.toml the way splice's other writes do (the rest of the file and its comments kept), and
// the next request of a session follows it, with no restart. null clears a key, and a table left with no key is
// removed. A mode splice did not find answers 404; a head splice.toml does not declare answers 400 and writes nothing.
// What was written holds after a restart.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync } from "node:fs";
import { AgentProvider } from "../harness/lib/agent.ts";
import { KEYS, first, head } from "../harness/lib/capability.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { TOOL, mark, requireTemplate } from "./lib/modes.ts";

setDefaultTimeout(120_000);

let up: AgentProvider;
let s: Splice;
let astra: Launched;
let borealis: Launched;

const COMMENT = "# the person's own note, which a write keeps";

beforeAll(async () => {
  requireTemplate();
  up = new AgentProvider("the provider").start();
  s = await Splice.boot(
    `${COMMENT}\n` +
      head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }) +
      head({ key: "borealis", command: "claude-borealis", port: await freePort(), url: up.url }),
    KEYS,
  );
  astra = await launch(s, "claude-astra");
  borealis = await launch(s, "claude-borealis");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
});

/** Whether a new first turn on `l` is offered the template's tool. */
async function offered(l: Launched): Promise<boolean> {
  const { session, sent } = await first(l, `Plan ${mark()}.`);
  expect(sent.status).toBe(200);
  const tools = (up.of(session).at(-1)!.body.tools as { name: string }[] | undefined) ?? [];
  return tools.some((t) => t.name === TOOL);
}

async function put(name: string, body: unknown): Promise<{ status: number; json: Record<string, unknown> }> {
  const r = await s.api(`/api/modes/${name}`, { method: "PUT", body: JSON.stringify(body) });
  return { status: r.status, json: (await r.json().catch(() => ({}))) as Record<string, unknown> };
}

const toml = () => readFileSync(s.config, "utf8");

describe("modes-switch", () => {
  test("a head switched on is offered the mode on its next request, and the file says so", async () => {
    expect(await offered(astra)).toBe(false);
    const r = await put("template", { heads: { astra: true } });
    expect(r.status).toBe(200);
    expect(r.json.name).toBe("template");
    expect((r.json.heads as Record<string, unknown>).astra).toEqual({ on: true, from: "head" });
    expect(toml()).toMatch(/^\[modes\.template\]\s*$/m);
    expect(toml()).toMatch(/\bastra\s*=\s*true\b/);
    expect(toml()).toContain(COMMENT);
    expect(await offered(astra)).toBe(true);
    expect(await offered(borealis)).toBe(false);
  });

  test("clearing the head's entry removes the table, and the head follows", async () => {
    const r = await put("template", { heads: { astra: null } });
    expect(r.status).toBe(200);
    expect(toml()).not.toContain("[modes.template]");
    expect(toml()).toContain(COMMENT);
    expect(await offered(astra)).toBe(false);
  });

  test("on for every head reaches every head, and holds after a restart", async () => {
    const r = await put("template", { on: true });
    expect(r.status).toBe(200);
    expect(r.json.on).toEqual({ value: true, from: "splice.toml" });
    expect(toml()).toMatch(/^\[modes\.template\]\s*$/m);
    expect(toml()).toMatch(/^on\s*=\s*true\s*$/m);
    expect(await offered(astra)).toBe(true);
    expect(await offered(borealis)).toBe(true);
    await s.restart();
    astra = await launch(s, "claude-astra");
    expect(await offered(astra)).toBe(true);
  });

  test("a mode splice did not find answers 404, and a head splice.toml does not declare answers 400; neither writes", async () => {
    const before = toml();
    expect((await put("nosuchmode", { on: true })).status).toBe(404);
    const bad = await put("template", { heads: { nohead: true } });
    expect(bad.status).toBe(400);
    expect(String(bad.json.error ?? "")).toContain("nohead");
    expect(toml()).toBe(before);
  });
});
