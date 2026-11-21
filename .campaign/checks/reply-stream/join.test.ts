// live-join: a follower that connects while a reply is being written gets, right after `hello`, that turn's `turn`
// event and one event per block holding everything written so far, then the live deltas, so it shows the whole reply.
// Every follower of a session gets every event, and a turn that has ended is no longer named in flight.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { type Door, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ClientTurn, Follower, SlowAnthropic, anthropicKeyHead, received } from "./lib/live.ts";

setDefaultTimeout(180_000);

const ASTRA = "claude-astra--astra-large";
const ANT = "claude-ant--claude-opus-5-5";

let astra: Upstream;
let ant: SlowAnthropic;
let s: Splice;
let d: Door;
const followers: Follower[] = [];

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  // about seven seconds a reply: time to join one halfway
  ant = new SlowAnthropic("ant", { gapMs: 150, thoughts: 10, words: 30 }).start();
  s = await Splice.boot(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }) +
      anthropicKeyHead({ key: "ant", command: "claude-ant", port: await freePort(), url: ant.url, model: "claude-opus-5-5" }),
  );
  d = turnOn(s, "--model", ASTRA);
}, 900_000);

afterAll(async () => {
  for (const f of followers) f.close();
  await s?.dispose();
  astra?.stop();
  ant?.stop();
});

async function follow(session: string): Promise<Follower> {
  const f = await Follower.open(s, session);
  followers.push(f);
  return f;
}

const nonEmpty = <T extends { text: string; thinking: string; tool?: unknown }>(bs: T[]) => bs.filter((b) => b.text || b.thinking || b.tool);

describe("live-join", () => {
  test("a follower that joins mid-reply gets what was written so far at once, then the rest", async () => {
    const session = crypto.randomUUID();
    const t = ClientTurn.send(d.url, d.key, { model: ANT, session, thinking: true, tools: true });
    await until("the client has some of the text", () => t.textDeltas().length >= 5, 60_000);
    const sofar = t.textDeltas().join("");
    const f = await follow(session);
    const hello = await f.hello();
    expect(hello.session).toBe(session);
    expect(Array.isArray(hello.live)).toBe(true);
    expect((hello.live as unknown[]).length).toBe(1);
    const id = String((hello.live as unknown[])[0]);

    // right after hello: the turn, then the blocks so far, one event each
    await f.until("the turn after hello", (me) => me.events.length >= 2);
    expect(f.events[0]!.event).toBe("hello");
    expect(f.events[1]!.event).toBe("turn");
    expect(f.events[1]!.data!.turn).toBe(id);
    const firstText = f.events.find((e) => e.event === "text" && e.data?.turn === id);
    await f.until("the text so far", (me) => me.events.some((e) => e.event === "text" && e.data?.turn === id));
    const snapshot = firstText ?? f.events.find((e) => e.event === "text" && e.data?.turn === id)!;
    expect(String(snapshot.data!.delta).startsWith(sofar)).toBe(true);

    await t.done;
    expect(t.status).toBe(200);
    expect(f.events[1]!.data!.message).toBe(t.message());
    await f.endOf(id);
    // the whole reply, as the client received it
    const got = received(t);
    expect(got.some((b) => b.text === ant.text())).toBe(true);
    expect(nonEmpty(f.blocks(id))).toEqual(got);
    // and fewer events than the client had deltas: what was written before it joined came as one event per block
    const textEvents = f.of(id).filter((e) => e.event === "text").length;
    expect(textEvents).toBeLessThan(t.textDeltas().length);
  });

  test("every follower of a session gets every event", async () => {
    const session = crypto.randomUUID();
    const a = await follow(session);
    const b = await follow(session);
    await Promise.all([a.hello(), b.hello()]);
    const t = ClientTurn.send(d.url, d.key, { model: ANT, session, thinking: true, tools: true });
    await t.done;
    const turn = await a.turnOf(t.message()!);
    const id = String(turn.turn);
    await Promise.all([a.endOf(id), b.endOf(id)]);
    expect(b.of(id)).toEqual(a.of(id));
    expect(nonEmpty(a.blocks(id))).toEqual(received(t));
  });

  test("once a turn has ended, a new follower's hello names no turn in flight", async () => {
    const session = crypto.randomUUID();
    const f = await follow(session);
    await f.hello();
    const t = ClientTurn.send(d.url, d.key, { model: ANT, session, content: "short" });
    await t.done;
    await f.endOf(String((await f.turnOf(t.message()!)).turn));
    const late = await follow(session);
    expect(await late.hello()).toEqual({ session, live: [] });
  });
});
