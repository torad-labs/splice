// messages-key-stays: a person's key is the server's secret on the Messages route too. A header the provider's
// forward_headers names rides on to the provider unchanged, unless its value holds one of the server's keys: then the
// turn is still answered, that header stays behind, and the daemon log names the header and never the key.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { mockHead } from "./lib/harness.ts";
import { COMMAND, MODEL, Server } from "./lib/server.ts";

let srv: Server;
let ola = "";
let ben = "";
beforeAll(async () => {
  srv = await Server.boot({
    head: (url, port) => mockHead({ key: "mock", command: COMMAND, port, url, model: MODEL, provider: `forward_headers = ["x-team-user", "x-team-note"]` }),
  });
  ola = srv.mint("ola@example.com").key;
  ben = srv.mint("ben@example.com").key;
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

// The headers of the request the provider's server got for the turn that went out after `before` requests.
const sent = (before: number) => {
  const s = srv.up.completions()[before];
  expect(s).toBeDefined();
  return s!.headers;
};

test("a named header with an ordinary value rides on to the provider", async () => {
  const before = srv.up.completions().length;
  const t = await srv.turn(ola, { headers: { "x-team-user": "ola", "x-team-note": "the platform team" } });
  expect(t.status).toBe(200);
  expect(sent(before)["x-team-user"]).toBe("ola");
  expect(sent(before)["x-team-note"]).toBe("the platform team");
}, 60_000);

test("a named header that holds the person's own key stays behind, and the turn is answered", async () => {
  const before = srv.up.completions().length;
  const t = await srv.turn(ola, { headers: { "x-team-user": ola, "x-team-note": `for ${ola} today` } });
  expect(t.status).toBe(200);
  expect(t.reply).toBe(srv.up.reply());
  const h = sent(before);
  expect(Object.entries(h).filter(([, v]) => v.includes(ola))).toEqual([]);
  expect(h["x-team-user"]).toBeUndefined();
  expect(h["x-team-note"]).toBeUndefined();
}, 60_000);

test("so does one that holds another person's key, streamed or not", async () => {
  for (const stream of [true, false]) {
    const before = srv.up.completions().length;
    const t = await srv.turn(ola, { stream, headers: { "x-team-note": ben } });
    expect(t.status).toBe(200);
    expect(Object.entries(sent(before)).filter(([, v]) => v.includes(ben))).toEqual([]);
  }
}, 60_000);

test("the daemon log names the header it held back, and no key", () => {
  const log = readFileSync(srv.s.log, "utf8");
  expect(log).toContain("x-team-note");
  expect(log).not.toContain(ola);
  expect(log).not.toContain(ben);
});
