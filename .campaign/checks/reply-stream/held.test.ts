// live-held: a compaction whose client hung up, which splice drives on, streams to its followers until it ends; and a
// turn the client asked for without streaming shows as a turn too, its whole answer at once when it is ready.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { type Door, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { COMPACT_ASK, COMPACT_SYSTEM, ClientTurn, Follower, SlowAnthropic, anthropicKeyHead, received } from "./lib/live.ts";

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
  ant = new SlowAnthropic("ant", { gapMs: 150, thoughts: 4, words: 30 }).start();
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

describe("live-held", () => {
  test("a compaction the client left streams to its followers until it ends", async () => {
    const session = crypto.randomUUID();
    const f = await follow(session);
    await f.hello();
    const t = ClientTurn.send(d.url, d.key, { model: ANT, session, system: COMPACT_SYSTEM, content: COMPACT_ASK });
    await until("the client has some of the summary", () => t.textDeltas().length >= 3, 60_000);
    const message = t.message()!;
    t.abort();
    await t.done;
    const turn = await f.turnOf(message);
    expect(turn.compact).toBe(true);
    const id = String(turn.turn);
    const end = await f.endOf(id, 90_000);
    expect(end).toEqual({ turn: id, stop_reason: "end_turn", error: null });
    // the whole summary reached the follower, though the client heard only its start
    const text = f.blocks(id).map((b) => b.text).join("");
    expect(text).toBe(ant.text());
    expect(t.textDeltas().join("").length).toBeLessThan(ant.text().length);
  });

  test("a turn that was not streamed shows as a turn, with its whole answer and its end", async () => {
    const session = crypto.randomUUID();
    const f = await follow(session);
    await f.hello();
    const t = ClientTurn.send(d.url, d.key, { model: ANT, session, stream: false, thinking: true, tools: true });
    await t.done;
    expect(t.status).toBe(200);
    expect(t.json).not.toBeNull();
    const turn = await f.turnOf(t.message()!);
    expect(turn.head).toBe("ant");
    expect(turn.compact).toBe(false);
    const id = String(turn.turn);
    const end = await f.endOf(id);
    expect(end).toEqual({ turn: id, stop_reason: t.stopReason(), error: null });
    const got = received(t);
    expect(got.some((b) => b.text === ant.text())).toBe(true);
    expect(f.blocks(id).filter((b) => b.text || b.thinking || b.tool)).toEqual(got);
    for (const secret of [ant.signature, ant.toolMarker]) expect(f.raw).not.toContain(secret);
  });
});
