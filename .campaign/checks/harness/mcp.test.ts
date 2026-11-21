// mcp-worker: a capability's worker can be a tool of an MCP server the person runs: `worker = { kind = "mcp", server,
// tool }`. On the rule's turn splice calls that tool through the MCP hub, with the fields the declaration names as its
// arguments, and the tool's text is placed the way an HTTP worker's result is. The server is the one the hub would give
// a session in that session's folder, so a server a folder declares in its own .mcp.json is found for a session there.
// An error result, a result with no text, a server or tool the hub cannot find, and the time limit are failures that
// on_failure handles. An MCP worker with no server or no tool is a config finding, and `splice status` shows
// `mcp <server>/<tool>`. (docs/specs/harness.md, decisions 1, 4, 6 and 13.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { mkdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Splice, freePort } from "../server/lib/harness.ts";
import { type Launched, launch, sessionKey } from "../speed/lib/session.ts";
import { KEYS, type Message, Provider, type SendOptions, blocks, head, opening, rows, second, send, today } from "./lib/capability.ts";
import { McpServer, READY, approve, claudeJson, mcpCapability, projectFolder, seat } from "./lib/mcp.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const LIMIT_S = 4;

let up: Provider;
/** At user scope in the home's .claude.json, with the tools the capabilities name. */
let notes: McpServer;
/** Declared only in the project folder's own .mcp.json. */
let repo: McpServer;
let s: Splice;
/** The project folder that declares `repo`. */
let project: string;
/** `brief`: notes/brief, fails open. */
let astra: Launched;
/** No capability: what a request is today. */
let borealis: Launched;
/** `gate`: notes/gate, fails closed. */
let cirrus: Launched;
/** `repo`: repo-notes/brief, fails open. */
let draco: Launched;
/** `lost`: notes/missing, a tool the server does not have, fails open. */
let lyra: Launched;
/** `gone`: nowhere/brief, a server nobody declares, fails open. */
let vega: Launched;
/** `astray`: nowhere/brief, fails closed. */
let orion: Launched;
/** `untooled`: notes/missing, fails closed. */
let mensa: Launched;

/** The first turn of a new session of `l` running in `folder` (the home, unless named): what the person typed. */
async function first(l: Launched, typed: string, folder = s.home, o: SendOptions = {}) {
  const session = crypto.randomUUID();
  seat(l, session, folder);
  const mine = opening(typed);
  return { session, mine, sent: await send(l, session, [{ role: "user", content: mine }], o) };
}

const asToday = (messages: Message[]) => today(borealis, up, messages);

describe("mcp-worker", () => {
  beforeAll(async () => {
    up = new Provider("the provider").start();
    const keys = ["astra", "borealis", "cirrus", "draco", "lyra", "vega", "orion", "mensa"];
    const tables = await Promise.all(keys.map(async (key) => head({ key, command: `claude-${key}`, port: await freePort(), url: up.url })));
    s = await Splice.create(
      tables.join("") +
        mcpCapability("brief", { heads: ["astra"], server: "notes", tool: "brief", send: ["first_user_message", "model"], timeout: LIMIT_S }) +
        mcpCapability("gate", { heads: ["cirrus"], server: "notes", tool: "gate", timeout: LIMIT_S, onFailure: "closed" }) +
        mcpCapability("repo", { heads: ["draco"], server: "repo-notes", tool: "brief", timeout: LIMIT_S }) +
        mcpCapability("lost", { heads: ["lyra"], server: "notes", tool: "missing", timeout: LIMIT_S }) +
        mcpCapability("gone", { heads: ["vega"], server: "nowhere", tool: "brief", timeout: LIMIT_S }) +
        mcpCapability("astray", { heads: ["orion"], server: "nowhere", tool: "brief", timeout: LIMIT_S, onFailure: "closed" }) +
        mcpCapability("untooled", { heads: ["mensa"], server: "notes", tool: "missing", timeout: LIMIT_S, onFailure: "closed" }),
      KEYS,
    );
    const servers = join(s.home, "mcp-servers");
    notes = new McpServer("notes", servers, ["brief", "gate"]);
    repo = new McpServer("repo-notes", servers, ["brief"]);
    claudeJson(s.home, [notes]);
    project = join(s.home, "work", "app");
    mkdirSync(project, { recursive: true });
    projectFolder(project, [repo]);
    try {
      await s.start();
    } catch (e) {
      await s.dispose();
      throw e;
    }
    astra = await launch(s, "claude-astra");
    borealis = await launch(s, "claude-borealis");
    cirrus = await launch(s, "claude-cirrus");
    draco = await launch(s, "claude-draco");
    lyra = await launch(s, "claude-lyra");
    vega = await launch(s, "claude-vega");
    orion = await launch(s, "claude-orion");
    mensa = await launch(s, "claude-mensa");
    approve(draco, project, [repo.name]);
  }, 300_000);

  afterAll(async () => {
    await s?.dispose();
    up?.stop();
  });

  afterEach(() => {
    if (notes) notes.script = READY(notes.tools);
    if (repo) repo.script = READY(repo.tools);
  });

  test("a capability with an MCP worker boots, and splice status shows mcp <server>/<tool>", () => {
    // biome-ignore lint/suspicious/noControlCharactersInRegex: the escape that starts a terminal colour
    const status = s.cli(["status"]).out.replace(/\u001b\[[0-9;]*m/g, "").split("\n");
    const of = (name: string) => status.find((line) => new RegExp(`(^|\\s)${name}(\\s|$)`).test(line)) ?? "";
    expect(of("brief")).toContain("astra");
    expect(of("brief")).toContain("mcp notes/brief");
    expect(of("gate")).toContain("mcp notes/gate");
    expect(of("repo")).toContain("mcp repo-notes/brief");
    expect(of("gone")).toContain("mcp nowhere/brief");
  });

  test("on the rule's turn the tool is called once, with the declared fields as its arguments and nothing else of the session's", async () => {
    const typed = "Add a --json flag to the status command. [mcp-args]";
    notes.answering(typed);
    const { session, sent } = await first(astra, typed);
    expect(sent.status).toBe(200);
    expect(sent.error).toBeNull();
    expect(sent.body).toContain(up.reply());

    const calls = notes.of(typed);
    expect(calls.length).toBe(1);
    const c = calls[0]!;
    expect(c.tool).toBe("brief");
    expect(String(c.arguments.first_user_message)).toContain(typed);
    expect(c.arguments.model).toBe("astra-large");
    // the declared fields go; decision 1's capability name and session and turn ids may go beside them; nothing else
    const keys = Object.keys(c.arguments);
    expect(keys).toEqual(expect.arrayContaining(["first_user_message", "model"]));
    for (const k of keys) expect(["first_user_message", "model", "capability", "session", "turn"]).toContain(k);
    const whole = JSON.stringify(c.arguments);
    expect(whole).not.toContain(KEYS.MOCK_UPSTREAM_KEY);
    expect(whole).not.toContain(sessionKey(astra));
    expect(whole).not.toContain(s.home);

    // the turn was held on the capability, as its record says
    const [row] = await rows(s, "astra", session);
    expect(row!.held_on).toBe("brief");
  });

  test("the tool's text reaches the provider in the first user message, after the client's blocks, and stays there", async () => {
    const typed = "Rename the --tail flag of the logs command to --last. [mcp-text]";
    notes.answering(typed);
    const { session, mine, sent } = await first(astra, typed);
    expect(sent.status).toBe(200);
    const got = up.of(session);
    expect(got.length).toBe(1);

    const plain = blocks((await asToday([{ role: "user", content: mine }]))[0]);
    const content = blocks(got[0]!.body.messages[0]);
    expect(content.length).toBe(plain.length + 1);
    expect(content.slice(0, plain.length)).toEqual(plain);
    const added = content.at(-1)!;
    expect(added.type).toBe("text");
    expect(added.text ?? "").toContain(notes.text(typed));
    expect(added.text ?? "").not.toMatch(/splice/i);

    // the next turn carries the same bytes, and the tool is not called again
    notes.script = { end: { text: "A second answer nobody should see." } };
    expect((await send(astra, session, second(mine))).status).toBe(200);
    const later = blocks(up.of(session).at(-1)!.body.messages[0]);
    expect(later.at(-1)).toEqual(added);
    expect(notes.of(typed).length).toBe(1);
  });

  test("open: an error result, a result with no text, and the time limit leave the turn as it is today", async () => {
    const ways: { what: string; end: "no-text" | { error: string } | { text: string }; waitMs?: number }[] = [
      { what: "answers an error result", end: { error: "The notes index is locked." } },
      { what: "answers with no text", end: "no-text" },
      { what: "passes its time limit", end: { text: "Too late to be placed." }, waitMs: 9000 },
    ];
    for (const w of ways) {
      notes.script = { end: w.end, waitMs: w.waitMs ?? 0 };
      const typed = `Explain the retry ladder, when the tool ${w.what}.`;
      const { session, mine, sent } = await first(astra, typed);
      expect(sent.status, w.what).toBe(200);
      expect(sent.error, w.what).toBeNull();
      expect(sent.body, w.what).toContain(up.reply());
      expect(notes.of(typed).length, w.what).toBe(1);
      const got = up.of(session);
      expect(got.length, w.what).toBe(1);
      expect(blocks(got[0]!.body.messages[0]), w.what).toEqual(mine);
      if (w.waitMs) {
        const waited = got[0]!.at - sent.sentAt;
        expect(waited, w.what).toBeGreaterThanOrEqual(LIMIT_S * 1000 - 100);
        expect(waited, w.what).toBeLessThan(LIMIT_S * 1000 + 3000);
      }
    }
  }, 120_000);

  test("open: a tool or a server the hub cannot find for the session's folder leaves the turn as it is today", async () => {
    for (const [l, what] of [
      [lyra, "a tool the server does not have"],
      [vega, "a server nobody declares"],
    ] as const) {
      const { session, mine, sent } = await first(l, `Explain the retry ladder, with ${what}.`);
      expect(sent.status, what).toBe(200);
      expect(sent.error, what).toBeNull();
      expect(sent.body, what).toContain(up.reply());
      expect(blocks(up.of(session)[0]!.body.messages[0]), what).toEqual(mine);
    }
  });

  test("closed: each failure refuses the turn naming the capability, and nothing goes to the provider", async () => {
    const ways: { what: string; l: () => Launched; name: string; end?: "no-text" | { error: string } | { text: string }; waitMs?: number }[] = [
      { what: "answers an error result", l: () => cirrus, name: "gate", end: { error: "The notes index is locked." } },
      { what: "answers with no text", l: () => cirrus, name: "gate", end: "no-text" },
      { what: "passes its time limit", l: () => cirrus, name: "gate", end: { text: "Too late to be placed." }, waitMs: 9000 },
      { what: "names a server nobody declares", l: () => orion, name: "astray" },
      { what: "names a tool the server does not have", l: () => mensa, name: "untooled" },
    ];
    for (const w of ways) {
      notes.script = { end: w.end ?? { text: "" }, waitMs: w.waitMs ?? 0 };
      const { session, sent } = await first(w.l(), `Explain the retry ladder, when the worker ${w.what}.`);
      expect(sent.error, w.what).not.toBeNull();
      expect(sent.error!.message, w.what).toContain(w.name);
      expect(sent.body, w.what).not.toContain(up.reply());
      expect(up.of(session).length, w.what).toBe(0);
    }
  }, 120_000);

  test("a server declared only in a folder's own .mcp.json is found for a session in that folder, and only there", async () => {
    const typed = "Add a --since flag to the logs command. [mcp-folder]";
    repo.answering(typed);
    const there = await first(draco, typed, project);
    expect(there.sent.status).toBe(200);
    expect(there.sent.error).toBeNull();
    expect(repo.of(typed).length).toBe(1);
    expect(blocks(up.of(there.session)[0]!.body.messages[0]).at(-1)!.text ?? "").toContain(repo.text(typed));

    // a session in the home, above the project, has no such server: the capability fails open
    const elsewhere = "Add a --since flag to the logs command. [mcp-elsewhere]";
    repo.answering(elsewhere);
    const away = await first(draco, elsewhere, s.home);
    expect(away.sent.status).toBe(200);
    expect(away.sent.error).toBeNull();
    expect(repo.of(elsewhere).length).toBe(0);
    expect(blocks(up.of(away.session)[0]!.body.messages[0])).toEqual(away.mine);
  });
});

describe("mcp-worker config", () => {
  test("an MCP worker with no server or no tool is a config finding naming the key, and a whole one is not", async () => {
    const table = (name: string, worker: string) => `
[capabilities.${name}]
heads = ["astra"]
call = "rule"
place = "first-user-message"
may = "add"
message = "Checking the request"
color = "cyan"
timeout_s = 30
on_failure = "open"
send = ["first_user_message"]
worker = ${worker}
`;
    const nobody = new Provider("nobody").start();
    const wrong = await Splice.create(
      head({ key: "astra", command: "claude-astra", port: await freePort(), url: nobody.url }) +
        table("well-made", '{ kind = "mcp", server = "notes", tool = "brief" }') +
        table("half-a", '{ kind = "mcp", tool = "brief" }') +
        table("half-b", '{ kind = "mcp", server = "notes" }'),
      KEYS,
    );
    try {
      await expect(wrong.start()).rejects.toThrow();
      const lines = readFileSync(wrong.log, "utf8").split("\n");
      expect(lines.some((line) => line.includes("half-a") && line.includes("server")), "a finding that names half-a and server").toBe(true);
      expect(lines.some((line) => line.includes("half-b") && line.includes("tool")), "a finding that names half-b and tool").toBe(true);
      expect(lines.some((line) => line.includes("well-made"))).toBe(false);
    } finally {
      await wrong.dispose();
      nobody.stop();
    }
  }, 120_000);
});
