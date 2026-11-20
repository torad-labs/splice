// gnh-turns: a turn through the Splice gateway naming `claude-<provider>--<model id>` runs on that provider with no head:
// it reaches the provider as the model id, with the effort the provider's vocabulary maps the session's effort to, and
// with that provider's key and no other credential; its usage and a count come back scaled to the window the provider
// declares, the way the door scales them, so compaction comes when it should; a provider that stops answering gets an
// honest error naming it while splice stays up, and the next turn after it is back runs with no restart. The turns run
// in the daemon itself, on no port of a head or of a provider.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { session, toDoor, type Session } from "../door/lib/claude.ts";
import { count } from "../door/lib/adds.ts";
import { carries } from "../catalog/lib/gateway.ts";
import { Splice, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { PEBBLE, SLATE, inputOf, listed, listening, messageOf, provider, selector, send } from "./lib/providers.ts";

setDefaultTimeout(240_000);

const PEBBLE_SECRET = "sk-pebble-check-secret-7f3a";
const SLATE_SECRET = "sk-slate-check-secret-91c2";

let pebble: Upstream;
let slate: Upstream;
let pebblePort = 0;
let s: Splice;
let x: Session;

beforeAll(async () => {
  pebble = new Upstream("pebble", PEBBLE).start();
  pebblePort = pebble.port;
  slate = new Upstream("slate", SLATE).start();
  const toml =
    // the row says 131072, the provider's own context_window narrows every row of it to 65536
    provider({
      key: "pebble",
      url: pebble.url,
      model: PEBBLE,
      window: 131072,
      env: "PEBBLE_KEY",
      more: "context_window = 65536",
      quirks: 'reasoning_effort = true\nchat_effort_vocabulary = { default = "steady", levels = { low = "light", medium = "steady", high = "deep", xhigh = "deepest", max = "deepest" } }',
    }) + provider({ key: "slate", url: slate.url, model: SLATE, window: 262144, env: "SLATE_KEY" });
  s = await Splice.boot(toml, { PEBBLE_KEY: PEBBLE_SECRET, SLATE_KEY: SLATE_SECRET });
  // the terminal's sessions declare slate's window, 262144: four times pebble's
  toDoor(s, "--model", selector("slate", SLATE));
  x = await session(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  pebble?.stop();
  slate?.stop();
});

describe("gnh-turns", () => {
  test("a turn naming the selector reaches the provider as its model id, with the mapped effort and its key alone", async () => {
    const before = pebble.completions().length;
    const r = await send(x, selector("pebble", PEBBLE), { effort: "xhigh" });
    expect(r.status, r.body).toBe(200);
    const seen = pebble.completions().slice(before);
    expect(seen.length).toBe(1);
    expect(seen[0]!.body?.model).toBe(PEBBLE);
    expect(seen[0]!.body?.reasoning_effort).toBe("deepest");
    expect(carries(seen, PEBBLE_SECRET)).toBe(true);
    expect(carries(seen, SLATE_SECRET)).toBe(false);
    expect(carries(seen, x.key)).toBe(false);
  });

  test("a turn on the other provider carries only that provider's key", async () => {
    const before = slate.completions().length;
    const r = await send(x, selector("slate", SLATE), { effort: "high" });
    expect(r.status, r.body).toBe(200);
    const seen = slate.completions().slice(before);
    expect(seen.length).toBe(1);
    expect(seen[0]!.body?.model).toBe(SLATE);
    expect(carries(seen, SLATE_SECRET)).toBe(true);
    expect(carries(seen, PEBBLE_SECRET)).toBe(false);
    expect(carries(seen, x.key)).toBe(false);
  });

  test("usage comes back scaled to the provider's declared window against the session's", async () => {
    // both mocks report the same usage, so the two answers differ only by the scale: 262144 / 65536 on pebble, 1 on slate
    const onPebble = inputOf(await send(x, selector("pebble", PEBBLE)));
    const onSlate = inputOf(await send(x, selector("slate", SLATE)));
    expect(Number.isFinite(onPebble) && Number.isFinite(onSlate)).toBe(true);
    expect(onSlate).toBeGreaterThan(0);
    expect(onPebble / onSlate).toBeCloseTo(4, 1);
  });

  test("a count is scaled the same way", async () => {
    const content = "the same words counted on both providers ".repeat(40);
    const a = await count(x, selector("pebble", PEBBLE), content);
    const b = await count(x, selector("slate", SLATE), content);
    expect([a.status, b.status]).toEqual([200, 200]);
    expect(a.input_tokens / b.input_tokens).toBeCloseTo(4, 1);
  });

  test("the turns ran in the daemon itself: no port was opened for a provider, nothing listens but the control port", () => {
    expect(listening(s)).toEqual([s.controlPort]);
  });

  test("a provider that stops answering stays listed, gets an honest error naming it, and splice stays up", async () => {
    pebble.stop();
    expect(listed(s).map((r) => r.model)).toContain(selector("pebble", PEBBLE));
    const r = await send(x, selector("pebble", PEBBLE));
    expect(r.status).toBeGreaterThanOrEqual(400);
    expect(messageOf(r).toLowerCase()).toContain("pebble");
    // the daemon is up and the other provider still serves
    expect((await send(x, selector("slate", SLATE))).status).toBe(200);
    pebble.start(pebblePort);
    await until("the provider serves again with no restart", async () => (await send(x, selector("pebble", PEBBLE))).status === 200, 90_000);
  }, 360_000);
});
