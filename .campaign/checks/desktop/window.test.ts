// desktop-window: the Code tab's Claude Code has one context window for a session, the one the settings file
// declares, and the models in the door have windows of their own. The door reports a turn's counts as the same share
// of the declared window that they are of the model's own, so a session compacts before its model's limit whichever
// model it is on.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Door, countTokens, doorTurn, reported, turnOn } from "./lib/desktop.ts";

const NARROW = { window: 100_000, selector: "claude-narrow--narrow-model" };
const WIDE = { window: 400_000, selector: "claude-wide--wide-model" };

let narrow: Upstream;
let wide: Upstream;
let s: Splice;
let d: Door;

beforeAll(async () => {
  // each provider reports a prompt that fills half of its model's own window
  narrow = new Upstream("narrow", "narrow-model", { usage: { prompt: NARROW.window / 2, cached: 0, completion: 40 } }).start();
  wide = new Upstream("wide", "wide-model", { usage: { prompt: WIDE.window / 2, cached: 0, completion: 40 } }).start();
  s = await Splice.boot(
    mockHead({ key: "narrow", command: "claude-narrow", port: await freePort(), url: narrow.url, model: "narrow-model", window: NARROW.window }) +
      mockHead({ key: "wide", command: "claude-wide", port: await freePort(), url: wide.url, model: "wide-model", window: WIDE.window }),
  );
  d = turnOn(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  narrow?.stop();
  wide?.stop();
});

/** The share of the declared window one turn's answer reports. */
async function share(selector: string, session: string, stream: boolean): Promise<number> {
  const t = await doorTurn(d.url, { key: d.key, model: selector, session, stream });
  expect(t.status).toBe(200);
  return reported(t) / d.window;
}

describe("desktop-window", () => {
  test("the file declares one window to Claude Code", () => {
    expect(Number.isInteger(d.window)).toBe(true);
    expect(d.window).toBeGreaterThan(0);
  });

  test("a turn that fills half of its model's window is reported as half of the declared one, on either model", async () => {
    for (const stream of [false, true]) {
      expect(await share(NARROW.selector, crypto.randomUUID(), stream)).toBeCloseTo(0.5, 1);
      expect(await share(WIDE.selector, crypto.randomUUID(), stream)).toBeCloseTo(0.5, 1);
    }
  });

  test("a session that switches model is reported against the model each turn ran on", async () => {
    const session = crypto.randomUUID();
    expect(await share(WIDE.selector, session, true)).toBeCloseTo(0.5, 1);
    expect(await share(NARROW.selector, session, true)).toBeCloseTo(0.5, 1);
    expect(await share(WIDE.selector, session, true)).toBeCloseTo(0.5, 1);
  });

  test("count_tokens answers in the same measure: the same text is four times the share of a window a quarter the size", async () => {
    const content = Array.from({ length: 6000 }, (_, i) => `word${i}`).join(" ");
    const onNarrow = await countTokens(d.url, { key: d.key, model: NARROW.selector, content });
    const onWide = await countTokens(d.url, { key: d.key, model: WIDE.selector, content });
    expect(onNarrow.status).toBe(200);
    expect(onWide.status).toBe(200);
    expect(onWide.input_tokens).toBeGreaterThan(0);
    const ratio = onNarrow.input_tokens / onWide.input_tokens;
    expect(ratio).toBeGreaterThan(3.6);
    expect(ratio).toBeLessThan(4.4);
  });
});
