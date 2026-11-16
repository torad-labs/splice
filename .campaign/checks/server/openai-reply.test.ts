// openai-reply-headers: of the provider's answer, the client gets what it is for and nothing of the provider's own.
// On the OpenAI route the client gets the upstream's status and body as relayed, its content-type and, on a refusal,
// its Retry-After, and none of the provider's own headers (a cookie it sets, its request ids); a finish chunk that
// carries usage reaches a client that didn't ask for usage without it.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

const OWN = { "set-cookie": "upstream_session=s3cr3t-cookie; Path=/; HttpOnly", "x-upstream-request-id": "req-upstream-77" };

let srv: Server;
let key = "";
beforeAll(async () => {
  srv = await Server.boot({ upstream: { replyHeaders: { ...OWN } } });
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

const chunks = (text: string) =>
  text
    .split(/\r?\n/)
    .filter((l) => l.startsWith("data:"))
    .map((l) => l.slice(5).trim())
    .filter((d) => d !== "[DONE]")
    .map((d) => JSON.parse(d) as { usage?: { prompt_tokens?: number } | null });

const own = (h: Headers) => [...h.keys()].filter((k) => k === "set-cookie" || k === "x-upstream-request-id" || (h.get(k) ?? "").includes("s3cr3t-cookie"));

test("a completion, streamed or not, reaches the client without the provider's cookie or request id", async () => {
  const whole = await chat({ stream: false });
  expect(whole.status).toBe(200);
  expect(whole.headers.get("content-type")).toContain("application/json");
  expect(own(whole.headers)).toEqual([]);
  await whole.text();
  const streamed = await chat({ stream: true });
  expect(streamed.status).toBe(200);
  expect(streamed.headers.get("content-type")).toContain("text/event-stream");
  expect(own(streamed.headers)).toEqual([]);
  await streamed.text();
}, 60_000);

test("a refusal from the provider keeps its status, its body and its Retry-After, and not its cookie", async () => {
  srv.up.behaviour.status = 429;
  srv.up.behaviour.replyHeaders = { ...OWN, "retry-after": "7" };
  try {
    const r = await chat({ stream: false });
    expect(r.status).toBe(429);
    expect(r.headers.get("retry-after")).toBe("7");
    expect(own(r.headers)).toEqual([]);
    expect(((await r.json()) as { error: { message: string } }).error.message).toContain("says 429");
  } finally {
    srv.up.behaviour.status = null;
    srv.up.behaviour.replyHeaders = { ...OWN };
  }
}, 60_000);

test("usage the upstream puts on its finish chunk reaches only a client that asked for it", async () => {
  srv.up.behaviour.usageOnFinish = true;
  try {
    const plain = chunks(await (await chat({ stream: true })).text());
    expect(plain.length).toBeGreaterThan(0);
    expect(plain.filter((c) => c.usage)).toEqual([]);
    const asked = chunks(await (await chat({ stream: true, stream_options: { include_usage: true } })).text());
    expect(asked.some((c) => c.usage?.prompt_tokens === 1200)).toBe(true);
  } finally {
    srv.up.behaviour.usageOnFinish = false;
  }
}, 60_000);
