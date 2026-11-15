// ledger-rows: one durable, content-free row per request a person sends the server, classed honestly: an upstream
// that breaks or fails is 502, a client that leaves is 499 apart from errors, a refusal keeps its status, an
// OpenAI-style stream without [DONE] is cut off; rates come from llama-server's timings.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { json, until } from "./lib/harness.ts";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

interface Row {
  at: string;
  person: string;
  team: string | null;
  device: string;
  command: string;
  model: string;
  route: string;
  upstream: string | null;
  status: number;
  outcome: string;
  prompt_tokens: number;
  cached_tokens: number;
  completion_tokens: number;
  ttft_ms: number | null;
  duration_ms: number;
  prefill_tps: number | null;
  decode_tps: number | null;
  cost_usd: number | null;
}

let srv: Server;
beforeAll(async () => {
  srv = await Server.boot({ toml: `\n[server.limits]\ninflight_per_person = 1\n` });
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

const rowsOf = (person: string) => json<{ rows: Row[] }>(srv.cli(["server", "usage", "rows", "--json", "--since", "1h", "--person", person])).rows;
const lastOf = async (person: string, n: number) => {
  await until(`${n} ledger rows for ${person}`, () => rowsOf(person).length >= n, 20_000);
  return rowsOf(person).at(-1)!;
};

test("a good turn leaves one row with who, where, what it used and how fast, priced from the rate card", async () => {
  const k = srv.mint("alice@example.com");
  expect((await srv.turn(k.key)).status).toBe(200);
  const r = await lastOf("alice@example.com", 1);
  expect(rowsOf("alice@example.com").length).toBe(1);
  expect(r.person).toBe("alice@example.com");
  expect(r.device).toBe(k.prefix);
  expect(r.command).toBe(COMMAND);
  expect(r.model).toBe(MODEL);
  expect(r.route).toBe("messages");
  expect(r.status).toBe(200);
  expect(r.outcome).toBe("ok");
  expect([r.prompt_tokens, r.cached_tokens, r.completion_tokens]).toEqual([1200, 200, 40]);
  expect(r.ttft_ms).toBeGreaterThan(0);
  expect(r.duration_ms).toBeGreaterThanOrEqual(r.ttft_ms!);
  expect(r.prefill_tps).toBeCloseTo(2000, 0);
  expect(r.decode_tps).toBeCloseTo(50, 0);
  expect(r.cost_usd).toBeCloseTo(0.00242, 6);
  expect(Math.abs(Date.parse(r.at) - Date.now())).toBeLessThan(120_000);
}, 60_000);

test("no row, and no file in the server's home, holds the prompt's or the reply's text", async () => {
  const k = srv.mint("cara@example.com");
  const asked = `CANARY-ASKED-${crypto.randomUUID()}`;
  const told = `CANARY-TOLD-${crypto.randomUUID()}`;
  srv.up.behaviour.say = told;
  try {
    const t = await srv.turn(k.key, { content: `Repeat ${asked}` });
    expect(t.reply).toBe(told);
  } finally {
    srv.up.behaviour.say = null;
  }
  await lastOf("cara@example.com", 1);
  const out = srv.cli(["server", "usage", "rows", "--json", "--since", "1h"]).out;
  for (const c of [asked, told]) {
    expect(out).not.toContain(c);
    expect(srv.s.files().filter((f) => f.bytes.includes(c)).map((f) => f.path)).toEqual([]);
  }
}, 60_000);

test("an upstream body that breaks mid-stream is a 502 upstream_error, not a 200", async () => {
  const k = srv.mint("bea@example.com");
  srv.up.behaviour.breakAfter = 2;
  try {
    await srv.turn(k.key);
  } finally {
    srv.up.behaviour.breakAfter = null;
  }
  const r = await lastOf("bea@example.com", 1);
  expect(r.status).toBe(502);
  expect(r.outcome).toBe("upstream_error");
}, 120_000);

test("an upstream that answers 500 is a 502 upstream_error", async () => {
  const k = srv.mint("fio@example.com");
  srv.up.behaviour.status = 500;
  try {
    await srv.turn(k.key);
  } finally {
    srv.up.behaviour.status = null;
  }
  const r = await lastOf("fio@example.com", 1);
  expect(r.status).toBe(502);
  expect(r.outcome).toBe("upstream_error");
}, 120_000);

test("a client that leaves early is a 499 client_gone", async () => {
  const k = srv.mint("gil@example.com");
  srv.up.behaviour.prefillMs = 3_000;
  try {
    const a = new AbortController();
    const t = srv.turn(k.key, { signal: a.signal }).catch(() => null);
    await Bun.sleep(800);
    a.abort();
    await t;
  } finally {
    srv.up.behaviour.prefillMs = 0;
  }
  const r = await lastOf("gil@example.com", 1);
  expect(r.status).toBe(499);
  expect(r.outcome).toBe("client_gone");
}, 120_000);

test("a turn refused by the person's limit keeps its 429 as a refused row", async () => {
  const k = srv.mint("hugo@example.com");
  srv.up.behaviour.prefillMs = 2_500;
  try {
    const first = srv.turn(k.key);
    await Bun.sleep(700);
    expect((await srv.turn(k.key)).status).toBe(429);
    await first;
  } finally {
    srv.up.behaviour.prefillMs = 0;
  }
  await lastOf("hugo@example.com", 2);
  const rows = rowsOf("hugo@example.com");
  expect(rows.map((r) => r.status).sort()).toEqual([200, 429]);
  expect(rows.find((r) => r.status === 429)!.outcome).toBe("refused");
}, 120_000);

test("an OpenAI-style stream that ends without [DONE] is cut off", async () => {
  const k = srv.mint("ida@example.com");
  srv.up.behaviour.noDone = true;
  try {
    const r = await fetch(`${srv.base}/${COMMAND}/v1/chat/completions`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${k.key}` },
      body: JSON.stringify({ model: MODEL, stream: true, messages: [{ role: "user", content: "hi" }] }),
    });
    await r.text();
  } finally {
    srv.up.behaviour.noDone = false;
  }
  const row = await lastOf("ida@example.com", 1);
  expect(row.route).toBe("chat_completions");
  expect(row.outcome).toBe("cut_off");
}, 60_000);

test("rows survive a restart", async () => {
  const before = rowsOf("alice@example.com").length;
  await srv.s.restart();
  expect(rowsOf("alice@example.com").length).toBe(before);
}, 300_000);
