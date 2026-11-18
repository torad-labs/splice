// agent-call-offered: a capability declared `call = "agent"` or `"both"` names a tool, and splice offers it to the
// model: on every request that carries tools, of a thread the rule allows (`when.thread`, `when.mode`,
// `when.models`; `turn` does not apply), the tool goes after the client's tools as the same bytes every time, and the
// client's tools and system text are not changed. A request without tools, one of a thread or mode or model the rule
// leaves out, one on a head the capability is not on for, and every request of a capability with `call = "rule"`
// are not offered it. A declaration that names the call wrongly is a config finding.
// (docs/specs/harness.md, decisions 1, 2 and 12.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync } from "node:fs";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { ADVISOR, AgentProvider, agentCapability } from "./lib/agent.ts";
import { IDLE, KEYS, type Message, PRINT, type SendOptions, type SeenTurn, Worker, capability, first, head, opening, second, send } from "./lib/capability.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const SYSTEM = "You are Claude Code, Anthropic's official CLI for Claude.";

let up: AgentProvider;
/** `advisor` is the model's to call; `both` is the model's and the rule's; `brief` is the rule's alone. */
let advisor: Worker;
let both: Worker;
let brief: Worker;
let s: Splice;
/** astra: advisor (main thread, interactive, astra-large only); cirrus: both; draco: brief; borealis: none. */
let astra: Launched;
let cirrus: Launched;
let draco: Launched;
let borealis: Launched;

/** The tool as the provider is offered it: the declaration's name, description and input schema. */
const OFFERED = { name: ADVISOR.name, description: ADVISOR.description, input_schema: ADVISOR.input };

type Tools = { name: string; [k: string]: unknown }[];
const toolsOf = (t: SeenTurn): Tools => (t.body.tools as Tools | undefined) ?? [];

/** What the provider is sent for this request today, on a head with no capability. */
async function asToday(messages: Message[], o: SendOptions = {}): Promise<SeenTurn> {
  const session = crypto.randomUUID();
  await send(borealis, session, messages, o);
  return up.of(session).at(-1)!;
}

describe("agent-call-offered", () => {
  beforeAll(async () => {
    up = new AgentProvider("the provider").start();
    advisor = new Worker().start();
    both = new Worker().start();
    brief = new Worker().start();
    s = await Splice.boot(
      head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url, small: "astra-small" }) +
        head({ key: "cirrus", command: "claude-cirrus", port: await freePort(), url: up.url }) +
        head({ key: "draco", command: "claude-draco", port: await freePort(), url: up.url }) +
        head({ key: "borealis", command: "claude-borealis", port: await freePort(), url: up.url }) +
        agentCapability("advisor", { heads: ["astra"], url: advisor.url, when: '{ thread = "main", mode = "interactive", models = ["astra-large"] }' }) +
        agentCapability("both", { heads: ["cirrus"], url: both.url, call: "both", extra: 'place = "first-user-message"\nmay = "add"' }) +
        capability("brief", { heads: ["draco"], url: brief.url }),
      KEYS,
    );
    astra = await launch(s, "claude-astra");
    cirrus = await launch(s, "claude-cirrus");
    draco = await launch(s, "claude-draco");
    borealis = await launch(s, "claude-borealis");
  }, 300_000);

  afterAll(async () => {
    await s?.dispose();
    for (const each of [up, advisor, both, brief]) each?.stop();
  });

  afterEach(() => {
    for (const each of [advisor, both, brief]) each.work = IDLE;
  });

  test("the tool goes after the client's tools, as the same bytes on every request of the thread, and nothing of the client's changes", async () => {
    const { session, mine, sent } = await first(astra, "Plan the move of the retry ladder.", { system: SYSTEM });
    expect(sent.status).toBe(200);
    expect(sent.body).toContain(up.reply());
    const later = second(mine);
    expect((await send(astra, session, later, { system: SYSTEM })).status).toBe(200);
    // a thread first seen mid-conversation is offered it too: `turn` does not apply
    const midway = crypto.randomUUID();
    expect((await send(astra, midway, later, { system: SYSTEM })).status).toBe(200);

    const got = [...up.of(session), ...up.of(midway)];
    expect(got.length).toBe(3);
    const plain = [await asToday([{ role: "user", content: mine }], { system: SYSTEM }), await asToday(later, { system: SYSTEM }), await asToday(later, { system: SYSTEM })];
    const bytes = new Set<string>();
    got.forEach((t, i) => {
      const tools = toolsOf(t);
      const client = toolsOf(plain[i]!);
      expect(tools.length).toBe(client.length + 1);
      expect(tools.slice(0, client.length)).toEqual(client);
      expect(tools.at(-1)).toEqual(OFFERED);
      bytes.add(JSON.stringify(tools.at(-1)));
      expect(t.body.system).toEqual(plain[i]!.body.system);
      expect(t.body.messages).toEqual(plain[i]!.body.messages);
    });
    expect(bytes.size).toBe(1);
    // offering is not calling
    expect(advisor.asked.length).toBe(0);
  });

  test("a request without tools, a subagent's, a print-mode one and one for a model the rule does not name are not offered it", async () => {
    const typed = opening("Plan the move of the retry ladder.");
    const cases: { what: string; messages: Message[]; o: SendOptions }[] = [
      { what: "a side request without tools", messages: [{ role: "user", content: [{ type: "text", text: "Write a title for this conversation." }] }], o: { tools: false } },
      { what: "a subagent's request (thread = main)", messages: [{ role: "user", content: typed }], o: { agent: "agent-a1b2c3" } },
      { what: "a print-mode request (mode = interactive)", messages: [{ role: "user", content: typed }], o: { userAgent: PRINT } },
      { what: "a request for astra-small (models = astra-large)", messages: [{ role: "user", content: typed }], o: { model: "claude-astra--astra-small" } },
    ];
    for (const c of cases) {
      const session = crypto.randomUUID();
      const sent = await send(astra, session, c.messages, c.o);
      expect(sent.status, c.what).toBe(200);
      const got = up.of(session).at(-1)!;
      expect(toolsOf(got).map((t) => t.name), c.what).not.toContain(ADVISOR.name);
      const plain = await asToday(c.messages, { ...c.o, model: undefined });
      expect(got.body.tools, c.what).toEqual(plain.body.tools);
      expect(got.body.messages, c.what).toEqual(plain.body.messages);
    }
    expect(advisor.asked.length).toBe(0);
  });

  test('a head the capability is not on for, and a capability with call = "rule", offer nothing', async () => {
    for (const [what, l] of [
      ["a head with no capability", borealis],
      ['a head whose capability has call = "rule"', draco],
    ] as const) {
      const { session, sent } = await first(l, "Plan the move of the retry ladder.");
      expect(sent.status, what).toBe(200);
      expect(toolsOf(up.of(session).at(-1)!).map((t) => t.name), what).toEqual(["Read"]);
    }
    // the rule's capability still runs by its rule
    expect(brief.asked.length).toBe(1);
  });

  test('call = "both" offers the tool and still runs by its rule on the first turn', async () => {
    const { session, sent } = await first(cirrus, "Plan the move of the retry ladder.");
    expect(sent.status).toBe(200);
    const got = up.of(session).at(-1)!;
    expect(toolsOf(got).at(-1)).toEqual(OFFERED);
    expect(both.of(session).length).toBe(1);
    // the rule's call is asked with the fields `send` names, and no `input`: the model called nothing
    expect(Object.keys(both.of(session)[0]!.body.fields ?? {})).not.toContain("input");
  });
});

describe("agent-call-offered: config", () => {
  test("a call declared wrongly stops the daemon from starting, every finding at once, and a right one is not among them", async () => {
    const at = { url: "http://127.0.0.1:9/work" };
    const s2 = await Splice.create(
      head({ key: "astra", command: "claude-astra", port: await freePort(), url: "http://127.0.0.1:9" }) +
        mockHead({ key: "chatty", command: "claude-chatty", port: await freePort(), url: "http://127.0.0.1:9", model: "chat-large" }) +
        agentCapability("well-agent", { heads: ["astra"], ...at, when: '{ thread = "main" }' }) +
        agentCapability("well-both", { heads: ["astra"], ...at, call: "both", extra: 'place = "first-user-message"\nmay = "add"' }) +
        agentCapability("agent-toolless", { heads: ["astra"], ...at, tool: null }) +
        agentCapability("both-toolless", { heads: ["astra"], ...at, call: "both", tool: null, extra: 'place = "first-user-message"\nmay = "add"' }) +
        agentCapability("rule-tooled", { heads: ["astra"], ...at, call: "rule", extra: 'place = "first-user-message"\nmay = "add"' }) +
        agentCapability("agent-placed", { heads: ["astra"], ...at, extra: 'place = "first-user-message"' }) +
        agentCapability("agent-turned", { heads: ["astra"], ...at, when: '{ turn = "first" }' }) +
        agentCapability("agent-chat", { heads: ["chatty"], ...at }),
      KEYS,
    );
    try {
      await expect(s2.start()).rejects.toThrow();
      const lines = readFileSync(s2.log, "utf8").split("\n");
      const finding = (name: string, says: RegExp) => lines.some((line) => line.includes(name) && says.test(line));
      expect(finding("agent-toolless", /\btool\b/), "agent with no tool names `tool`").toBe(true);
      expect(finding("both-toolless", /\btool\b/), "both with no tool names `tool`").toBe(true);
      expect(finding("rule-tooled", /\btool\b/), 'a tool with call = "rule" names `tool`').toBe(true);
      expect(finding("agent-placed", /\bplace\b/), 'place with call = "agent" names `place`').toBe(true);
      expect(finding("agent-turned", /\bturn\b/), 'when.turn with call = "agent" names `turn`').toBe(true);
      expect(finding("agent-chat", /dialect|chatty/), "an agent call on an openai-chat head names the dialect or the head").toBe(true);
      expect(lines.some((line) => line.includes("well-agent") || line.includes("well-both"))).toBe(false);
    } finally {
      await s2.dispose();
    }
  }, 120_000);
});
