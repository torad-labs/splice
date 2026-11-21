// door-session-route: a session started on the door reads as a door session in `GET /api/sessions`, in
// `splice sessions` and in `splice sessions --json`, with the model splice served it, and never as "unknown head": on
// this machine's door, and on another machine's door, where it is listed by the machine that launched it and not by
// the box that serves the door. A turn it runs through the door leaves it a door session. A session splice never
// launched reads as it always did.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { doorTurn, slottedHead } from "../desktop/lib/desktop.ts";
import { lane } from "../door/lib/claude.ts";
import { type Box, box, doorOpen, node, plain } from "../door/lib/share.ts";
import { Splice, freePort, json, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Row, type Started, address, row, start } from "./lib/launch.ts";

setDefaultTimeout(300_000);

let astra: Upstream;
let s: Splice;
let b: Box;
let n: Splice;
let local: Started;
let remote: Started;
const started: Started[] = [];

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  // no head is `claude`, so `claude` is on the door
  s = await Splice.boot(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }),
  );
  b = await box();
  await doorOpen(b);
  n = await node(b, `\n[remote.claude]\nurl = "${b.base}/door"\n`);
  local = await start(s, "claude");
  remote = await start(n, "claude");
  started.push(local, remote);
}, 600_000);

afterAll(async () => {
  for (const x of started) x.stop();
  await s?.dispose();
  await n?.dispose();
  await b?.s.dispose();
  b?.astra.stop();
  astra?.stop();
});

/** The session's row, once the list holds it. */
async function listed(on: Splice, session: string): Promise<Row> {
  let r: Row | null = null;
  await until("the session is listed", async () => (r = await row(on, session)) !== null);
  return r!;
}

/** `splice sessions` on `on`, the line of the session whose pid is `pid`. */
function line(on: Splice, pid: number): string {
  const r = on.cli(["sessions"]);
  expect(r.code).toBe(0);
  return plain(r.out).split("\n").find((l) => l.includes(`pid ${pid}`)) ?? "";
}

/** `splice sessions --json` on `on`, the session's entry. */
function entry(on: Splice, session: string): Record<string, unknown> | undefined {
  const r = on.cli(["sessions", "--json"]);
  expect(r.code).toBe(0);
  return json<{ sessions: Record<string, unknown>[] }>(r).sessions.find((e) => e.sessionId === session);
}

describe("door-session-route", () => {
  test("a session on this machine's door reads as a door session, before and after a turn", async () => {
    const door = lane(s).door!;
    expect(address(local)).toBe(door);
    const before = await listed(s, local.session);
    expect([before.route, before.head]).toEqual(["door", "door"]);
    expect(before.door).toEqual({ command: "claude", address: door, machine: null });

    const t = await doorTurn(door, { key: local.env.AWS_BEARER_TOKEN_BEDROCK ?? local.env.ANTHROPIC_AUTH_TOKEN, model: "claude-astra--astra-large", session: local.session });
    expect(t.status).toBe(200);
    // the turn names the head that ran it; the session is still the door's, on the model splice served it
    await until("the turn is counted", async () => (await row(s, local.session))?.served_model === "astra-large");
    const after = (await row(s, local.session))!;
    expect([after.route, after.head]).toEqual(["door", "door"]);
    expect(after.door).toEqual({ command: "claude", address: door, machine: null });
  });

  test("splice sessions and splice sessions --json say the same of it", async () => {
    const said = line(s, local.pid);
    expect(said).toContain("door");
    expect(said).not.toContain("unknown head");
    const e = entry(s, local.session);
    expect(e?.route).toBe("door");
    expect(e?.door).toEqual({ command: "claude", address: lane(s).door!, machine: null });
  });

  test("a session on another machine's door is listed as a door session by the machine that launched it", async () => {
    const r = await listed(n, remote.session);
    expect([r.route, r.head]).toEqual(["door", "door"]);
    expect(r.door).toEqual({ command: "claude", address: `${b.base}/door`, machine: "127.0.0.1" });
    const said = line(n, remote.pid);
    expect(said).toContain("door on 127.0.0.1");
    expect(said).not.toContain("unknown head");
    expect(entry(n, remote.session)?.route).toBe("door");
    // the box that serves the door is not asked to list it
    expect(await row(b.s, remote.session)).toBeNull();
  });

  test("a session splice never launched reads as it always did", async () => {
    const x = await start(s, "claude", true);
    started.push(x);
    const r = await listed(s, x.session);
    expect([r.route, r.head]).toEqual(["direct", "unknown head"]);
    expect(r.door ?? null).toBeNull();
  });
});
