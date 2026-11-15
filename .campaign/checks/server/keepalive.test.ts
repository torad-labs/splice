// prefill-keepalive: while a long prompt is still being prefilled, a streamed turn already has its headers and an
// SSE keepalive at least every keepalive_seconds, so no proxy on the way times it out; tokens are relayed as they
// come, never buffered; a non-streamed turn still answers one well-formed JSON body.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { Server } from "./lib/server.ts";

let srv: Server;
let key = "";
beforeAll(async () => {
  srv = await Server.boot({ server: `keepalive_seconds = 5\n` });
  key = srv.mint("lia@example.com").key;
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

test("a 25 s prefill: headers within 2 s, a keepalive at least every 5 s, then the whole answer", async () => {
  srv.up.behaviour.prefillMs = 25_000;
  try {
    const t = await srv.turn(key);
    expect(t.status).toBe(200);
    expect(t.firstByteMs).toBeLessThan(2_000);
    expect(t.longestGapMs).toBeLessThan(6_500);
    expect(t.reply).toBe(srv.up.reply());
    expect(t.events.at(-1)?.event).toBe("message_stop");
  } finally {
    srv.up.behaviour.prefillMs = 0;
  }
}, 120_000);

test("tokens are relayed as the upstream sends them, not when it ends", async () => {
  srv.up.behaviour.words = 8;
  srv.up.behaviour.gapMs = 1_000;
  try {
    const t = await srv.turn(key);
    expect(t.status).toBe(200);
    const first = t.arrivals.find((a) => a.text.includes("from-a"));
    expect(first).toBeDefined();
    expect(t.totalMs - first!.ms).toBeGreaterThan(4_000);
  } finally {
    srv.up.behaviour.words = 5;
    srv.up.behaviour.gapMs = 20;
  }
}, 120_000);

test("a non-streamed turn behind a 12 s prefill answers one JSON body with the reply", async () => {
  srv.up.behaviour.prefillMs = 12_000;
  try {
    const t = await srv.turn(key, { stream: false });
    expect(t.status).toBe(200);
    const body = JSON.parse(t.body) as { type: string; content: { type: string; text: string }[] };
    expect(body.type).toBe("message");
    expect(body.content.map((c) => c.text).join("")).toBe(srv.up.reply());
  } finally {
    srv.up.behaviour.prefillMs = 0;
  }
}, 120_000);
