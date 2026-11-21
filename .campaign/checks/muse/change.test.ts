// muse-tool-change: a tool a server adds to a Muse conversation, or takes away from it, costs the session only what
// the change adds. Claude Code tells the change in a system message (`tool_addition`, `tool_removal`); the request
// that carries it, and every request after it, reads everything the conversation had already sent from Meta's cache.
// An added tool is the model's to call under its own name, and the model is told which tool went away.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { launch, type Launched } from "../speed/lib/session.ts";
import { Conversation, call, pasted, tool, type Tool } from "./lib/claude.ts";
import { MUSE_ENV, MetaBackend, museHead, type Turn } from "./lib/meta.ts";

let backend: MetaBackend;
let s: Splice;
let muse: Launched;

beforeAll(async () => {
  backend = MetaBackend.start();
  s = await Splice.boot(museHead({ command: "claude-muse", port: await freePort(), backend: backend.url, dir: `/tmp/claude-1000/muse-change-${process.pid}` }), MUSE_ENV);
  muse = await launch(s, "claude-muse");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  backend?.stop();
});

const DOCS = tool("mcp__docs__create_doc", ["title"]);
const DOCS_EDIT = tool("mcp__docs__update_doc", ["id", "text"]);
const DESK = tool("mcp__desk__run_process", ["command"]);
/** A name longer than the 64 characters Meta takes for a tool. */
const LONG = tool("mcp__claude_ai_Team_Workspace_Calendar__list_the_events_of_one_person_for_a_week", ["person"]);

/** The request the backend was sent last. */
function last(): Turn {
  const t = backend.turns().at(-1);
  if (!t) throw new Error("the backend was sent nothing");
  return t;
}

/** A conversation with a history behind it, whose Claude Code started with `extra` beside its own tools. */
async function started(extra: Tool[] = []): Promise<Conversation> {
  const c = new Conversation(muse, [tool("Read"), tool("Bash"), tool("ToolSearch", ["query"]), ...extra]);
  expect((await c.say(`${pasted(12000)}\nSay OK.`).send()).text).toBe("OK");
  expect((await c.say("Say OK again.").send()).text).toBe("OK");
  return c;
}

/** What the request `after` carries beyond the request `before`, as the backend was sent it. */
function added(before: Turn, after: Turn): string {
  return JSON.stringify((after.body.input as unknown[]).slice((before.body.input as unknown[]).length));
}

describe("muse-tool-change", () => {
  test("the request that carries tools a server added reads everything before it from the cache", async () => {
    const c = await started();
    const before = last();
    expect((await c.added([DOCS, DOCS_EDIT]).say("Say OK.").send()).text).toBe("OK");
    const after = last();
    expect(after.cached).toBeGreaterThanOrEqual(before.input);
    expect(after.fresh).toBeLessThan(600);
    expect((await c.say("Say OK once more.").send()).text).toBe("OK");
    expect(last().cached).toBeGreaterThanOrEqual(after.input);
  });

  test("an added tool is the model's to call, and the call reaches Claude Code under the tool's own name", async () => {
    const c = await started();
    const before = last();
    const reply = await c.added([DOCS, LONG]).say(call("mcp__docs__create_doc", { title: "Notes" })).send();
    const after = last();
    expect(after.cached).toBeGreaterThanOrEqual(before.input);
    expect(reply.calls.map((x) => ({ name: x.name, input: x.input }))).toEqual([{ name: "mcp__docs__create_doc", input: { title: "Notes" } }]);
    const long = await c.result(reply.calls[0]!.id, "created", call(LONG.name, { person: "ana" })).send();
    expect(long.calls.map((x) => ({ name: x.name, input: x.input }))).toEqual([{ name: LONG.name, input: { person: "ana" } }]);
    expect(last().cached).toBeGreaterThanOrEqual(after.input);
  });

  test("a tool the conversation started with is taken away: the cache stands, and the model is told which tool went", async () => {
    const c = await started([DESK]);
    const before = last();
    expect((await c.removed([DESK.name]).say("Say OK.").send()).text).toBe("OK");
    const after = last();
    expect(after.cached).toBeGreaterThanOrEqual(before.input);
    expect(added(before, after)).toContain(DESK.name);
    expect((await c.say("Say OK once more.").send()).text).toBe("OK");
    expect(last().cached).toBeGreaterThanOrEqual(after.input);
  });

  test("an added tool is taken away again: the cache stands, and the model is told which tool went", async () => {
    const c = await started();
    expect((await c.added([DOCS, DOCS_EDIT]).say("Say OK.").send()).text).toBe("OK");
    expect((await c.say("Say OK again.").send()).text).toBe("OK");
    const before = last();
    expect((await c.removed([DOCS.name]).say("Say OK.").send()).text).toBe("OK");
    const after = last();
    expect(after.cached).toBeGreaterThanOrEqual(before.input);
    expect(added(before, after)).toContain(DOCS.name);
    // The tool that stayed is still the model's to call.
    const reply = await c.say(call("mcp__docs__update_doc", { id: "1", text: "more" })).send();
    expect(reply.calls.map((x) => x.name)).toEqual(["mcp__docs__update_doc"]);
  });

  // The stand-in's model calls a tool only with arguments that fit the schema it holds for it.
  test("a tool that comes back with a new schema is called with the new one", async () => {
    const c = await started();
    expect((await c.added([DOCS]).say("Say OK.").send()).text).toBe("OK");
    expect((await c.removed([DOCS.name]).say("Say OK.").send()).text).toBe("OK");
    const before = last();
    const again = tool(DOCS.name, ["title", "folder"]);
    const reply = await c.added([again]).say(call(DOCS.name, { title: "Notes", folder: "team" })).send();
    const after = last();
    expect(after.cached).toBeGreaterThanOrEqual(before.input);
    expect(reply.calls.map((x) => ({ name: x.name, input: x.input }))).toEqual([{ name: DOCS.name, input: { title: "Notes", folder: "team" } }]);
  });
});
