// server-token-cap: `[server.limits] tpm_per_person` caps the tokens one person's turns may use in a minute. Once the
// tokens of their turns that ended in the last minute reach the cap, their next turn is answered 429
// rate_limit_error with a Retry-After of at most a minute, nothing reaches the upstream, and other people are served.
// A person's own `--tpm` overrides the server's.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { Server } from "./lib/server.ts";

// Each mock turn reports 1200 prompt tokens (200 of them cached) and 40 completion tokens, so two turns pass 2000
// however the cached ones are counted, and one does not.
const CAP = 2000;
let srv: Server;

beforeAll(async () => {
  srv = await Server.boot({ toml: `\n[server.limits]\ntpm_per_person = ${CAP}\n` });
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

test("a person whose last minute's tokens reach the cap is answered 429, and nothing reaches the upstream", async () => {
  const ann = srv.mint("ann@example.com").key;
  expect((await srv.turn(ann)).status).toBe(200);
  expect((await srv.turn(ann)).status).toBe(200);
  const before = srv.up.completions().length;
  const third = await srv.turn(ann);
  expect(third.status).toBe(429);
  expect(third.error?.type).toBe("rate_limit_error");
  const after = Number(third.headers.get("retry-after"));
  expect(after).toBeGreaterThanOrEqual(1);
  expect(after).toBeLessThanOrEqual(60);
  expect(srv.up.completions().length).toBe(before);
}, 120_000);

test("someone else is served while one person is held", async () => {
  expect((await srv.turn(srv.mint("bob@example.com").key)).status).toBe(200);
}, 120_000);

test("a streamed turn is held the same way", async () => {
  const cy = srv.mint("cy@example.com").key;
  expect((await srv.turn(cy, { stream: true })).status).toBe(200);
  expect((await srv.turn(cy, { stream: true })).status).toBe(200);
  expect((await srv.turn(cy, { stream: true })).status).toBe(429);
}, 120_000);

test("a person's own --tpm overrides the server's", async () => {
  expect(srv.cli(["server", "person", "set", "dee@example.com", "--tpm", "1000"]).code).toBe(0);
  const dee = srv.mint("dee@example.com").key;
  expect((await srv.turn(dee)).status).toBe(200);
  expect((await srv.turn(dee)).status).toBe(429);
  expect(srv.cli(["server", "person", "set", "fay@example.com", "--tpm", "100000"]).code).toBe(0);
  const fay = srv.mint("fay@example.com").key;
  for (let i = 0; i < 3; i++) expect((await srv.turn(fay)).status).toBe(200);
}, 120_000);

test("a cap that is not a positive number is refused at the CLI", () => {
  expect(srv.cli(["server", "person", "set", "gil@example.com", "--tpm", "0"]).code).not.toBe(0);
  expect(srv.cli(["server", "person", "set", "gil@example.com", "--tpm", "lots"]).code).not.toBe(0);
});
