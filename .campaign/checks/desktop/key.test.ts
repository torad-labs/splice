// desktop-key: the door has a key of its own. It opens the door and nothing else, no other key of splice's opens the
// door, it is never sent to a provider, it outlives `splice rotate-key`, and `splice desktop rotate-key` replaces it
// and says the new one to set in Desktop.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { statSync } from "node:fs";
import { Splice, freePort, turn } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { launch, sessionKey } from "../speed/lib/session.ts";
import { type Door, doorTurn, rotateKey, settingsFile, slottedHead, turnOn } from "./lib/desktop.ts";

const MODEL = "claude-astra--astra-large";

let up: Upstream;
let s: Splice;
let port = 0;
let d: Door;

beforeAll(async () => {
  up = new Upstream("astra", "astra-large").start();
  port = await freePort();
  s = await Splice.boot(slottedHead({ key: "astra", command: "claude-astra", port, url: up.url, big: "astra-large", small: "astra-small" }));
  d = turnOn(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
});

/** A key that must not open the door: refused in Anthropic's error shape, naming `splice desktop`. */
async function refused(presented: string | undefined): Promise<void> {
  const before = up.completions().length;
  const t = await doorTurn(d.url, { key: presented, model: MODEL, session: crypto.randomUUID() });
  expect(t.status).toBe(401);
  expect(t.error?.type).toBe("authentication_error");
  expect(t.error?.message).toContain("splice desktop");
  expect(up.completions().length).toBe(before);
}

describe("desktop-key", () => {
  test("the desktop key opens the door", async () => {
    const t = await doorTurn(d.url, { key: d.key, model: MODEL, session: crypto.randomUUID() });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(up.reply());
  });

  test("no key and a wrong key are refused, naming splice desktop", async () => {
    await refused(undefined);
    await refused("not-the-desktop-key");
    await refused(`${d.key}x`);
  });

  test("splice's other keys do not open the door", async () => {
    await refused(sessionKey(await launch(s, "claude-astra")));
    await refused(s.mgmtKey());
  });

  test("the desktop key opens nothing but the door", async () => {
    const before = up.completions().length;
    for (const bearer of [true, false]) {
      const t = await turn(`http://127.0.0.1:${port}/v1/messages`, { key: d.key, bearer, model: "astra-large" });
      expect(t.status).toBe(401);
    }
    expect(up.completions().length).toBe(before);
    const control = await fetch(`http://127.0.0.1:${s.controlPort}/api/perf/turns`, { headers: { authorization: `Bearer ${d.key}` } });
    expect([401, 403]).toContain(control.status);
  });

  test("it is never sent to the provider", async () => {
    const before = up.seen.length;
    expect((await doorTurn(d.url, { key: d.key, model: MODEL, session: crypto.randomUUID() })).status).toBe(200);
    const sent = up.seen.slice(before);
    expect(sent.length).toBeGreaterThan(0);
    for (const r of sent) expect(JSON.stringify([r.headers, r.body])).not.toContain(d.key);
  });

  test("splice's own copy is kept for its owner alone, and the daemon's log never holds it", () => {
    const holders = s.files().filter((f) => f.bytes.includes(d.key) && f.path !== settingsFile(s));
    expect(holders.length).toBeGreaterThan(0);
    for (const f of holders) {
      expect(f.path.endsWith("daemon.log")).toBe(false);
      expect(statSync(f.path).mode & 0o777).toBe(0o600);
    }
  });

  test("splice rotate-key leaves it working", async () => {
    expect(s.cli(["rotate-key"]).code).toBe(0);
    expect(turnOn(s).key).toBe(d.key);
    expect((await doorTurn(d.url, { key: d.key, model: MODEL, session: crypto.randomUUID() })).status).toBe(200);
  });

  test("splice desktop rotate-key replaces it: it says the new key to set, and the old one is refused", async () => {
    const next = rotateKey(s).key;
    expect(next).not.toBe(d.key);
    await refused(d.key);
    expect((await doorTurn(d.url, { key: next, model: MODEL, session: crypto.randomUUID() })).status).toBe(200);
  });
});
