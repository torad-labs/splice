// desktop-window: the Code tab's Claude Code has one context window for a session, the one `splice desktop on`
// declares in the settings file, and it compacts on the share of that window a turn's input and cache counts fill.
// The models in the door have windows of their own, so the door reports each turn's counts, and each count_tokens
// answer, as the same share of the declared window that they are of the window of the model the turn ran on.
//
// Two heads, each with one model:
//   narrow  a window of 100,000; its provider reports a prompt of 50,000 tokens, 10,000 of them cached: half
//   wide    a window of 400,000; its provider reports a prompt of 120,000 tokens, 40,000 of them cached: 0.3

import { Splice, freePort } from "../../checks/server/lib/harness.ts";
import { Upstream } from "../../checks/server/lib/upstream.ts";
import { check, must, once, run, show } from "../desktop-lib/cases.ts";
import { type Door, answer, ask, count, countOnHead, desktopOn, fileEnv, headRow, headUrl, keyHead, launch, reported, turnKey } from "../desktop-lib/door.ts";

const NARROW = { selector: "claude-narrow--narrow-model", model: "narrow-model", command: "claude-narrow", window: 100_000, prompt: 50_000, cached: 10_000, share: 0.5 };
const WIDE = { selector: "claude-wide--wide-model", model: "wide-model", command: "claude-wide", window: 400_000, prompt: 120_000, cached: 40_000, share: 0.3 };
type Head = typeof NARROW;

const narrow = new Upstream("narrow", NARROW.model, { usage: { prompt: NARROW.prompt, cached: NARROW.cached, completion: 40 } });
const wide = new Upstream("wide", WIDE.model, { usage: { prompt: WIDE.prompt, cached: WIDE.cached, completion: 40 } });
let live: Splice | undefined;

const booted = once(async () => {
  for (const up of [narrow, wide]) up.start();
  live = await Splice.boot(
    keyHead({ key: "narrow", command: NARROW.command, port: await freePort(), url: narrow.url, models: [{ id: NARROW.model, window: NARROW.window }] }) +
      keyHead({ key: "wide", command: WIDE.command, port: await freePort(), url: wide.url, models: [{ id: WIDE.model, window: WIDE.window }] }),
  );
  return live;
});

const open = once(async (): Promise<{ s: Splice; d: Door }> => {
  const s = await booted();
  return { s, d: desktopOn(s) };
});

/** The door, once the file declares a window the counts can be set against. */
async function declared(): Promise<Door> {
  const { d } = await open();
  must(Number.isInteger(d.window) && d.window > 0, `the file declares no window: CLAUDE_CODE_MAX_CONTEXT_TOKENS is ${show(d.window)}`);
  return d;
}

/** Fails unless one turn on `head` reports its counts as the share of the declared window they are of the
 *  model's own. */
async function sharesAlike(head: Head, session: string, stream: boolean): Promise<void> {
  const d = await declared();
  const t = await ask(d, { model: head.selector, session, stream });
  must(t.status === 200, `the turn on ${head.model} was answered ${answer(t)}`);
  const got = reported(t) / d.window;
  must(
    Math.abs(got - head.share) <= 0.01,
    `the turn on ${head.model} filled ${head.share} of its model's window (${head.prompt} of ${head.window}) and was reported as ${reported(t)} of the declared ${d.window}, which is ${got.toFixed(3)}`,
  );
}

check("on declares one window in the file", async () => {
  const { s } = await open();
  const value = fileEnv(s).CLAUDE_CODE_MAX_CONTEXT_TOKENS;
  must(/^[1-9]\d*$/.test(value ?? ""), `CLAUDE_CODE_MAX_CONTEXT_TOKENS is ${show(value)}`);
});

check("a turn that is not streamed reports the share of the declared window its counts are of its model's", async () => {
  await sharesAlike(NARROW, crypto.randomUUID(), false);
});

check("a streamed turn reports the share of the declared window its counts are of its model's", async () => {
  await sharesAlike(NARROW, crypto.randomUUID(), true);
});

check("a turn that is not streamed on a model with another window reports its own share", async () => {
  await sharesAlike(WIDE, crypto.randomUUID(), false);
});

check("a streamed turn on a model with another window reports its own share", async () => {
  await sharesAlike(WIDE, crypto.randomUUID(), true);
});

check("after a switch of model a session's turn is reported against the model it ran on", async () => {
  const session = crypto.randomUUID();
  await sharesAlike(WIDE, session, true);
  await sharesAlike(NARROW, session, true);
  await sharesAlike(WIDE, session, true);
});

/** A text long enough that a count of it is in the thousands. */
const TEXT = Array.from({ length: 6000 }, (_, i) => `word${i}`).join(" ");

/** Fails unless the door's count of TEXT on `head` is the share of the declared window that the head's own count
 *  of it is of the model's window. */
async function countsAlike(head: Head): Promise<void> {
  const d = await declared();
  const { s } = await open();
  const l = await launch(s, head.command);
  const own = await countOnHead(headUrl(l), turnKey(l), { model: head.model, content: TEXT });
  must(own.status === 200 && own.input_tokens > 0, `the head's own count_tokens answered ${own.status} ${own.body.slice(0, 200)}`);
  const c = await count(d, { model: head.selector, content: TEXT, session: crypto.randomUUID() });
  must(c.status === 200 && c.input_tokens > 0, `count_tokens through the door answered ${c.status} ${c.body.slice(0, 200)}`);
  const want = own.input_tokens / head.window;
  const got = c.input_tokens / d.window;
  must(
    Math.abs(got - want) <= want * 0.03,
    `the text is ${own.input_tokens} tokens of ${head.model}'s ${head.window}, a share of ${want.toFixed(4)}, and the door counted ${c.input_tokens} of the declared ${d.window}, a share of ${got.toFixed(4)}`,
  );
}

check("a count_tokens answer is the share of the declared window that the count is of its model's", async () => {
  await countsAlike(NARROW);
});

check("a count_tokens answer on a model with another window is its own share", async () => {
  await countsAlike(WIDE);
});

check("the same text counts four times as much on a model with a quarter of the window", async () => {
  const d = await declared();
  const onNarrow = await count(d, { model: NARROW.selector, content: TEXT });
  const onWide = await count(d, { model: WIDE.selector, content: TEXT });
  must(onNarrow.status === 200 && onWide.status === 200 && onWide.input_tokens > 0, `count_tokens answered ${onNarrow.status} ${onNarrow.body.slice(0, 100)} and ${onWide.status} ${onWide.body.slice(0, 100)}`);
  const ratio = onNarrow.input_tokens / onWide.input_tokens;
  must(ratio > 3.6 && ratio < 4.4, `the text counted ${onNarrow.input_tokens} on the narrow model and ${onWide.input_tokens} on the wide one, ${ratio.toFixed(2)} times as much`);
});

check("the turn's recorded counts are the provider's own", async () => {
  const d = await declared();
  const { s } = await open();
  const session = crypto.randomUUID();
  const t = await ask(d, { model: NARROW.selector, session });
  must(t.status === 200, `the turn was answered ${answer(t)}`);
  const row = await headRow(s, "narrow", session);
  must(
    row.in_tokens === NARROW.prompt && row.cached_tokens === NARROW.cached,
    `the provider reported ${NARROW.prompt} input tokens, ${NARROW.cached} of them cached, and the turn's row holds in_tokens ${show(row.in_tokens)} and cached_tokens ${show(row.cached_tokens)}`,
  );
});

await run(async () => {
  await live?.dispose();
  for (const up of [narrow, wide]) up.stop();
});
