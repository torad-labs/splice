// agent-call-says: while the agent's call holds a turn, the session's status line says what the capability's message
// says, then each progress line its worker sends, the way a turn held by a rule's call does; when the answer goes on,
// the line is what it was. (docs/specs/harness.md, decisions 7 and 12.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { AgentProvider, agentCapability, answer, asking, text, thinking, toolId } from "./lib/agent.ts";
import { IDLE, KEYS, Worker, first, head, statusLine } from "./lib/capability.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const ASKING = "Asking the advisor";
const STEPS = ["Reading the plan", "Weighing the risks"];
/** How long the worker is silent before it says each step, and after the last. */
const GAP_MS = 6000;

let up: AgentProvider;
let advisor: Worker;
let s: Splice;
let astra: Launched;

beforeAll(async () => {
  up = new AgentProvider("the provider").start();
  advisor = new Worker().start();
  s = await Splice.boot(
    head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }) + agentCapability("advisor", { heads: ["astra"], url: advisor.url, message: ASKING, color: "magenta" }),
    KEYS,
  );
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

describe("agent-call-says", () => {
  test("the held call's line says the capability's message, then each step the worker sends, and is what it was afterwards", async () => {
    const m = `m${crypto.randomUUID().slice(0, 8)}`;
    up.plan(m, [asking(toolId(), `Is the plan sound? (${m})`, [thinking(`Checking (${m}).`), text("Asking for a review.")]), answer(text("The review agrees."))]);
    advisor.work = { ...IDLE, waitMs: GAP_MS, progress: STEPS, gapMs: GAP_MS };

    const sending = first(astra, `Move the retry ladder (${m}).`);
    let session = "";
    await until("the worker is asked", () => {
      const a = advisor.asked.find((x) => String((x.body.fields?.input as { question?: string } | undefined)?.question ?? "").includes(m));
      session = String(a?.body.session ?? "");
      return session !== "";
    });
    const from = Date.now();
    const at = async (ms: number) => {
      await Bun.sleep(Math.max(0, from + ms - Date.now()));
      return statusLine(astra, session);
    };

    const asking1 = await at(GAP_MS / 2);
    expect(asking1).toContain(ASKING);
    expect(asking1.indexOf(ASKING)).toBeLessThan(asking1.indexOf("astra-large"));
    const reading = await at(GAP_MS * 1.5);
    expect(reading).toContain(STEPS[0]!);
    expect(reading).not.toContain(ASKING);
    const weighing = await at(GAP_MS * 2.5);
    expect(weighing).toContain(STEPS[1]!);
    expect(weighing).not.toContain(STEPS[0]!);

    const { sent } = await sending;
    expect(sent.status).toBe(200);
    expect(sent.error).toBeNull();
    const after = statusLine(astra, session);
    expect(after).not.toContain(STEPS[1]!);
    expect(after).toContain("astra-large");
  }, 120_000);
});
