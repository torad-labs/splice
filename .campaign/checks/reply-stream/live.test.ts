// live-feed: `GET /api/sessions/{id}/live` follows one session's reply as splice streams it to Claude Code. A follower
// gets `hello`, then for each response `turn`, the text and thinking the client was sent in order, each tool call's
// start and `end`; the stream stays open across turns and never ends with one. Another session's turns never show, a
// subagent's turn names its agent, nothing secret is sent, and the route opens only with the management key. A reply
// from a head on another dialect streams the same way.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { type Door, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
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
  astra = new Upstream("astra", "astra-large", { words: 12, gapMs: 60 }).start();
  ant = new SlowAnthropic("ant", { gapMs: 60, thoughts: 6, words: 12 }).start();
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

async function follow(session: string, key?: string | null): Promise<Follower> {
  const f = await Follower.open(s, session, key);
  followers.push(f);
  return f;
}

const nonEmpty = <T extends { text: string; thinking: string; tool?: unknown }>(bs: T[]) => bs.filter((b) => b.text || b.thinking || b.tool);

describe("live-feed", () => {
  test("the route opens only with the management key", async () => {
    const none = await follow(crypto.randomUUID(), null);
    expect(none.status).toBe(401);
    const wrong = await follow(crypto.randomUUID(), "not-the-key");
    expect(wrong.status).toBe(401);
    const ok = await follow(crypto.randomUUID());
    expect(ok.status).toBe(200);
    expect(ok.contentType).toContain("text/event-stream");
  });

  test("a session splice has not seen is no error: hello names no turn and the stream stays open", async () => {
    const session = crypto.randomUUID();
    const f = await follow(session);
    expect(f.status).toBe(200);
    expect(await f.hello()).toEqual({ session, live: [] });
    expect(f.events[0]!.event).toBe("hello");
    await Bun.sleep(2_000);
    expect(f.ended).toBe(false);
  });

  test("a follower sees the turn, its thinking and text in order, the tool start and the end, as the client got them", async () => {
    const session = crypto.randomUUID();
    const f = await follow(session);
    expect(await f.hello()).toEqual({ session, live: [] });
    const t0 = Date.now();
    const t = ClientTurn.send(d.url, d.key, { model: ANT, session, thinking: true, tools: true });
    await t.done;
    expect(t.status).toBe(200);
    const message = t.message()!;
    expect(message).toBeTruthy();

    const turn = await f.turnOf(message);
    const id = String(turn.turn);
    expect(Object.keys(turn).sort()).toEqual(["agent", "at", "compact", "head", "message", "model", "turn"]);
    expect(typeof turn.turn).toBe("string");
    expect(id.length).toBeGreaterThan(0);
    expect(turn.agent).toBeNull();
    expect(turn.head).toBe("ant");
    expect(turn.compact).toBe(false);
    expect(typeof turn.model).toBe("string");
    expect(String(turn.model).length).toBeGreaterThan(0);
    expect(typeof turn.at).toBe("number");
    expect(Number(turn.at)).toBeGreaterThanOrEqual(t0 - 5_000);
    expect(Number(turn.at)).toBeLessThanOrEqual(Date.now());

    const end = await f.endOf(id);
    expect(end).toEqual({ turn: id, stop_reason: t.stopReason(), error: null });
    expect(t.stopReason()).toBe("tool_use");

    // the turn's events: `turn` first, `end` last, nothing else names it, and each kind has its own fields
    const mine = f.of(id);
    expect(mine[0]!.event).toBe("turn");
    expect(mine.at(-1)!.event).toBe("end");
    expect(mine.filter((e) => e.event === "turn" || e.event === "end").length).toBe(2);
    for (const e of mine) {
      expect(["turn", "text", "thinking", "tool", "end"]).toContain(e.event);
      if (e.event === "text" || e.event === "thinking") expect(Object.keys(e.data!).sort()).toEqual(["delta", "index", "turn"]);
      if (e.event === "tool") expect(Object.keys(e.data!).sort()).toEqual(["id", "index", "name", "turn"]);
    }

    // what the follower was sent, put together, is what the client received, block by block and in the same order
    const got = received(t);
    expect(got.some((b) => b.text === ant.text())).toBe(true);
    expect(got.some((b) => b.tool?.name === "Read")).toBe(true);
    expect(nonEmpty(f.blocks(id))).toEqual(got);

    // the follower was sent no key, no header, no signature and no tool input
    for (const secret of [s.mgmtKey(), d.key, "mock-upstream-key", ant.signature, ant.toolMarker, "x-claude-code-session-id", "anthropic-version", "authorization"]) {
      expect(f.raw).not.toContain(secret);
    }

    // a turn's end does not end the stream
    await Bun.sleep(1_500);
    expect(f.ended).toBe(false);
  });

  test("the next turn comes on the same stream with an id of its own, and another session's turns never show", async () => {
    const session = crypto.randomUUID();
    const other = crypto.randomUUID();
    const f = await follow(session);
    await f.hello();
    const first = ClientTurn.send(d.url, d.key, { model: ANT, session, content: "first" });
    const elsewhere = ClientTurn.send(d.url, d.key, { model: ANT, session: other, content: "another session" });
    await Promise.all([first.done, elsewhere.done]);
    const a = await f.turnOf(first.message()!);
    await f.endOf(String(a.turn));
    const second = ClientTurn.send(d.url, d.key, { model: ANT, session, content: "second" });
    await second.done;
    const b = await f.turnOf(second.message()!);
    await f.endOf(String(b.turn));
    expect(b.turn).not.toBe(a.turn);
    expect(f.turnEvent(elsewhere.message()!)).toBeUndefined();
    const ids = new Set(f.events.filter((e) => e.event === "turn").map((e) => e.data!.turn));
    expect(ids).toEqual(new Set([a.turn, b.turn]));
    for (const e of f.events.filter((x) => x.event !== "hello")) expect(ids.has(e.data!.turn)).toBe(true);
    expect(f.ended).toBe(false);
  });

  test("a subagent's turn carries its agent id", async () => {
    const session = crypto.randomUUID();
    const agent = `agent-${crypto.randomUUID().slice(0, 8)}`;
    const f = await follow(session);
    await f.hello();
    const main = ClientTurn.send(d.url, d.key, { model: ANT, session, content: "main thread" });
    await main.done;
    const sub = ClientTurn.send(d.url, d.key, { model: ANT, session, agent, content: "subagent work" });
    await sub.done;
    expect((await f.turnOf(main.message()!)).agent).toBeNull();
    expect((await f.turnOf(sub.message()!)).agent).toBe(agent);
  });

  test("a reply from a head on another dialect streams the same way", async () => {
    const session = crypto.randomUUID();
    const f = await follow(session);
    await f.hello();
    const t = ClientTurn.send(d.url, d.key, { model: ASTRA, session, content: "hello there" });
    await t.done;
    expect(t.status).toBe(200);
    const turn = await f.turnOf(t.message()!);
    expect(turn.head).toBe("astra");
    const id = String(turn.turn);
    const end = await f.endOf(id);
    expect(end).toEqual({ turn: id, stop_reason: t.stopReason(), error: null });
    const got = received(t);
    expect(got.map((b) => b.text).join("")).toContain(astra.reply());
    expect(nonEmpty(f.blocks(id))).toEqual(got);
  });

  test("a quiet stream sends a ping comment every 15 s", async () => {
    const f = await follow(crypto.randomUUID());
    await f.hello();
    await f.until("a ping", (me) => me.comments.includes("ping"), 20_000);
    expect(f.ended).toBe(false);
  });
});
