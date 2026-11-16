// muse-tool-load: a tool Claude Code's ToolSearch loads in the middle of a Muse conversation costs the session only
// what the load adds. The request that carries the load, and every request after it, reads everything the
// conversation had already sent from Meta's cache, and the loaded tool is the model's to call under its own name.
// On 2026-11-16 each load made Meta read the whole conversation again at the full price: 26 loads, 2.69 million
// tokens, in one five-hour window.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { launch, type Launched } from "../speed/lib/session.ts";
import { Conversation, call, pasted, tool, type Reply, type Tool } from "./lib/claude.ts";
import { MUSE_ENV, MetaBackend, museHead, type Turn } from "./lib/meta.ts";

let backend: MetaBackend;
let s: Splice;
let muse: Launched;

beforeAll(async () => {
  backend = MetaBackend.start();
  s = await Splice.boot(museHead({ command: "claude-muse", port: await freePort(), backend: backend.url, dir: `/tmp/claude-1000/muse-load-${process.pid}` }), MUSE_ENV);
  muse = await launch(s, "claude-muse");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  backend?.stop();
});

const SEND = tool("SendMessage", ["to", "message"]);
const STOP = tool("TaskStop", ["task_id"]);
const MONITOR = tool("Monitor", ["command"]);
const EXA = tool("mcp__exa__web_search_exa", ["query"]);

/** The request the backend was sent last. */
function last(): Turn {
  const t = backend.turns().at(-1);
  if (!t) throw new Error("the backend was sent nothing");
  return t;
}

/** A conversation with a history behind it: the person pasted a long file and the model answered twice. */
async function started(agent?: string): Promise<Conversation> {
  const c = new Conversation(muse, [tool("Read"), tool("Bash"), tool("ToolSearch", ["query"])], agent);
  expect((await c.say(`${pasted(12000)}\nSay OK.`).send()).text).toBe("OK");
  expect((await c.say("Say OK again.").send()).text).toBe("OK");
  return c;
}

/** The model searches for `tools` and Claude Code's ToolSearch loads them. Answers the request before the load, the
 *  request that carried it, and what the model said to it. `then` rides behind the search's result. */
async function load(c: Conversation, tools: Tool[], then?: string): Promise<{ before: Turn; after: Turn; reply: Reply }> {
  const search = await c.say(call("ToolSearch", { query: `select:${tools.map((t) => t.name).join(",")}` })).send();
  expect(search.calls.map((x) => x.name)).toEqual(["ToolSearch"]);
  const before = last();
  const reply = await c.loaded(search.calls[0]!.id, tools, then).send();
  expect(reply.status).toBe(200);
  return { before, after: last(), reply };
}

describe("muse-tool-load", () => {
  test("the request that carries a loaded tool reads everything before it from the cache", async () => {
    const c = await started();
    const { before, after } = await load(c, [SEND]);
    expect(after.status).toBe(200);
    expect(after.cached).toBeGreaterThanOrEqual(before.input);
    // What it pays in full is the search, its result and the tool: a few hundred tokens, never the history.
    expect(after.fresh).toBeLessThan(600);
  });

  test("every request after the load reads the load from the cache too", async () => {
    const c = await started();
    const { after } = await load(c, [SEND]);
    expect((await c.say("Say OK.").send()).text).toBe("OK");
    const next = last();
    expect(next.cached).toBeGreaterThanOrEqual(after.input);
    expect((await c.say("Say OK once more.").send()).text).toBe("OK");
    expect(last().cached).toBeGreaterThanOrEqual(next.input);
  });

  test("the loaded tool is the model's to call, and the call reaches Claude Code under the tool's own name", async () => {
    const c = await started();
    const { before, after, reply } = await load(c, [SEND], call("SendMessage", { to: "ops", message: "hi" }));
    expect(after.cached).toBeGreaterThanOrEqual(before.input);
    expect(reply.calls.map((x) => ({ name: x.name, input: x.input }))).toEqual([{ name: "SendMessage", input: { to: "ops", message: "hi" } }]);
    expect((await c.result(reply.calls[0]!.id, "sent").send()).text).toBe("OK");
    expect(last().cached).toBeGreaterThanOrEqual(after.input);
  });

  test("one search that loads three tools costs what one does, and each is the model's to call", async () => {
    const c = await started();
    const { before, after, reply } = await load(c, [STOP, MONITOR, EXA], call("Monitor", { command: "tail" }));
    expect(after.cached).toBeGreaterThanOrEqual(before.input);
    expect(reply.calls.map((x) => x.name)).toEqual(["Monitor"]);
    const exa = await c.result(reply.calls[0]!.id, "watching", call("mcp__exa__web_search_exa", { query: "muse" })).send();
    expect(exa.calls.map((x) => ({ name: x.name, input: x.input }))).toEqual([{ name: "mcp__exa__web_search_exa", input: { query: "muse" } }]);
    expect(last().cached).toBeGreaterThanOrEqual(after.input);
  });

  test("a second load, later in the conversation, costs only what it adds, and the first tool stays callable", async () => {
    const c = await started();
    await load(c, [SEND]);
    expect((await c.say("Say OK.").send()).text).toBe("OK");
    const { before, after, reply } = await load(c, [STOP], call("SendMessage", { to: "ops", message: "still here" }));
    expect(after.cached).toBeGreaterThanOrEqual(before.input);
    expect(reply.calls.map((x) => x.name)).toEqual(["SendMessage"]);
    const stop = await c.result(reply.calls[0]!.id, "sent", call("TaskStop", { task_id: "7" })).send();
    expect(stop.calls.map((x) => ({ name: x.name, input: x.input }))).toEqual([{ name: "TaskStop", input: { task_id: "7" } }]);
  });

  test("a subagent's load costs only what it adds", async () => {
    const c = await started("agent-a41f07c2d9e85b36");
    const { before, after, reply } = await load(c, [SEND], call("SendMessage", { to: "lead", message: "done" }));
    expect(after.cached).toBeGreaterThanOrEqual(before.input);
    expect(reply.calls.map((x) => x.name)).toEqual(["SendMessage"]);
  });

  test("the request that compacts a conversation with a loaded tool reads all of it from the cache", async () => {
    const c = await started();
    await load(c, [SEND]);
    expect((await c.say("Say OK.").send()).text).toBe("OK");
    const before = last();
    expect((await c.compact()).status).toBe(200);
    expect(last().cached).toBeGreaterThanOrEqual(before.input);
  });

  test("a conversation picked up after the daemon restarts still reads its loaded tool from the cache", async () => {
    const c = await started();
    await load(c, [SEND]);
    expect((await c.say("Say OK.").send()).text).toBe("OK");
    const before = last();
    await s.restart();
    const reply = await c.say(call("SendMessage", { to: "ops", message: "back" })).send();
    expect(reply.calls.map((x) => x.name)).toEqual(["SendMessage"]);
    expect(last().cached).toBeGreaterThanOrEqual(before.input);
  }, 300_000);
});
