// df-turn-headers: the row of a door turn on /api/perf/turns carries the Claude Code session, agent and parent agent
// it came from (`x-claude-code-session-id`, `x-claude-code-agent-id`, `x-claude-code-parent-agent-id`), so a
// subagent's turns are told apart from the turns of the session that started it.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Door, doorTurn, rows, slottedHead, turnOn } from "../desktop/lib/desktop.ts";

setDefaultTimeout(120_000);

const ASTRA = "claude-astra--astra-large";

let astra: Upstream;
let s: Splice;
let d: Door;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  s = await Splice.boot(slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }));
  d = turnOn(s, "--model", ASTRA);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
});

describe("df-turn-headers", () => {
  test("a session's turn and its subagent's turn each carry who sent them", async () => {
    const session = crypto.randomUUID();
    const agent = `agent-${crypto.randomUUID().slice(0, 8)}`;
    const parent = `agent-${crypto.randomUUID().slice(0, 8)}`;
    expect((await doorTurn(d.url, { key: d.key, model: ASTRA, session })).status).toBe(200);
    const t = await fetch(`${d.url}/v1/messages?beta=true`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "anthropic-version": "2023-06-01",
        authorization: `Bearer ${d.key}`,
        "x-claude-code-session-id": session,
        "x-claude-code-agent-id": agent,
        "x-claude-code-parent-agent-id": parent,
      },
      body: JSON.stringify({ model: "claude-haiku-5-5", max_tokens: 64, stream: false, messages: [{ role: "user", content: "subagent work" }] }),
    });
    expect(t.status).toBe(200);
    await until("both rows", async () => (await rows(s, "astra", session)).length >= 2);
    const [main, sub] = await rows(s, "astra", session);
    expect(main!.door).toBe("desktop");
    expect(main!.agent_id ?? null).toBeNull();
    expect(sub!.agent_id).toBe(agent);
    expect(sub!.parent_agent_id).toBe(parent);
    expect(sub!.session_id).toBe(session);
  });
});
