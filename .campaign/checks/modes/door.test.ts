// modes-door: a door mode that is on for a session's head and caller is offered to its model, and answered inside
// splice. Its tool goes after the client's tools as `mcp__<server>__<tool>` with the mode's schema and description,
// its prompt.md once at the end of the system text, the same bytes on every request. When the model calls it, the
// mode's handler answers inside splice: the client never sees the call and gets one assistant message, the provider
// gets the handler's text as the call's result (an error result when the handler refuses the input), and later
// requests carry the call and its result back where the model made them. A head splice.toml turns it off for, Claude
// Desktop's own sessions (the template's manifest leaves them out) and a splice with no [modes.template] are not
// offered it; a session on a head's own command and the terminal's `claude` on the door are.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { turnOn } from "../desktop/lib/desktop.ts";
import { session as doorSession, toDoor } from "../door/lib/claude.ts";
import { AgentProvider, answer, assemble, call, resultText, text, toolId } from "../harness/lib/agent.ts";
import { type Block, KEYS, type Message, blocks, first, head, send, unmarked } from "../harness/lib/capability.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { TOOL, doorSend, inJson, mark, modesTable, occurrences, requireTemplate, systemText, templatePrompt, templateTool } from "./lib/modes.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const SYSTEM = "You are Claude Code, Anthropic's official CLI for Claude.";
const ASTRA = "claude-astra--astra-large";

let up: AgentProvider;
let s: Splice;
let astra: Launched;
let borealis: Launched;

type Tools = { name: string; [k: string]: unknown }[];
const toolsOf = (body: { [k: string]: unknown }): Tools => (body.tools as Tools | undefined) ?? [];
const names = (body: { [k: string]: unknown }) => toolsOf(body).map((t) => t.name);

beforeAll(async () => {
  requireTemplate();
  up = new AgentProvider("the provider").start();
  s = await Splice.boot(
    head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }) +
      head({ key: "borealis", command: "claude-borealis", port: await freePort(), url: up.url }) +
      modesTable("template", "on = true", "heads = { borealis = false }"),
    KEYS,
  );
  astra = await launch(s, "claude-astra");
  borealis = await launch(s, "claude-borealis");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
});

describe("modes-door", () => {
  test("the tool goes after the client's tools and the prompt at the end of the system text, once, the same bytes every request", async () => {
    const { session, mine, sent } = await first(astra, `Plan the move ${mark()}.`, { system: SYSTEM });
    expect(sent.status).toBe(200);
    const later: Message[] = [
      { role: "user", content: unmarked(mine) },
      { role: "assistant", content: [{ type: "text", text: "Done." }] },
      { role: "user", content: [{ type: "text", text: "And after that?" }] },
    ];
    expect((await send(astra, session, later, { system: SYSTEM })).status).toBe(200);
    const got = up.of(session);
    expect(got.length).toBe(2);
    const prompt = templatePrompt();
    for (const t of got) {
      expect(names(t.body)).toEqual(["Read", TOOL]);
      expect(toolsOf(t.body).at(-1)).toEqual(templateTool());
      expect(systemText(t.body).trimEnd().endsWith(prompt)).toBe(true);
      expect(systemText(t.body).startsWith(SYSTEM)).toBe(true);
      expect(occurrences(t.raw, inJson(prompt)), "the prompt is placed once").toBe(1);
    }
    expect(JSON.stringify(toolsOf(got[1]!.body).at(-1))).toBe(JSON.stringify(toolsOf(got[0]!.body).at(-1)));
    expect(JSON.stringify(got[1]!.body.system)).toBe(JSON.stringify(got[0]!.body.system));
  });

  test("a call is answered inside splice: the client sees one message and no call, the provider gets the handler's text", async () => {
    const m = mark();
    const id = toolId();
    up.plan(m, [{ blocks: [text("Saying it back."), call(id, TOOL, { text: m })], stop: "tool_use" }, answer(text(`It said ${m}.`))]);
    const { session, mine, sent } = await first(astra, `say back ${m}`);
    expect(sent.status).toBe(200);
    expect(sent.body).not.toContain(TOOL);
    const got = assemble(sent.body);
    expect(got.count("message_start")).toBe(1);
    expect(got.count("message_stop")).toBe(1);
    expect(got.blocks.some((b) => b.type === "tool_use")).toBe(false);
    expect(got.blocks.map((b) => b.text ?? "").join("")).toContain(`It said ${m}.`);
    const asked = up.of(session);
    expect(asked.length).toBe(2);
    const results = blocks(asked[1]!.body.messages.at(-1)).filter((b) => b.type === "tool_result");
    expect(results.length).toBe(1);
    expect(results[0]!.tool_use_id).toBe(id);
    expect(resultText(results[0])).toBe(m);
    expect(results[0]!.is_error ?? false).toBe(false);

    // The client's next request holds its own copy; the provider is sent the call and its result back where they were.
    const next: Message[] = [
      { role: "user", content: unmarked(mine) },
      { role: "assistant", content: got.blocks as Block[] },
      { role: "user", content: [{ type: "text", text: "Thanks." }] },
    ];
    expect((await send(astra, session, next)).status).toBe(200);
    expect(up.refused.length, JSON.stringify(up.refused.map((r) => r.why))).toBe(0);
    const third = up.of(session).at(-1)!;
    const msgs = third.body.messages;
    const at = msgs.findIndex((x) => x.role === "assistant" && blocks(x).some((b) => b.type === "tool_use" && b.id === id && b.name === TOOL));
    expect(at, "the call is put back").toBeGreaterThan(0);
    expect(resultText(blocks(msgs[at + 1]).find((b) => b.type === "tool_result" && b.tool_use_id === id))).toBe(m);
  });

  test("input the handler refuses comes back to the model as an error result, and the turn goes on", async () => {
    const m = mark();
    const id = toolId();
    up.plan(m, [{ blocks: [call(id, TOOL, { words: m })], stop: "tool_use" }, answer(text(`Could not ${m}.`))]);
    const { session, sent } = await first(astra, `say back ${m}`);
    expect(sent.status).toBe(200);
    expect(sent.body).not.toContain(TOOL);
    expect(assemble(sent.body).blocks.map((b) => b.text ?? "").join("")).toContain(`Could not ${m}.`);
    const result = blocks(up.of(session)[1]!.body.messages.at(-1)).find((b) => b.type === "tool_result");
    expect(result?.is_error).toBe(true);
  });

  test("a head splice.toml turns it off for is not offered it", async () => {
    const { session, sent } = await first(borealis, `Plan ${mark()}.`);
    expect(sent.status).toBe(200);
    expect(names(up.of(session).at(-1)!.body)).toEqual(["Read"]);
    expect(systemText(up.of(session).at(-1)!.body)).not.toContain(templatePrompt());
  });

  test("the terminal's claude on the door is offered it; Claude Desktop's own sessions are not", async () => {
    toDoor(s);
    const x = await doorSession(s);
    const here = crypto.randomUUID();
    const t = await doorSend(x.url, x.key, here, [{ role: "user", content: [{ type: "text", text: `Plan ${mark()}.` }] }], ASTRA);
    expect(t.status, t.body).toBe(200);
    expect(names(up.of(here).at(-1)!.body)).toEqual(["Read", TOOL]);

    const d = turnOn(s, "--model", ASTRA);
    const desk = crypto.randomUUID();
    const dt = await doorSend(d.url, d.key, desk, [{ role: "user", content: [{ type: "text", text: `Plan ${mark()}.` }] }], d.model || ASTRA);
    expect(dt.status, dt.body).toBe(200);
    const seen = up.of(desk).at(-1)!;
    expect(names(seen.body)).toEqual(["Read"]);
    expect(systemText(seen.body)).not.toContain(templatePrompt());
  });
});

describe("modes-door: shipped off", () => {
  test("with no [modes.template] in splice.toml the template is offered on no head", async () => {
    const plain = await Splice.boot(head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }), KEYS);
    try {
      const l = await launch(plain, "claude-astra");
      const { session, sent } = await first(l, `Plan ${mark()}.`);
      expect(sent.status).toBe(200);
      expect(names(up.of(session).at(-1)!.body)).toEqual(["Read"]);
    } finally {
      await plain.dispose();
    }
  }, 300_000);
});
