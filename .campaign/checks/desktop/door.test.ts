// desktop-door: splice answers the Code tab of Claude Desktop under one address, /desktop, in Claude Code's Mantle
// dialect. The model a request names picks the head: a selector runs that model on its head, and Anthropic's own
// IDs, in any spelling Claude Code sends, run the tier's model on the head the session is on.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Splice, freePort, turn } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Door, clientHead, countTokens, doorTurn, slottedHead, turnOn } from "./lib/desktop.ts";

const ASTRA_BIG = "claude-astra--astra-large";
const ASTRA_SMALL = "claude-astra--astra-small";
const BOREALIS_BIG = "claude-borealis--borealis-large";

let astra: Upstream;
let borealis: Upstream;
let corvus: Upstream;
let s: Splice;
let d: Door;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  borealis = new Upstream("borealis", "borealis-large").start();
  corvus = new Upstream("corvus", "claude-opus-5-5").start();
  s = await Splice.boot(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }) +
      slottedHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, big: "borealis-large", small: "borealis-small" }) +
      clientHead({ key: "corvus", command: "claude-corvus", port: await freePort(), url: corvus.url }),
  );
  d = turnOn(s, "--model", ASTRA_BIG);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
  borealis?.stop();
  corvus?.stop();
});

/** The model `up` was asked for by one turn of `session` through the door that names `model`. */
async function ran(up: Upstream, model: string, session: string): Promise<unknown> {
  const before = up.completions().length;
  const t = await doorTurn(d.url, { key: d.key, model, session });
  expect(t.status).toBe(200);
  expect(t.reply).toBe(up.reply());
  expect(up.completions().length).toBe(before + 1);
  return up.completions()[before]!.body?.model;
}

/** A model the door does not serve: refused in Anthropic's error shape, naming `splice desktop`, with nothing sent
 *  to any provider. */
async function refused(model: string): Promise<void> {
  const before = [astra, borealis, corvus].map((u) => u.seen.length);
  const t = await doorTurn(d.url, { key: d.key, model, session: crypto.randomUUID() });
  expect(t.status).toBe(400);
  expect(t.error?.type).toBe("invalid_request_error");
  expect(t.error?.message).toContain("splice desktop");
  expect([astra, borealis, corvus].map((u) => u.seen.length)).toEqual(before);
}

describe("desktop-door", () => {
  test("the door is one address, under /desktop", () => {
    expect(d.url).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/desktop$/);
  });

  test("a selector runs that model on its head, whichever head it is", async () => {
    expect(await ran(astra, ASTRA_SMALL, crypto.randomUUID())).toBe("astra-small");
    expect(await ran(borealis, BOREALIS_BIG, crypto.randomUUID())).toBe("borealis-large");
  });

  test("a streamed turn is answered as Anthropic's stream", async () => {
    const t = await doorTurn(d.url, { key: d.key, model: ASTRA_BIG, session: crypto.randomUUID() });
    expect(t.status).toBe(200);
    expect(t.events.map((e) => e.event)).toContain("message_start");
    expect(t.events.map((e) => e.event)).toContain("message_stop");
  });

  test("Claude Code's start-up check, not streamed and without ?beta=true, runs on the default model's head", async () => {
    const before = astra.completions().length;
    const t = await turn(`${d.url}/v1/messages`, { key: d.key, bearer: true, model: "anthropic.claude-opus-5-5", stream: false });
    expect(t.status).toBe(200);
    expect(t.reply).toBe(astra.reply());
    expect(astra.completions()[before]!.body?.model).toBe("astra-large");
  });

  test("Anthropic's IDs run the head's model for their tier, in every spelling Claude Code sends", async () => {
    const spellings: [string, string][] = [
      ["anthropic.claude-opus-5-5", "astra-large"],
      ["anthropic.claude-sonnet-5-5", "astra-large"],
      ["anthropic.claude-haiku-5-5", "astra-small"],
      ["us.anthropic.claude-opus-5-5-v1:0", "astra-large"],
      ["global.anthropic.claude-haiku-4-5", "astra-small"],
      ["claude-opus-5-5", "astra-large"],
      ["claude-haiku-5-5", "astra-small"],
      ["opus", "astra-large"],
      ["sonnet", "astra-large"],
      ["haiku", "astra-small"],
      // a tier the head names no model for runs its pinned model
      ["anthropic.claude-fable-5-2", "astra-large"],
    ];
    for (const [asked, runs] of spellings) expect([asked, await ran(astra, asked, crypto.randomUUID())]).toEqual([asked, runs]);
  });

  test("a session is on the head of the last selector it named, so a tier it asks for runs there", async () => {
    const session = crypto.randomUUID();
    expect(await ran(borealis, BOREALIS_BIG, session)).toBe("borealis-large");
    expect(await ran(borealis, "anthropic.claude-haiku-5-5", session)).toBe("borealis-small");
    expect(await ran(astra, ASTRA_BIG, session)).toBe("astra-large");
    expect(await ran(astra, "anthropic.claude-haiku-5-5", session)).toBe("astra-small");
  });

  test("count_tokens is answered through the door", async () => {
    const c = await countTokens(d.url, { key: d.key, model: ASTRA_BIG, content: "Say hello." });
    expect(c.status).toBe(200);
    expect(c.input_tokens).toBeGreaterThan(0);
  });

  test("a model the door does not serve is refused, and nothing is sent upstream", async () => {
    await refused("gpt-nobody-serves");
    await refused("claude-nobody--astra-large");
  });

  test("a head that forwards the caller's own Claude login is not in the door", async () => {
    await refused("claude-corvus--claude-opus-5-5");
  });

  test("a restart does not move the door", async () => {
    await s.restart();
    expect(turnOn(s).url).toBe(d.url);
    expect(await ran(astra, ASTRA_SMALL, crypto.randomUUID())).toBe("astra-small");
  }, 120_000);
});
