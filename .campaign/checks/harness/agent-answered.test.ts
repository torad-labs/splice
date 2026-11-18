// agent-call-answered: when the model's message calls the capability's tool and nothing else, the client never sees
// the call. splice asks the worker with the tool's input as the field `input` beside the fields `send` names, sends the
// provider the same request with the model's message and a tool_result carrying the worker's text, and streams that
// answer to the client as the rest of the same response: one assistant message, the model's blocks before the call
// and then the answer's. An answer that calls the tool again is answered again. A failing worker gives the model a
// tool_result saying so when the capability fails open, and refuses the turn when it fails closed; a refusal from a
// worker that may refuse refuses the turn; the client leaving stops the worker.
// (docs/specs/harness.md, decisions 3, 6 and 12.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { ADVISOR, AgentProvider, type Assembled, type Reply, agentCapability, answer, asking, assemble, resultText, text, thinking, toolId, unmarkedAll } from "./lib/agent.ts";
import { type Block, IDLE, KEYS, type SeenTurn, Worker, blocks, first, head, opening, send } from "./lib/capability.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

let up: AgentProvider;
/** advisor fails open and may only add; gate fails closed; guard fails open and may refuse. */
let advisor: Worker;
let gate: Worker;
let guard: Worker;
let s: Splice;
let astra: Launched;
let cirrus: Launched;
let draco: Launched;

beforeAll(async () => {
  up = new AgentProvider("the provider").start();
  advisor = new Worker().start();
  gate = new Worker().start();
  guard = new Worker().start();
  s = await Splice.boot(
    head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }) +
      head({ key: "cirrus", command: "claude-cirrus", port: await freePort(), url: up.url }) +
      head({ key: "draco", command: "claude-draco", port: await freePort(), url: up.url }) +
      agentCapability("advisor", { heads: ["astra"], url: advisor.url, send: ["model"] }) +
      agentCapability("gate", { heads: ["cirrus"], url: gate.url, onFailure: "closed", timeout: 5 }) +
      agentCapability("guard", { heads: ["draco"], url: guard.url, may: "add-or-refuse" }),
    KEYS,
  );
  astra = await launch(s, "claude-astra");
  cirrus = await launch(s, "claude-cirrus");
  draco = await launch(s, "claude-draco");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  for (const each of [up, advisor, gate, guard]) each?.stop();
});

afterEach(() => {
  for (const each of [advisor, gate, guard]) each.work = IDLE;
});

/** A marker that ties a test's requests together. */
const mark = () => `m${crypto.randomUUID().slice(0, 8)}`;

/** The one assistant message the client was sent: one message_start and one message_stop, block indices 0, 1, 2…
 *  in order, one message_delta, and no call of the capability's tool anywhere. */
function oneMessage(body: string): Assembled {
  const got = assemble(body);
  expect(got.count("message_start")).toBe(1);
  expect(got.count("message_stop")).toBe(1);
  expect(got.count("message_delta")).toBe(1);
  expect(got.indices).toEqual(got.indices.map((_, i) => i));
  expect(body).not.toContain(ADVISOR.name);
  expect(got.blocks.some((b) => b.type === "tool_use")).toBe(false);
  return got;
}

/** The request the provider was sent after the model called the tool: `before`'s, with the model's message and a user
 *  message holding one tool_result for `id`. Returns that tool_result. */
function followed(before: SeenTurn, after: SeenTurn, model: Block[], id: string): Block {
  const { messages: a, ...restA } = unmarkedAll(before.body);
  const { messages: b, ...restB } = unmarkedAll(after.body);
  expect(restB).toEqual(restA);
  expect(b.length).toBe(a.length + 2);
  expect(b.slice(0, a.length)).toEqual(a);
  // the model's message as it made it: thinking and signature, text and the call, byte for byte
  expect(b[a.length]).toEqual({ role: "assistant", content: model });
  const reply = b[a.length + 1]!;
  expect(reply.role).toBe("user");
  const content = blocks(reply);
  expect(content.length).toBe(1);
  expect(content[0]!.type).toBe("tool_result");
  expect(content[0]!.tool_use_id).toBe(id);
  return content[0]!;
}

describe("agent-call-answered", () => {
  test("the client receives one message, the model's blocks then the answer's, and never the call", async () => {
    const m = mark();
    const id = toolId();
    const before = [thinking(`The plan moves the retry ladder (${m}); a second look is worth it.`), text("Let me ask for a review of the plan first.")];
    const after = [thinking("The advisor found no gap in the plan."), text("The advisor agrees with the plan. Moving the retry ladder now.")];
    const ask: Reply = asking(id, `Is the plan to move the retry ladder sound? (${m})`, before);
    up.plan(m, [ask, answer(...after)]);

    const { session, sent } = await first(astra, `Move the retry ladder into its own module (${m}).`);
    expect(sent.status).toBe(200);
    expect(sent.error).toBeNull();
    const got = oneMessage(sent.body);
    expect(got.blocks).toEqual([...before, ...after]);
    expect(got.stops).toEqual(["end_turn"]);

    // the worker is asked once, with the tool's input beside the fields `send` names
    const asked = advisor.of(session);
    expect(asked.length).toBe(1);
    expect(asked[0]!.body.capability).toBe("advisor");
    expect(asked[0]!.body.fields).toEqual({ input: { question: `Is the plan to move the retry ladder sound? (${m})` }, model: "astra-large" });

    // the provider's second request is the first with the model's message and the worker's text as the call's result
    const sentUp = up.with(m);
    expect(sentUp.length).toBe(2);
    const r = followed(sentUp[0]!, sentUp[1]!, ask.blocks, id);
    expect(resultText(r)).toBe(advisor.text(asked[0]!));
    expect(r.is_error ?? false).toBe(false);
    expect(up.refused.length).toBe(0);
  });

  test("an answer that calls the tool again is answered again, in the same response", async () => {
    const m = mark();
    const [one, two] = [toolId(), toolId()];
    const first1 = asking(one, `Is the plan sound? (${m})`, [thinking(`Two things to check (${m}).`), text("Asking about the plan.")]);
    const first2 = asking(two, "And are the tests enough?", [text("Now asking about the tests.")]);
    const last = answer(text("Both reviews are back; the plan and its tests hold."));
    up.plan(m, [first1, first2, last]);
    advisor.text = (a) => `Review of: ${String((a.body.fields?.input as { question?: string } | undefined)?.question)}`;
    try {
      const { session, sent } = await first(astra, `Move the retry ladder into its own module (${m}).`);
      expect(sent.status).toBe(200);
      expect(sent.error).toBeNull();
      const got = oneMessage(sent.body);
      expect(got.blocks).toEqual([...first1.blocks.slice(0, -1), ...first2.blocks.slice(0, -1), ...last.blocks]);

      const asked = advisor.of(session);
      expect(asked.map((a) => (a.body.fields?.input as { question?: string }).question)).toEqual([`Is the plan sound? (${m})`, "And are the tests enough?"]);
      const sentUp = up.with(m);
      expect(sentUp.length).toBe(3);
      const r1 = followed(sentUp[0]!, sentUp[1]!, first1.blocks, one);
      expect(resultText(r1)).toBe(advisor.text(asked[0]!));
      const r2 = followed(sentUp[1]!, sentUp[2]!, first2.blocks, two);
      expect(resultText(r2)).toBe(advisor.text(asked[1]!));
    } finally {
      advisor.text = new Worker().text;
    }
  });

  test("open: a worker that fails, or refuses where it may only add, gives the model a result saying so, and the model goes on", async () => {
    const ways = [
      { what: "answers 500", work: { ...IDLE, status: 500 }, why: /500/ },
      { what: "refuses where it may only add", work: { ...IDLE, end: { refuse: "Not my place." } }, why: /refus/i },
    ];
    for (const w of ways) {
      const m = mark();
      const id = toolId();
      const ask = asking(id, `Is the plan sound? (${m})`, [thinking(`Checking (${m}).`), text("Asking for a review.")]);
      up.plan(m, [ask, answer(text("No review came back; going on without it."))]);
      advisor.work = w.work;
      const { sent } = await first(astra, `Move the retry ladder (${m}).`);
      expect(sent.status, w.what).toBe(200);
      expect(sent.error, w.what).toBeNull();
      expect(oneMessage(sent.body).blocks.at(-1)!.text, w.what).toBe("No review came back; going on without it.");
      const sentUp = up.with(m);
      expect(sentUp.length, w.what).toBe(2);
      const said = resultText(followed(sentUp[0]!, sentUp[1]!, ask.blocks, id));
      expect(said, w.what).toMatch(/fail/i);
      expect(said, w.what).toMatch(w.why);
    }
  });

  test("closed: a worker that fails refuses the turn as the stream's error, naming the capability", async () => {
    for (const w of [
      { what: "answers 500", work: { ...IDLE, status: 500 } },
      { what: "passes its time limit", work: { ...IDLE, waitMs: 9000 } },
    ]) {
      const m = mark();
      up.plan(m, [asking(toolId(), `Is the plan sound? (${m})`, [thinking(`Checking (${m}).`), text("Asking for a review.")]), answer(text("This answer is never asked for."))]);
      gate.work = w.work;
      const { sent } = await first(cirrus, `Move the retry ladder (${m}).`);
      expect(sent.error, w.what).not.toBeNull();
      expect(sent.error!.message, w.what).toContain("gate");
      expect(sent.body, w.what).not.toContain("This answer is never asked for.");
      expect(sent.body, w.what).not.toContain(ADVISOR.name);
      expect(up.with(m).length, w.what).toBe(1);
    }
  }, 60_000);

  test("a refusal from a worker that may refuse refuses the turn with its reason, though the capability fails open", async () => {
    const m = mark();
    up.plan(m, [asking(toolId(), `Is the plan sound? (${m})`, [thinking(`Checking (${m}).`), text("Asking for a review.")]), answer(text("This answer is never asked for."))]);
    guard.work = { ...IDLE, end: { refuse: "The plan deletes the production database." } };
    const { sent } = await first(draco, `Move the retry ladder (${m}).`);
    expect(sent.error).not.toBeNull();
    expect(sent.error!.message).toContain("guard");
    expect(sent.error!.message).toContain("The plan deletes the production database.");
    expect(sent.body).not.toContain("This answer is never asked for.");
    expect(up.with(m).length).toBe(1);
  });

  test("the client leaves while the worker works: the worker is stopped, and the provider is not asked again", async () => {
    const m = mark();
    up.plan(m, [asking(toolId(), `Is the plan sound? (${m})`, [thinking(`Checking (${m}).`), text("Asking for a review.")]), answer(text("This answer is never asked for."))]);
    advisor.work = { ...IDLE, waitMs: 5000 };
    const session = crypto.randomUUID();
    const gone = new AbortController();
    const sending = send(astra, session, [{ role: "user", content: opening(`Move the retry ladder (${m}).`) }], { signal: gone.signal }).catch(() => null);
    await until("the worker is asked", () => advisor.of(session).length === 1);
    await Bun.sleep(300);
    gone.abort();
    await sending;
    await until("the worker's request is dropped", () => advisor.dropped.includes(session), 3000);
    await Bun.sleep(5500);
    expect(up.with(m).length).toBe(1);
  }, 60_000);
});
