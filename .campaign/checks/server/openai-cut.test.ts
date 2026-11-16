// openai-cut: an answer the upstream cuts short is an error the OpenAI client sees, never a quiet end. A streamed
// completion whose upstream breaks partway ends with an error event in OpenAI's shape and no [DONE], which the OpenAI
// SDKs raise; a JSON answer cut partway is a 502 with OpenAI's error body.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

let srv: Server;
let key = "";
beforeAll(async () => {
  srv = await Server.boot();
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

// What a client read of a body before it ended, whole or torn.
async function readAll(r: Response): Promise<string> {
  const reader = r.body!.getReader();
  const dec = new TextDecoder();
  let text = "";
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      text += dec.decode(value, { stream: true });
    }
  } catch {}
  return text;
}

const data = (text: string) =>
  text
    .split(/\r?\n/)
    .filter((l) => l.startsWith("data:"))
    .map((l) => l.slice(5).trim());

test("a stream the upstream breaks partway ends with an error event in OpenAI's shape, and no [DONE]", async () => {
  srv.up.behaviour.breakAfter = 2;
  try {
    const r = await chat({ stream: true });
    expect(r.status).toBe(200);
    const events = data(await readAll(r));
    expect(events).not.toContain("[DONE]");
    // The words that came before the break were relayed as they came.
    expect(events.some((d) => d.includes("from-a"))).toBe(true);
    const errors = events
      .filter((d) => d !== "[DONE]")
      .map((d) => JSON.parse(d) as { error?: { message?: unknown; type?: unknown } })
      .filter((d) => d.error);
    expect(errors.length).toBe(1);
    expect(typeof errors[0]!.error!.message).toBe("string");
    expect(typeof errors[0]!.error!.type).toBe("string");
  } finally {
    srv.up.behaviour.breakAfter = null;
  }
}, 60_000);

test("a JSON answer the upstream cuts partway is a 502 with OpenAI's error body", async () => {
  srv.up.behaviour.breakAfter = 2;
  try {
    const r = await chat({ stream: false });
    expect(r.status).toBe(502);
    expect(r.headers.get("content-type")).toContain("application/json");
    const j = (await r.json()) as { error?: { message?: unknown; type?: unknown } };
    expect(typeof j.error?.message).toBe("string");
    expect(typeof j.error?.type).toBe("string");
  } finally {
    srv.up.behaviour.breakAfter = null;
  }
}, 60_000);

test("an answer the upstream ends whole is relayed as before", async () => {
  const streamed = data(await readAll(await chat({ stream: true })));
  expect(streamed.at(-1)).toBe("[DONE]");
  const whole = (await (await chat({ stream: false })).json()) as { choices: { message: { content: string } }[] };
  expect(whole.choices[0]!.message.content).toBe(srv.up.reply());
}, 60_000);
