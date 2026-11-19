// df-wire-door: the door takes Claude Desktop as a gateway client. Desktop sends the key as `x-api-key` as well as a
// Bearer, probes the address's root before it calls the door, and makes non-streamed one-shot calls of its own
// (its connection test, titles, Claude in Chrome). Each is answered like any door turn.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, turn } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Door, countTokens, slottedHead, turnOn } from "../desktop/lib/desktop.ts";

setDefaultTimeout(120_000);

const ASTRA_BIG = "claude-astra--astra-large";

let astra: Upstream;
let s: Splice;
let d: Door;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  s = await Splice.boot(slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }));
  d = turnOn(s, "--model", ASTRA_BIG);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
});

const origin = () => `http://127.0.0.1:${s.controlPort}`;

describe("df-wire-door", () => {
  test("the desktop key sent as x-api-key opens the door, for a turn and for a count", async () => {
    const before = astra.completions().length;
    const t = await turn(`${d.url}/v1/messages?beta=true`, { key: d.key, model: ASTRA_BIG });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(astra.reply());
    expect(astra.completions().length).toBe(before + 1);
    const r = await fetch(`${d.url}/v1/messages/count_tokens`, {
      method: "POST",
      headers: { "content-type": "application/json", "anthropic-version": "2023-06-01", "x-api-key": d.key },
      body: JSON.stringify({ model: ASTRA_BIG, messages: [{ role: "user", content: "Say hello." }] }),
    });
    expect(r.status).toBe(200);
    expect(Number(((await r.json()) as { input_tokens?: number }).input_tokens)).toBeGreaterThan(0);
  });

  test("a wrong key as x-api-key is refused 401 in Anthropic's shape, naming splice desktop, and nothing is sent", async () => {
    const before = astra.seen.length;
    const t = await turn(`${d.url}/v1/messages?beta=true`, { key: "sk-wrong", model: ASTRA_BIG });
    expect(t.status).toBe(401);
    expect(t.error?.type).toBe("authentication_error");
    expect(t.error?.message).toContain("splice desktop");
    expect(astra.seen.length).toBe(before);
  });

  test("the management key as x-api-key does not open the door", async () => {
    const t = await turn(`${d.url}/v1/messages?beta=true`, { key: s.mgmtKey(), model: ASTRA_BIG });
    expect(t.status).toBe(401);
  });

  test("the Bearer still opens the door", async () => {
    const c = await countTokens(d.url, { key: d.key, model: ASTRA_BIG, content: "Say hello." });
    expect(c.status).toBe(200);
  });

  test("Desktop's reachability probe of the address's root is answered 200, not redirected", async () => {
    for (const method of ["GET", "HEAD"]) {
      const r = await fetch(`${origin()}/`, { method, headers: { accept: "*/*" }, redirect: "manual" });
      expect([method, r.status]).toEqual([method, 200]);
    }
  });

  test("a browser at the root still lands on the console", async () => {
    const r = await fetch(`${origin()}/`, { headers: { accept: "text/html,application/xhtml+xml" }, redirect: "manual" });
    expect(r.status).toBeGreaterThanOrEqual(300);
    expect(r.status).toBeLessThan(400);
  });

  test("a non-streamed one-shot of Desktop's own is answered as one Anthropic message, on a selector and on a tier", async () => {
    for (const model of [ASTRA_BIG, "claude-haiku-5-5"]) {
      const before = astra.completions().length;
      const r = await fetch(`${d.url}/v1/messages`, {
        method: "POST",
        headers: { "content-type": "application/json", "anthropic-version": "2023-06-01", "x-api-key": d.key },
        body: JSON.stringify({ model, max_tokens: 64, messages: [{ role: "user", content: "Write a five-word title for: fix the build." }] }),
      });
      expect([model, r.status]).toEqual([model, 200]);
      expect(r.headers.get("content-type") ?? "").toContain("application/json");
      const j = (await r.json()) as { type?: string; content?: { type: string; text?: string }[] };
      expect(j.type).toBe("message");
      expect((j.content ?? []).filter((c) => c.type === "text").map((c) => c.text).join("")).toBe(astra.reply());
      expect(astra.completions().length).toBe(before + 1);
    }
  });
});
