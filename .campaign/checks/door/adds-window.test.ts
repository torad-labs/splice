// adds-window: a `claude` session on its head holds Claude Code's own window for a model it does not know: 200,000
// tokens, or 1,000,000 for a selector that ends in `[1m]`. The models of other accounts have windows of their own, so
// a relayed turn's counts are reported as the same share of the window the session holds that they are of the
// model's own, and the session compacts before its model's limit. A Claude model's counts are Anthropic's own,
// untouched. (docs/specs/door-default.md, decision 9.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Provider } from "../harness/lib/capability.ts";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { claudeHead, count, turn } from "./lib/adds.ts";
import { type Session, session } from "./lib/claude.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

/** What Claude Code holds for a model it does not know, and for one whose name ends in `[1m]`. */
const HELD = 200_000;
const HELD_1M = 1_000_000;

const NARROW = { window: 100_000, selector: "claude-narrow--narrow-model" };
const WIDE = { window: 400_000, selector: "claude-wide--wide-model" };
const VAST = { window: 600_000, selector: "claude-vast--vast-model[1m]" };

let anthropic: Provider;
let narrow: Upstream;
let wide: Upstream;
let vast: Upstream;
let s: Splice;
let x: Session;

beforeAll(async () => {
  anthropic = new Provider("anthropic").start();
  // each provider reports a prompt that fills half of its model's own window
  const half = (window: number) => ({ usage: { prompt: window / 2, cached: 0, completion: 40 } });
  narrow = new Upstream("narrow", "narrow-model", half(NARROW.window)).start();
  wide = new Upstream("wide", "wide-model", half(WIDE.window)).start();
  vast = new Upstream("vast", "vast-model", half(VAST.window)).start();
  s = await Splice.boot(
    claudeHead({ port: await freePort(), url: anthropic.url }) +
      mockHead({ key: "narrow", command: "claude-narrow", port: await freePort(), url: narrow.url, model: "narrow-model", window: NARROW.window }) +
      mockHead({ key: "wide", command: "claude-wide", port: await freePort(), url: wide.url, model: "wide-model", window: WIDE.window }) +
      mockHead({ key: "vast", command: "claude-vast", port: await freePort(), url: vast.url, model: "vast-model[1m]", window: VAST.window }),
  );
  x = await session(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  anthropic?.stop();
  narrow?.stop();
  wide?.stop();
  vast?.stop();
});

/** The share of the window the session holds (`held`) that one turn's answer reports. */
async function share(selector: string, id: string, stream: boolean, held = HELD): Promise<number> {
  const a = await turn(x, selector, { session: id, stream });
  expect([selector, a.status]).toEqual([selector, 200]);
  return a.reported / held;
}

describe("adds-window", () => {
  test("the launch sets no window, so Claude's own models keep theirs", () => {
    expect(x.sets.CLAUDE_CODE_MAX_CONTEXT_TOKENS).toBeUndefined();
    expect(x.sets.CLAUDE_CODE_AUTO_COMPACT_WINDOW).toBeUndefined();
  });

  test("a turn that fills half of its model's window is reported as half of the 200,000 the session holds, on either model", async () => {
    for (const stream of [false, true]) {
      expect(await share(NARROW.selector, crypto.randomUUID(), stream)).toBeCloseTo(0.5, 1);
      expect(await share(WIDE.selector, crypto.randomUUID(), stream)).toBeCloseTo(0.5, 1);
    }
  });

  test("a selector that ends in [1m] is reported against the 1,000,000 Claude Code holds for it", async () => {
    for (const stream of [false, true]) {
      expect(await share(VAST.selector, crypto.randomUUID(), stream, HELD_1M)).toBeCloseTo(0.5, 1);
    }
  });

  test("a session that switches model is reported against the model each turn ran on", async () => {
    const id = crypto.randomUUID();
    expect(await share(WIDE.selector, id, true)).toBeCloseTo(0.5, 1);
    expect(await share(NARROW.selector, id, true)).toBeCloseTo(0.5, 1);
    expect(await share(VAST.selector, id, true, HELD_1M)).toBeCloseTo(0.5, 1);
    expect(await share(WIDE.selector, id, true)).toBeCloseTo(0.5, 1);
  });

  test("a Claude model's counts are the ones Anthropic gave, in the same session too", async () => {
    const id = crypto.randomUUID();
    expect(await share(WIDE.selector, id, true)).toBeCloseTo(0.5, 1);
    for (const stream of [false, true]) {
      const a = await turn(x, "claude-opus-5-5", { session: id, stream });
      expect(a.status).toBe(200);
      // the stand-in for Anthropic reports 12 input tokens and no cache
      expect(a.reported).toBe(12);
    }
  });

  test("count_tokens answers in the same measure: the same text is four times the share of a window a quarter the size", async () => {
    const content = Array.from({ length: 6000 }, (_, i) => `word${i}`).join(" ");
    const onNarrow = await count(x, NARROW.selector, content);
    const onWide = await count(x, WIDE.selector, content);
    expect(onNarrow.status).toBe(200);
    expect(onWide.status).toBe(200);
    expect(onWide.input_tokens).toBeGreaterThan(0);
    const ratio = onNarrow.input_tokens / onWide.input_tokens;
    expect(ratio).toBeGreaterThan(3.6);
    expect(ratio).toBeLessThan(4.4);
  });
});
