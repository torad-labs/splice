// limit-says: a turn that waits on a usage limit tells the person through the same piece a capability's hold does.
// The session's status line leads with the wait, as it does today, and now says how long ago the wait began and, with
// two turns of the session waiting, how many. The wait keeps what it does today: the line it writes into the
// conversation, what ends it, and what it tells Claude Code when the head stops. (docs/specs/harness.md, decision 8.)

import { afterAll, beforeAll, beforeEach, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { opening, send } from "./lib/capability.ts";
import { line } from "./lib/held.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

let up: Upstream;
let s: Splice;
let astra: Launched;

beforeAll(async () => {
  up = new Upstream("astra", "astra-large").start();
  s = await Splice.boot(mockHead({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url, model: "astra-large" }), { MOCK_UPSTREAM_KEY: "sk-check-upstream" });
  astra = await launch(s, "claude-astra");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
});

beforeEach(() => {
  up.behaviour.status = null;
  up.behaviour.replyHeaders = {};
});

const typed = (text: string) => [{ role: "user", content: opening(text) }];

/** What the status line says of a wait on a usage limit, and how long ago in seconds it says the wait began. */
function waiting(text: string): { at: number; age: number | null } {
  const m = /plan limit reached: waiting until [^·]+? or a reset( · (\d+)s ago)?/.exec(text);
  return { at: m ? m.index : -1, age: m?.[2] ? Number(m[2]) : null };
}

/** Lets the limit lift: the provider answers again when the wait it stated (25 seconds: a shorter one is a retry
 *  splice does not show) is over. */
function lift(): void {
  up.behaviour.status = null;
  up.behaviour.replyHeaders = {};
}

describe("limit-says", () => {
  test("a turn waiting on a usage limit leads the status line with the wait and how long ago it began", async () => {
    up.behaviour.status = 429;
    up.behaviour.replyHeaders = { "retry-after": "25" };
    const session = crypto.randomUUID();
    const other = crypto.randomUUID();
    const sending = send(astra, session, typed("Say OK."));
    await Bun.sleep(2000);
    const first = line(astra, session);
    const one = waiting(first);
    expect(one.at).toBeGreaterThan(-1);
    expect(one.at).toBeLessThan(first.indexOf("astra-large"));
    expect(one.age).not.toBeNull();
    expect(one.age!).toBeLessThanOrEqual(3);
    expect(waiting(line(astra, other)).at).toBe(-1);

    await Bun.sleep(2500);
    const two = waiting(line(astra, session));
    expect(two.age).not.toBeNull();
    expect(two.age!).toBeGreaterThanOrEqual(one.age! + 2);

    lift();
    const sent = await sending;
    expect(sent.status).toBe(200);
    expect(sent.body).toContain(up.reply());
    // the line the wait writes into the conversation is the one it writes today
    expect(sent.body).toContain("[splice] claude-astra is at its usage limit. This turn continues in 25 seconds.");
    expect(waiting(line(astra, session)).at).toBe(-1);
  }, 300_000);

  test("with two turns of the session waiting, the line says how many", async () => {
    up.behaviour.status = 429;
    up.behaviour.replyHeaders = { "retry-after": "25" };
    const session = crypto.randomUUID();
    const main = send(astra, session, typed("Say OK."));
    await Bun.sleep(1500);
    expect(line(astra, session)).not.toMatch(/\b\d+ turns\b/);
    const sub = send(astra, session, typed("Find the file."), { agent: "agent-a1b2c3" });
    await Bun.sleep(1500);
    const both = line(astra, session);
    expect(waiting(both).at).toBeGreaterThan(-1);
    expect(both).toMatch(/\b2 turns\b/);

    lift();
    for (const sent of await Promise.all([main, sub])) expect(sent.status).toBe(200);
    const after = line(astra, session);
    expect(waiting(after).at).toBe(-1);
    expect(after).not.toMatch(/\b\d+ turns\b/);
  }, 300_000);

  test("the head stops while a turn waits on a limit: Claude Code is told to send the turn again, as today", async () => {
    up.behaviour.status = 429;
    up.behaviour.replyHeaders = { "retry-after": "40" };
    const session = crypto.randomUUID();
    const sending = send(astra, session, typed("Say OK."));
    await Bun.sleep(2000);
    expect(waiting(line(astra, session)).at).toBeGreaterThan(-1);
    const from = Date.now();
    const stopping = s.stop();
    const sent = await sending;
    await stopping;
    expect(Date.now() - from).toBeLessThan(15_000);
    expect(sent.error).toEqual({ type: "overloaded_error", message: "[splice] splice restarted during this turn. Claude Code sends this turn again." });
    expect(sent.body).not.toContain(up.reply());
  }, 300_000);
});
