// df-wire-answers: what Claude Desktop expects of a gateway's answers, on every head a door turn reaches
// (https://code.claude.com/docs/en/llm-gateway-protocol): usage with the cache write split into its 5-minute and
// 1-hour parts, `cache_control` and Anthropic's headers passed to an Anthropic head as sent, a retry-after in whole
// seconds and `x-should-retry` on a refusal, an Anthropic head's error body whole, and count_tokens on every head.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { rmSync } from "node:fs";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { type Door, countTokens, doorTurn, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { AnthropicStub, anthropicKeyHead } from "./lib/anthropic.ts";

setDefaultTimeout(120_000);

const ASTRA = "claude-astra--astra-large";
const LYRA = "claude-lyra--gpt-6-lyra";
const ANT = "claude-ant--claude-opus-5-5";

const logins = `/tmp/claude-1000/df-wire-answers-${process.pid}`;
let astra: Upstream;
let backend: CodexBackend;
let ant: AnthropicStub;
let s: Splice;
let d: Door;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  backend = CodexBackend.start([{ slug: "gpt-6-lyra", tiers: [] }]);
  ant = new AnthropicStub("ant").start();
  s = await Splice.create(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }) +
      gptHead({ key: "lyra", command: "claude-lyra", port: await freePort(), backend: backend.url, model: "gpt-6-lyra", dir: logins }) +
      anthropicKeyHead({ key: "ant", command: "claude-ant", port: await freePort(), url: ant.url, model: "claude-opus-5-5" }),
    gptEnv(backend.url),
  );
  await s.start();
  d = turnOn(s, "--model", ASTRA);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  backend?.stop();
  ant?.stop();
  rmSync(logins, { recursive: true, force: true });
});

type Usage = Record<string, unknown> & { cache_creation?: Record<string, unknown> };

/** The usage blocks an answer carries: a JSON answer's, or a stream's message_start and message_delta. */
function usages(t: Awaited<ReturnType<typeof doorTurn>>): Usage[] {
  if (!t.events.length) return [((JSON.parse(t.body) as { usage?: Usage }).usage ?? {}) as Usage];
  const out: Usage[] = [];
  for (const e of t.events) {
    const u = ((e.data?.message as { usage?: Usage } | undefined)?.usage ?? (e.data?.usage as Usage | undefined)) as Usage | undefined;
    if (e.event === "message_start" && u) out.push(u);
  }
  return out;
}

/** A raw door request with headers and a body of the caller's own. */
function raw(body: unknown, headers: Record<string, string> = {}): Promise<Response> {
  return fetch(`${d.url}/v1/messages?beta=true`, {
    method: "POST",
    headers: { "content-type": "application/json", "anthropic-version": "2023-06-01", authorization: `Bearer ${d.key}`, ...headers },
    body: JSON.stringify(body),
  });
}

describe("df-wire-answers", () => {
  test("every head's answer carries usage.cache_creation with its 5-minute and 1-hour parts, streamed or not", async () => {
    for (const model of [ASTRA, LYRA, ANT]) {
      for (const stream of [true, false]) {
        const t = await doorTurn(d.url, { key: d.key, model, stream, session: crypto.randomUUID() });
        expect([model, stream, t.status]).toEqual([model, stream, 200]);
        const us = usages(t);
        expect([model, stream, us.length > 0]).toEqual([model, stream, true]);
        for (const u of us) {
          const c = u.cache_creation ?? {};
          expect([model, stream, Number.isInteger(c.ephemeral_5m_input_tokens), Number.isInteger(c.ephemeral_1h_input_tokens)]).toEqual([model, stream, true, true]);
          const split = Number(c.ephemeral_5m_input_tokens) + Number(c.ephemeral_1h_input_tokens);
          expect([model, stream, split]).toEqual([model, stream, Number(u.cache_creation_input_tokens ?? 0)]);
        }
      }
    }
  });

  test("an Anthropic head is sent cache_control, unknown body fields and Anthropic's headers exactly as Desktop sent them", async () => {
    const marker = `df-wire-${crypto.randomUUID()}`;
    const body = {
      model: ANT,
      max_tokens: 64,
      stream: false,
      system: [{ type: "text", text: `system ${marker}`, cache_control: { type: "ephemeral", ttl: "1h" } }],
      messages: [{ role: "user", content: [{ type: "text", text: "hello", cache_control: { type: "ephemeral" } }] }],
      context_management: { edits: [{ type: "clear_tool_uses_20250919" }] },
    };
    const r = await raw(body, { "anthropic-beta": "context-management-2025-06-27,some-future-beta-2027-01-01" });
    expect(r.status).toBe(200);
    const seen = ant.turns().find((t) => t.raw.includes(marker))!;
    expect(seen.body?.system).toEqual(body.system);
    expect(seen.body?.messages).toEqual(body.messages);
    expect(seen.body?.context_management).toEqual(body.context_management);
    expect(seen.headers["anthropic-beta"] ?? "").toContain("some-future-beta-2027-01-01");
    expect(seen.headers["anthropic-beta"] ?? "").toContain("context-management-2025-06-27");
    expect(seen.headers["anthropic-version"]).toBe("2023-06-01");
  });

  test("an Anthropic head's refusal reaches Desktop with its status and body whole, streamed or not", async () => {
    // Claude Code streams its turns, so a streamed turn's refusal is the one Desktop meets most (round 4's feature
    // review: streamed 400, 403 and 413 came back rewritten as 400 "[splice] ..." with request_id gone).
    for (const status of [400, 403, 413]) {
      for (const stream of [false, true]) {
        const body = JSON.stringify({
          type: "error",
          error: { type: "invalid_request_error", message: `anthropic's own words for ${status}: prompt is too long: 210000 tokens > 200000 maximum` },
          request_id: `req_df_wire_${status}_${stream}`,
        });
        ant.next.length = 0;
        // refused every time it is asked, so whatever splice retries itself, the answer is the refusal
        for (let i = 0; i < 20; i++) ant.next.push({ status, body });
        const t = await doorTurn(d.url, { key: d.key, model: ANT, stream, session: crypto.randomUUID() });
        ant.next.length = 0;
        let got: unknown = t.body.slice(0, 300);
        try {
          got = JSON.parse(t.body);
        } catch {}
        expect([status, stream, t.status, got]).toEqual([status, stream, status, JSON.parse(body)]);
        expect([status, stream, t.headers.get("x-should-retry")]).toEqual([status, stream, "false"]);
      }
    }
  });

  test("an overload says when to retry in whole seconds, and that a retry is worth it", async () => {
    // refused every time it is asked, so whatever splice retries itself, the answer is the overload
    for (let i = 0; i < 20; i++) ant.next.push({ status: 529, body: JSON.stringify({ type: "error", error: { type: "overloaded_error", message: "Overloaded" } }), headers: { "retry-after": "6.2" } });
    const t = await doorTurn(d.url, { key: d.key, model: ANT, stream: false, session: crypto.randomUUID() });
    expect(t.status).toBeGreaterThanOrEqual(500);
    expect(t.headers.get("retry-after") ?? "").toMatch(/^\d+$/);
    expect(Number(t.headers.get("retry-after"))).toBeGreaterThanOrEqual(6);
    expect(t.headers.get("x-should-retry")).toBe("true");
    ant.next.length = 0;
  });

  test("count_tokens is answered on every head", async () => {
    ant.next.length = 0;
    for (const model of [ASTRA, LYRA, ANT]) {
      const c = await countTokens(d.url, { key: d.key, model, content: "Count these words, please." });
      expect([model, c.status, c.input_tokens > 0]).toEqual([model, 200, true]);
    }
  });
});
