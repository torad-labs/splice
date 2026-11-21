// agent-call-parallel: when one message of the model calls the capability's tool and a tool of the client's, the
// client gets its own call and never the capability's. The worker runs while the client runs its tool, and when the
// client sends its result splice puts the capability's result beside it: the provider gets the model's message with
// both calls in the order it made them, and one user message with both results in that order.
// (docs/specs/harness.md, decision 12.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { ADVISOR, AgentProvider, answer, agentCapability, assemble, call, result, resultText, text, thinking, toolId, unmarkedAll } from "./lib/agent.ts";
import { type Block, IDLE, KEYS, Worker, first, head, send, unmarked } from "./lib/capability.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

let up: AgentProvider;
let advisor: Worker;
let s: Splice;
let astra: Launched;

beforeAll(async () => {
  up = new AgentProvider("the provider").start();
  advisor = new Worker().start();
  s = await Splice.boot(head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }) + agentCapability("advisor", { heads: ["astra"], url: advisor.url }), KEYS);
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

/** How long the worker takes: long enough that a client which waited for it could not have run its tool meanwhile. */
const WORK_MS = 6000;

describe("agent-call-parallel", () => {
  test("the client gets only its own call, and its result reaches the provider beside the capability's, in the model's order", async () => {
    const m = `m${crypto.randomUUID().slice(0, 8)}`;
    const [mineId, theirsId] = [toolId(), toolId()];
    const before = [thinking(`Read the module and ask for a review at once (${m}).`), text("I'll read the module and ask for a review.")];
    // the capability's call first, then the client's: its result has to go before the client's
    const asks = [call(theirsId, ADVISOR.name, { question: `Is moving the retry ladder sound? (${m})` }), call(mineId, "Read", { file_path: "src/retry.ts" })];
    const last = answer(text("The module is small and the review agrees. Moving it now."));
    up.plan(m, [{ blocks: [...before, ...asks], stop: "tool_use" }, last]);
    advisor.work = { ...IDLE, waitMs: WORK_MS };

    const { session, mine, sent } = await first(astra, `Move the retry ladder into its own module (${m}).`);
    const ended = sent.sentAt + sent.totalMs;
    expect(sent.status).toBe(200);
    expect(sent.error).toBeNull();
    const got = assemble(sent.body);
    expect(got.count("message_start")).toBe(1);
    expect(got.count("message_stop")).toBe(1);
    expect(got.indices).toEqual([0, 1, 2]);
    expect(got.blocks).toEqual([...before, asks[1]!]);
    expect(got.stops).toEqual(["tool_use"]);
    expect(sent.body).not.toContain(ADVISOR.name);

    // the worker was asked, and the client's response ended before the worker answered: the client runs its tool
    // while the worker works
    expect(advisor.of(session).length).toBe(1);
    const asked = advisor.of(session)[0]!;
    expect(ended).toBeLessThan(asked.at + WORK_MS);

    await Bun.sleep(300);
    const read: Block = { ...result(mineId, "export function retry() {}\n"), cache_control: { type: "ephemeral", ttl: "1h" } };
    const resultsAt = Date.now();
    expect(asked.at).toBeLessThan(resultsAt);
    const back = await send(astra, session, [
      { role: "user", content: unmarked(mine) },
      { role: "assistant", content: got.blocks },
      { role: "user", content: [read] },
    ]);
    expect(back.status).toBe(200);
    expect(back.error).toBeNull();
    expect(assemble(back.body).blocks).toEqual(last.blocks);

    const sentUp = up.with(m);
    expect(sentUp.length).toBe(2);
    const messages = unmarkedAll(sentUp[1]!.body.messages);
    expect(messages.length).toBe(3);
    expect(messages[0]).toEqual({ role: "user", content: unmarked(mine) });
    expect(messages[1]).toEqual({ role: "assistant", content: [...before, ...asks] });
    expect(messages[2]!.role).toBe("user");
    const results = messages[2]!.content as Block[];
    expect(results.map((b) => [b.type, b.tool_use_id])).toEqual([
      ["tool_result", theirsId],
      ["tool_result", mineId],
    ]);
    expect(resultText(results[0])).toBe(advisor.text(asked));
    expect(results[1]).toEqual(unmarkedAll(read));
    expect(advisor.of(session).length).toBe(1);
    expect(up.refused.length).toBe(0);
  }, 60_000);
});
