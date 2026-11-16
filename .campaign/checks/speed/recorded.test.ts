// speed-recorded: each turn's row says the speed it was asked for and the speed the backend served it at, from the
// backend's own answer, and a plan turn is valued at the price of the speed it was served at. A turn served at a
// speed whose price splice doesn't know is not valued at the standard price.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { CodexBackend, FAST, FAST_ONLY, ULTRAFAST, gptEnv, gptHead } from "./lib/codex.ts";
import { ask, launch, prompt, type Launched } from "./lib/session.ts";

// GPT-6 Astra's published short-context cards (developers.openai.com/api/docs/pricing, read 2026-11-15), per million
// tokens. Each turn the stand-in backend answers is 1000 input tokens, none cached, and 100 output tokens.
const ASTRA_RATES = [
  "input = 10.0, cache_read = 1.0, output = 50.0",
  "fast_input = 20.0, fast_cache_read = 2.0, fast_output = 100.0",
  "ultrafast_input = 60.0, ultrafast_cache_read = 6.0, ultrafast_output = 300.0",
].join(", ");
const STANDARD_VALUE = (1000 * 10 + 100 * 50) / 1e6;
const FAST_VALUE = (1000 * 20 + 100 * 100) / 1e6;
const ULTRAFAST_VALUE = (1000 * 60 + 100 * 300) / 1e6;

let backend: CodexBackend;
let s: Splice;
let astra: Launched;
let terra: Launched;

beforeAll(async () => {
  backend = CodexBackend.start([
    { slug: "gpt-6-astra", tiers: [FAST, ULTRAFAST] },
    { slug: "gpt-6.1-terra", tiers: [FAST_ONLY] },
  ]);
  const logins = `/tmp/claude-1000/speed-recorded-${process.pid}`;
  const toml =
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins, rates: ASTRA_RATES }) +
    // Terra's card here names no Fast price.
    gptHead({ key: "terra", command: "claude-terra", port: await freePort(), backend: backend.url, model: "gpt-6.1-terra", dir: logins, rates: "input = 1.0, cache_read = 0.1, output = 5.0" });
  s = await Splice.boot(toml, gptEnv(backend.url));
  astra = await launch(s, "claude-astra");
  terra = await launch(s, "claude-terra");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  backend?.stop();
});

type Row = Record<string, unknown>;

let n = 0;
/** One new turn of `session` on head `key`, and its row once the daemon has written it. */
async function turnRow(l: Launched, key: string, session: string): Promise<Row> {
  const before = (await rows(key, session)).length;
  const r = await ask(l, session, `SPEED-RECORDED-${++n}`);
  expect(r.status).toBe(200);
  for (let i = 0; i < 50; i++) {
    const now = await rows(key, session);
    if (now.length > before) return now[0]!;
    await Bun.sleep(200);
  }
  throw new Error(`no row for the turn of session ${session}`);
}

/** The rows of `session` on head `key`, newest first. */
async function rows(key: string, session: string): Promise<Row[]> {
  const j = (await (await s.api(`/api/perf/turns?head=${key}`)).json()) as { heads: { key: string; rows: Row[] }[] };
  return (j.heads.find((h) => h.key === key)?.rows ?? []).filter((r) => r.session_id === session);
}

describe("speed-recorded", () => {
  test("each turn's row names the speed it was asked for and served at, and is valued at that speed's price", async () => {
    const one = crypto.randomUUID();
    const standard = await turnRow(astra, "astra", one);
    expect(standard.speed_asked).toBe("standard");
    expect(standard.speed).toBe("standard");
    expect(standard.plan_value_usd as number).toBeCloseTo(STANDARD_VALUE, 9);

    prompt(astra, one, "/fast");
    const fast = await turnRow(astra, "astra", one);
    expect(fast.speed_asked).toBe("fast");
    expect(fast.speed).toBe("fast");
    expect(fast.plan_value_usd as number).toBeCloseTo(FAST_VALUE, 9);

    prompt(astra, one, "/ultrafast");
    const ultra = await turnRow(astra, "astra", one);
    expect(ultra.speed_asked).toBe("ultrafast");
    expect(ultra.speed).toBe("ultrafast");
    expect(ultra.plan_value_usd as number).toBeCloseTo(ULTRAFAST_VALUE, 9);
  });

  test("a backend that names the tier fast, not priority, is read the same", async () => {
    const one = crypto.randomUUID();
    prompt(astra, one, "/fast");
    backend.echoFast = true;
    try {
      const row = await turnRow(astra, "astra", one);
      expect(row.speed).toBe("fast");
      expect(row.plan_value_usd as number).toBeCloseTo(FAST_VALUE, 9);
    } finally {
      backend.echoFast = false;
    }
  });

  test("a turn the backend served slower than asked reads as what it was served at, and is valued so", async () => {
    const one = crypto.randomUUID();
    prompt(astra, one, "/fast");
    backend.downgrade = true;
    try {
      const row = await turnRow(astra, "astra", one);
      expect(row.speed_asked).toBe("fast");
      expect(row.speed).toBe("standard");
      expect(row.plan_value_usd as number).toBeCloseTo(STANDARD_VALUE, 9);
    } finally {
      backend.downgrade = false;
    }
  });

  test("a turn served at a speed whose price splice doesn't know is not valued at the standard price", async () => {
    const one = crypto.randomUUID();
    prompt(terra, one, "/fast");
    const row = await turnRow(terra, "terra", one);
    expect(row.speed).toBe("fast");
    expect(row.plan_value_usd).toBeNull();
  });
});
