// agent-call-history: the client never saw the agent's call, so its history holds one assistant message where the
// model made three: its message with the call, the call's result, and the answer. splice keeps the call and its result
// and puts them back where the model made them, byte for byte, thinking blocks and their signatures included, on every
// later request of the session: the next turn, the request that compacts the conversation, and after the daemon
// restarts. A request whose record is gone still goes to the provider in a shape it accepts.
// (docs/specs/harness.md, decision 12.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { AgentProvider, type Reply, agentCapability, answer, asking, assemble, text, thinking, toolId, unmarkedAll } from "./lib/agent.ts";
import { type Block, IDLE, KEYS, type Message, type SeenTurn, Worker, first, head, send, unmarked } from "./lib/capability.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

let up: AgentProvider;
let advisor: Worker;
let s: Splice;
let astra: Launched;
let config: string;

beforeAll(async () => {
  up = new AgentProvider("the provider").start();
  advisor = new Worker().start();
  config = head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }) + agentCapability("advisor", { heads: ["astra"], url: advisor.url });
  s = await Splice.boot(config, KEYS);
  astra = await launch(s, "claude-astra");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
  advisor?.stop();
});

afterEach(() => {
  advisor.work = IDLE;
});

interface Called {
  session: string;
  mine: Block[];
  /** The one assistant message the client was sent, as it keeps it. */
  merged: Block[];
  /** The conversation the provider produced: the request it answered last, and that answer. */
  produced: Message[];
}

/** A first turn whose model calls the advisor once, thinking before the call and after it. */
async function called(l: Launched): Promise<Called> {
  const m = `m${crypto.randomUUID().slice(0, 8)}`;
  const ask: Reply = asking(toolId(), `Is the plan sound? (${m})`, [thinking(`The plan moves the retry ladder (${m}).`), text("Let me ask for a review first.")]);
  const after = answer(thinking("The review found no gap."), text("The review agrees. Moving the retry ladder now."));
  up.plan(m, [ask, after]);
  const { session, mine, sent } = await first(l, `Move the retry ladder into its own module (${m}).`);
  expect(sent.status).toBe(200);
  expect(sent.error).toBeNull();
  const merged = assemble(sent.body).blocks;
  expect(merged).toEqual([...ask.blocks.slice(0, -1), ...after.blocks]);
  const sentUp = up.with(m);
  expect(sentUp.length).toBe(2);
  // the follow-up request holds [user], [assistant: before + call], [user: result]; the answer came after it
  const produced = [...unmarkedAll(sentUp[1]!.body.messages), { role: "assistant", content: after.blocks }];
  expect(produced.length).toBe(4);
  expect(produced[1]).toEqual({ role: "assistant", content: ask.blocks });
  return { session, mine, merged, produced };
}

/** The session's history as Claude Code sends it next: its first message, the one assistant message it was sent, and
 *  what the person typed next, with Claude Code's cache mark on it. */
function history(c: Called, next: string): Message[] {
  return [
    { role: "user", content: unmarked(c.mine) },
    { role: "assistant", content: c.merged },
    { role: "user", content: [{ type: "text", text: next, cache_control: { type: "ephemeral", ttl: "1h" } }] },
  ];
}

/** The provider got the conversation it produced, then what the person typed next. */
function restored(got: SeenTurn, c: Called, next: string): void {
  const messages = unmarkedAll(got.body.messages);
  expect(messages).toEqual([...c.produced, { role: "user", content: [{ type: "text", text: next }] }]);
  // the signatures are the model's own, on both thinking blocks
  const signed = messages.flatMap((m) => (typeof m.content === "string" ? [] : m.content)).filter((b) => b.type === "thinking");
  expect(signed.length).toBe(2);
  for (const b of signed) expect(typeof b.signature === "string" && b.signature.length > 0).toBe(true);
}

describe("agent-call-history", () => {
  test("the next turn sends the provider the conversation it produced, byte for byte, and asks the worker nothing", async () => {
    const c = await called(astra);
    const next = "Now add a test for the new module.";
    const sent = await send(astra, c.session, history(c, next));
    expect(sent.status).toBe(200);
    expect(sent.error).toBeNull();
    restored(up.of(c.session).at(-1)!, c, next);
    expect(advisor.of(c.session).length).toBe(1);
    expect(up.refused.length).toBe(0);
  });

  test("the request that compacts the conversation carries the call and its result the same way", async () => {
    const c = await called(astra);
    const next = "Your task is to create a detailed summary of the conversation so far.";
    const sent = await send(astra, c.session, history(c, next));
    expect(sent.status).toBe(200);
    restored(up.of(c.session).at(-1)!, c, next);
    expect(up.refused.length).toBe(0);
  });

  test("after the daemon restarts, a later turn puts the call back the same way", async () => {
    const c = await called(astra);
    await s.restart();
    const next = "Now add a test for the new module.";
    const sent = await send(astra, c.session, history(c, next));
    expect(sent.status).toBe(200);
    expect(sent.error).toBeNull();
    restored(up.of(c.session).at(-1)!, c, next);
    expect(advisor.of(c.session).length).toBe(1);
    expect(up.refused.length).toBe(0);
  }, 180_000);

  test("a session whose record is gone still gets an answer: the provider accepts what it is sent", async () => {
    const c = await called(astra);
    // a daemon with a home of its own holds no record of the call
    await s.stop();
    const fresh = await Splice.boot(config, KEYS);
    try {
      const other = await launch(fresh, "claude-astra");
      const refusedBefore = up.refused.length;
      const sent = await send(other, c.session, history(c, "Now add a test for the new module."));
      expect(sent.status).toBe(200);
      expect(sent.error).toBeNull();
      expect(sent.body).toContain(up.reply());
      expect(up.refused.slice(refusedBefore).map((r) => r.why)).toEqual([]);
    } finally {
      await fresh.dispose();
    }
  }, 300_000);
});
