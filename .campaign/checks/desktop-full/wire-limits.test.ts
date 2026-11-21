// df-wire-limits (a guard: this held when desktop-full was planned): Claude Desktop's usage display reads Anthropic's unified rate-limit headers. A door turn's answer
// carries them from the head's own limits: an Anthropic head's as Anthropic sent them, and a head whose provider
// reports plan windows of its own (ChatGPT's 5-hour and weekly windows) as the same family, so the display means
// something whichever head ran the turn.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { rmSync } from "node:fs";
import type { Server } from "bun";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { type Door, doorTurn, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { AnthropicStub, anthropicKeyHead } from "./lib/anthropic.ts";

setDefaultTimeout(120_000);

const ASTRA = "claude-astra--astra-large";
const LYRA = "claude-lyra--gpt-6-lyra";
const ANT = "claude-ant--claude-opus-5-5";

// ChatGPT's own plan-window headers on each answer: 31.5% of the 5-hour window and 12% of the weekly one used.
const CODEX_WINDOWS = {
  "x-codex-primary-used-percent": "31.5",
  "x-codex-primary-window-minutes": "300",
  "x-codex-primary-reset-after-seconds": "3600",
  "x-codex-secondary-used-percent": "12",
  "x-codex-secondary-window-minutes": "10080",
  "x-codex-secondary-reset-after-seconds": "86400",
};
const ANTHROPIC_WINDOWS = {
  "anthropic-ratelimit-unified-status": "allowed",
  "anthropic-ratelimit-unified-5h-utilization": "0.42",
  "anthropic-ratelimit-unified-5h-reset": String(Math.floor(Date.now() / 1000) + 7200),
  "anthropic-ratelimit-unified-7d-utilization": "0.17",
  "anthropic-ratelimit-unified-7d-reset": String(Math.floor(Date.now() / 1000) + 400000),
};

const logins = `/tmp/claude-1000/df-wire-limits-${process.pid}`;
let astra: Upstream;
let backend: CodexBackend;
let proxy: Server<unknown>;
let ant: AnthropicStub;
let s: Splice;
let d: Door;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  backend = CodexBackend.start([{ slug: "gpt-6-lyra", tiers: [] }]);
  // The ChatGPT backend with its plan windows on every answer.
  proxy = Bun.serve({
    port: 0,
    hostname: "127.0.0.1",
    idleTimeout: 0,
    async fetch(req) {
      const u = new URL(req.url);
      const r = await fetch(`${backend.url}${u.pathname}${u.search}`, { method: req.method, headers: req.headers, body: req.method === "GET" ? undefined : await req.arrayBuffer() });
      const h = new Headers(r.headers);
      for (const [k, v] of Object.entries(CODEX_WINDOWS)) h.set(k, v);
      return new Response(r.body, { status: r.status, headers: h });
    },
  });
  const proxied = `http://127.0.0.1:${proxy.port}`;
  ant = new AnthropicStub("ant").start();
  ant.headers = ANTHROPIC_WINDOWS;
  s = await Splice.create(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }) +
      gptHead({ key: "lyra", command: "claude-lyra", port: await freePort(), backend: proxied, model: "gpt-6-lyra", dir: logins }) +
      anthropicKeyHead({ key: "ant", command: "claude-ant", port: await freePort(), url: ant.url, model: "claude-opus-5-5" }),
    { ...gptEnv(proxied), CLAUDEX_QUOTA_POLL: "off" },
  );
  await s.start();
  d = turnOn(s, "--model", ASTRA);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  backend?.stop();
  proxy?.stop(true);
  ant?.stop();
  rmSync(logins, { recursive: true, force: true });
});

describe("df-wire-limits", () => {
  test("an Anthropic head's unified limits reach Desktop with Anthropic's values", async () => {
    const t = await doorTurn(d.url, { key: d.key, model: ANT, session: crypto.randomUUID() });
    expect(t.status).toBe(200);
    for (const [k, v] of Object.entries(ANTHROPIC_WINDOWS)) {
      const got = t.headers.get(k);
      if (k.endsWith("-utilization")) expect([k, Number(got)]).toEqual([k, Number(v)]);
      else expect([k, got]).toEqual([k, v]);
    }
  });

  test("a ChatGPT head's own plan windows reach Desktop as the unified family", async () => {
    // the first turn teaches the head its windows; the next answer carries them
    await doorTurn(d.url, { key: d.key, model: LYRA, session: crypto.randomUUID() });
    const t = await doorTurn(d.url, { key: d.key, model: LYRA, session: crypto.randomUUID() });
    expect(t.status).toBe(200);
    expect(t.headers.get("anthropic-ratelimit-unified-status")).toBe("allowed");
    expect(Number(t.headers.get("anthropic-ratelimit-unified-5h-utilization"))).toBeCloseTo(0.315, 3);
    expect(Number(t.headers.get("anthropic-ratelimit-unified-7d-utilization"))).toBeCloseTo(0.12, 3);
    const reset = Number(t.headers.get("anthropic-ratelimit-unified-5h-reset"));
    expect(reset).toBeGreaterThan(Date.now() / 1000);
    expect(reset).toBeLessThan(Date.now() / 1000 + 3700);
  });

  test("a head that reports no windows says none, rather than a made-up one", async () => {
    const t = await doorTurn(d.url, { key: d.key, model: ASTRA, session: crypto.randomUUID() });
    expect(t.status).toBe(200);
    expect(t.headers.get("anthropic-ratelimit-unified-5h-utilization")).toBeNull();
  });
});
