// person-limits: each person has a cap on turns in flight and on turns per minute; past either the server answers
// 429 with Retry-After, other people are unaffected, and a turn's slot is freed when it ends or its client leaves.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { Server } from "./lib/server.ts";

let srv: Server;
beforeAll(async () => {
  srv = await Server.boot({ toml: `\n[server.limits]\ninflight_per_person = 2\n`, upstream: { prefillMs: 3000 } });
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

const refusedRight = (t: { status: number; headers: Headers; error: { type: string } | null }) => {
  expect(t.status).toBe(429);
  expect(t.error?.type).toBe("rate_limit_error");
  expect(Number(t.headers.get("retry-after"))).toBeGreaterThanOrEqual(1);
};

test("a person past their in-flight cap is answered 429 with Retry-After, while someone else is served", async () => {
  const alice = srv.mint("alice@example.com").key;
  const bob = srv.mint("bob@example.com").key;
  const first = [srv.turn(alice), srv.turn(alice)];
  await Bun.sleep(800);
  const third = await srv.turn(alice);
  refusedRight(third);
  expect(third.error && JSON.stringify(third.error).toLowerCase()).toContain("2");
  const other = await srv.turn(bob);
  expect(other.status).toBe(200);
  for (const t of await Promise.all(first)) expect(t.status).toBe(200);
  expect((await srv.turn(alice)).status).toBe(200);
}, 120_000);

test("a turn whose client leaves frees its slot at once", async () => {
  const kim = srv.mint("kim@example.com").key;
  const aborts = [new AbortController(), new AbortController()];
  const gone = aborts.map((a) => srv.turn(kim, { signal: a.signal }).catch(() => null));
  await Bun.sleep(800);
  for (const a of aborts) a.abort();
  await Promise.all(gone);
  await Bun.sleep(1000);
  const again = await Promise.all([srv.turn(kim), srv.turn(kim)]);
  for (const t of again) expect(t.status).toBe(200);
}, 120_000);

test("a person's own in-flight cap overrides the server's", async () => {
  const r = srv.cli(["server", "person", "set", "carl@example.com", "--inflight", "1"]);
  expect(r.code).toBe(0);
  const carl = srv.mint("carl@example.com").key;
  const first = srv.turn(carl);
  await Bun.sleep(800);
  refusedRight(await srv.turn(carl));
  expect((await first).status).toBe(200);
}, 120_000);

test("a person past their turns per minute is answered 429 with a Retry-After of at most a minute", async () => {
  srv.up.behaviour.prefillMs = 0;
  try {
    expect(srv.cli(["server", "person", "set", "rex@example.com", "--rpm", "3"]).code).toBe(0);
    const rex = srv.mint("rex@example.com").key;
    for (let i = 0; i < 3; i++) expect((await srv.turn(rex)).status).toBe(200);
    const fourth = await srv.turn(rex);
    refusedRight(fourth);
    expect(Number(fourth.headers.get("retry-after"))).toBeLessThanOrEqual(60);
    expect((await srv.turn(srv.mint("sue@example.com").key)).status).toBe(200);
  } finally {
    srv.up.behaviour.prefillMs = 3000;
  }
}, 120_000);
