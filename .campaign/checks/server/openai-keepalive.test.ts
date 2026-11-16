// openai-keepalive: with [server] keepalive_seconds, a streamed OpenAI completion whose upstream is still prefilling
// has its response headers within 2 s and an SSE comment at least every keepalive_seconds, which OpenAI clients skip,
// then the whole answer ending with [DONE]; a refusal from the upstream after the stream opened arrives as an error
// event in OpenAI's shape. A completion that asks for no stream still answers one JSON body.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

let srv: Server;
let key = "";
beforeAll(async () => {
  srv = await Server.boot({ server: `keepalive_seconds = 3\n` });
  key = srv.mint("ola@example.com").key;
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

const chat = (body: Record<string, unknown>) =>
  fetch(`${srv.base}/${COMMAND}/v1/chat/completions`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${key}` },
    body: JSON.stringify({ model: MODEL, messages: [{ role: "user", content: "hi" }], ...body }),
  });

// A streamed completion read to its end, with when its first byte came and its longest silence.
async function streamed(body: Record<string, unknown> = {}) {
  const t0 = performance.now();
  const r = await chat({ stream: true, ...body });
  const reader = r.body!.getReader();
  const dec = new TextDecoder();
  let text = "";
  let first = 0;
  let last = 0;
  let gap = 0;
  for (;;) {
    const { done, value } = await reader.read();
    const now = performance.now();
    if (done) break;
    if (!first) first = now - t0;
    else gap = Math.max(gap, now - last);
    last = now;
    text += dec.decode(value, { stream: true });
  }
  const lines = text.split(/\r?\n/);
  const data = lines.filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trim());
  return { status: r.status, first, gap, comments: lines.filter((l) => l.startsWith(":")).length, data };
}

const reply = (data: string[]) =>
  data
    .filter((d) => d !== "[DONE]")
    .map((d) => JSON.parse(d) as { choices?: { delta?: { content?: string } }[] })
    .map((c) => c.choices?.[0]?.delta?.content ?? "")
    .join("");

test("a 15 s prefill: headers within 2 s, a comment at least every 3 s, then the whole answer and [DONE]", async () => {
  srv.up.behaviour.prefillMs = 15_000;
  try {
    const s = await streamed();
    expect(s.status).toBe(200);
    expect(s.first).toBeLessThan(2_000);
    expect(s.gap).toBeLessThan(4_500);
    expect(s.comments).toBeGreaterThanOrEqual(3);
    expect(reply(s.data)).toBe(srv.up.reply());
    expect(s.data.at(-1)).toBe("[DONE]");
  } finally {
    srv.up.behaviour.prefillMs = 0;
  }
}, 120_000);

test("a refusal from the upstream after the stream opened arrives as an error event in OpenAI's shape", async () => {
  srv.up.behaviour.refuseAfterMs = 4_000;
  srv.up.behaviour.status = 503;
  try {
    const s = await streamed();
    expect(s.status).toBe(200);
    expect(s.data).not.toContain("[DONE]");
    const errors = s.data.map((d) => JSON.parse(d) as { error?: { message?: string } }).filter((d) => d.error);
    expect(errors.length).toBe(1);
    expect(errors[0]!.error!.message).toContain("says 503");
  } finally {
    srv.up.behaviour.refuseAfterMs = 0;
    srv.up.behaviour.status = null;
  }
}, 60_000);

test("a completion that asks for no stream, behind a 6 s prefill, answers one JSON body", async () => {
  srv.up.behaviour.prefillMs = 6_000;
  try {
    const r = await chat({ stream: false });
    expect(r.status).toBe(200);
    const j = (await r.json()) as { choices: { message: { content: string } }[] };
    expect(j.choices[0]!.message.content).toBe(srv.up.reply());
  } finally {
    srv.up.behaviour.prefillMs = 0;
  }
}, 60_000);
