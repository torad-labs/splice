// server-client-version: `[server.client] minimum` names the oldest splice a laptop may run against the server. Each
// launch sends `x-splice-client: <version>`. A turn from an older client is answered 200 with an assistant message
// naming the version to upgrade to and `splice upgrade`, streamed or not, and nothing reaches the upstream (Claude
// Code retries a refusal about six times and shows a 200 once). A client at or past the minimum is served, and so is
// a request with no version (a script). Versions compare by number, part by part.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { Server } from "./lib/server.ts";

const MINIMUM = "0.10.0";
let srv: Server;
let key = "";

beforeAll(async () => {
  srv = await Server.boot({ toml: `\n[server.client]\nminimum = "${MINIMUM}"\n` });
  key = srv.mint("ann@example.com").key;
}, 900_000);
afterAll(async () => {
  await srv?.dispose();
}, 60_000);

const from = (version: string | null, stream = false) =>
  srv.turn(key, { stream, headers: version === null ? {} : { "x-splice-client": version } });

test("an older client's turn is a 200 assistant message naming the minimum and splice upgrade, and nothing goes upstream", async () => {
  const before = srv.up.completions().length;
  const t = await from("0.9.9");
  expect(t.status).toBe(200);
  expect(t.error).toBeNull();
  const body = JSON.parse(t.body) as { type: string; role: string; content: { type: string; text: string }[]; stop_reason: string };
  expect(body.type).toBe("message");
  expect(body.role).toBe("assistant");
  expect(body.stop_reason).toBe("end_turn");
  expect(t.reply).toContain(MINIMUM);
  expect(t.reply).toContain("splice upgrade");
  expect(t.reply).toContain("0.9.9");
  expect(srv.up.completions().length).toBe(before);
}, 120_000);

test("streamed, the same answer arrives as a whole Messages stream", async () => {
  const before = srv.up.completions().length;
  const t = await from("0.2.14", true);
  expect(t.status).toBe(200);
  expect(t.headers.get("content-type")).toContain("text/event-stream");
  const kinds = t.events.map((e) => e.event);
  expect(kinds[0]).toBe("message_start");
  expect(kinds).toContain("content_block_delta");
  expect(kinds[kinds.length - 1]).toBe("message_stop");
  expect(t.reply).toContain(MINIMUM);
  expect(t.reply).toContain("splice upgrade");
  expect(srv.up.completions().length).toBe(before);
}, 120_000);

test("a client at or past the minimum is served, compared by number and not by text", async () => {
  for (const v of [MINIMUM, "0.10.1", "0.11.0", "1.0.0"]) {
    const before = srv.up.completions().length;
    const t = await from(v);
    expect(t.status).toBe(200);
    expect(t.reply).not.toContain("splice upgrade");
    expect(srv.up.completions().length).toBe(before + 1);
  }
}, 120_000);

test("a request that names no version, as a script's does, is served", async () => {
  const before = srv.up.completions().length;
  const t = await from(null);
  expect(t.status).toBe(200);
  expect(t.reply).not.toContain("splice upgrade");
  expect(srv.up.completions().length).toBe(before + 1);
}, 120_000);
