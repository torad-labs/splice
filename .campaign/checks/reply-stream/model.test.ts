// live-door-model: a turn's `model` is the model Claude Code asked for, also when the turn came through the door, which
// hands its head the model under the name that head runs it as.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { type Door, slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ClientTurn, Follower, SlowAnthropic, anthropicKeyHead } from "./lib/live.ts";

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
  ant = new SlowAnthropic("ant", { gapMs: 20 }).start();
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

describe("live-door-model", () => {
  test("a door turn's model is the one Claude Code named, on every head", async () => {
    const session = crypto.randomUUID();
    const f = await Follower.open(s, session);
    followers.push(f);
    await f.hello();
    for (const model of [ANT, ASTRA]) {
      const t = ClientTurn.send(d.url, d.key, { model, session, content: `which model: ${model}` });
      await t.done;
      expect(t.status).toBe(200);
      const turn = await f.turnOf(t.message()!);
      expect(turn.model).toBe(model);
      await f.endOf(String(turn.turn));
    }
  });
});
