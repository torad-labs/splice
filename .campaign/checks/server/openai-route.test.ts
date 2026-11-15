// openai-route: OpenAI-compatible clients (scripts, other tools) use the same server and the same keys: a served
// command whose provider speaks openai-chat answers /v1/chat/completions and /v1/models, relayed to its upstream as
// sent, streamed without buffering, with usage always asked of the upstream and given to the client only when the
// client asked; the person's limits hold here too.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

let srv: Server;
let key = "";
beforeAll(async () => {
  srv = await Server.boot({ toml: `\n[server.limits]\ninflight_per_person = 1\n` });
  key = srv.mint("ola@example.com").key;
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

const url = (p: string) => `${srv.base}/${COMMAND}/v1/${p}`;
const chat = (body: Record<string, unknown>, k: string | undefined = key) =>
  fetch(url("chat/completions"), {
    method: "POST",
    headers: { "content-type": "application/json", ...(k ? { authorization: `Bearer ${k}` } : {}) },
    body: JSON.stringify({ model: MODEL, messages: [{ role: "user", content: "hi" }], ...body }),
  });
const chunks = (text: string) =>
  text
    .split("\n")
    .filter((l) => l.startsWith("data: "))
    .map((l) => l.slice(6).trim());

test("without a key the OpenAI route is refused 401", async () => {
  expect((await chat({}, undefined)).status).toBe(401);
  expect((await fetch(url("models"))).status).toBe(401);
});

test("models lists the command's model to a key holder", async () => {
  const r = await fetch(url("models"), { headers: { authorization: `Bearer ${key}` } });
  expect(r.status).toBe(200);
  expect(JSON.stringify(await r.json())).toContain(MODEL);
});

test("a non-streamed completion answers the upstream's message and usage", async () => {
  const r = await chat({ stream: false });
  expect(r.status).toBe(200);
  const j = (await r.json()) as { choices: { message: { content: string } }[]; usage: { prompt_tokens: number } };
  expect(j.choices[0]!.message.content).toBe(srv.up.reply());
  expect(j.usage.prompt_tokens).toBe(1200);
});

test("a streamed completion ends with [DONE]; usage is asked of the upstream but reaches only a client that asked", async () => {
  const before = srv.up.completions().length;
  const plain = await chat({ stream: true });
  expect(plain.status).toBe(200);
  expect(plain.headers.get("content-type")).toContain("text/event-stream");
  const data = chunks(await plain.text());
  expect(data.at(-1)).toBe("[DONE]");
  const parsed = data.filter((d) => d !== "[DONE]").map((d) => JSON.parse(d) as { choices: unknown[]; usage?: unknown });
  expect(parsed.every((c) => c.choices.length > 0)).toBe(true);
  const sent = srv.up.completions()[before]!.body as { stream_options?: { include_usage?: boolean } };
  expect(sent.stream_options?.include_usage).toBe(true);
  const asked = await chat({ stream: true, stream_options: { include_usage: true } });
  const withUsage = chunks(await asked.text()).filter((d) => d !== "[DONE]").map((d) => JSON.parse(d) as { usage?: { prompt_tokens: number } });
  expect(withUsage.some((c) => c.usage?.prompt_tokens === 1200)).toBe(true);
}, 60_000);

test("a streamed completion is relayed as it comes, not when it ends", async () => {
  srv.up.behaviour.words = 8;
  srv.up.behaviour.gapMs = 1_000;
  try {
    const t0 = performance.now();
    const r = await chat({ stream: true });
    const reader = r.body!.getReader();
    const dec = new TextDecoder();
    let seen = "";
    let firstWordAt = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      seen += dec.decode(value, { stream: true });
      if (!firstWordAt && seen.includes("from-a")) firstWordAt = performance.now() - t0;
    }
    expect(performance.now() - t0 - firstWordAt).toBeGreaterThan(4_000);
  } finally {
    srv.up.behaviour.words = 5;
    srv.up.behaviour.gapMs = 20;
  }
}, 60_000);

test("the person's in-flight cap holds on this route too", async () => {
  srv.up.behaviour.prefillMs = 3_000;
  try {
    const first = chat({ stream: true });
    await Bun.sleep(800);
    const second = await chat({ stream: true });
    expect(second.status).toBe(429);
    expect(Number(second.headers.get("retry-after"))).toBeGreaterThanOrEqual(1);
    const r = await first;
    expect(r.status).toBe(200);
    await r.text();
  } finally {
    srv.up.behaviour.prefillMs = 0;
  }
}, 60_000);
