// mcp-says: while an MCP worker holds a turn, each progress notification its tool sends becomes what the held turn
// says on the session's status line, and the message's age starts again. Before the first one the line says the
// capability's own message; when the turn goes on, the line is what it was. (docs/specs/harness.md, decisions 7 and
// 13; it stands on the held-turn status line of decision 7 as well as on the MCP worker.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { join } from "node:path";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { KEYS, Provider, head, opening, send } from "./lib/capability.ts";
import { age, line } from "./lib/held.ts";
import { McpServer, READY, claudeJson, mcpCapability, seat } from "./lib/mcp.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const READING = "Reading the request";

let up: Provider;
let notes: McpServer;
let s: Splice;
let astra: Launched;

beforeAll(async () => {
  up = new Provider("the provider").start();
  s = await Splice.create(
    head({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url }) +
      mcpCapability("brief", { heads: ["astra"], server: "notes", tool: "brief", message: READING, color: "cyan", timeout: 120 }),
    KEYS,
  );
  notes = new McpServer("notes", join(s.home, "mcp-servers"), ["brief"]);
  claudeJson(s.home, [notes]);
  try {
    await s.start();
  } catch (e) {
    await s.dispose();
    throw e;
  }
  astra = await launch(s, "claude-astra");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
});

afterEach(() => {
  if (notes) notes.script = READY(notes.tools);
});

describe("mcp-says", () => {
  test("each progress notification the tool sends becomes what the held turn says, and its age starts again", async () => {
    const typed = "Why does the daemon's log rotate at midnight? [mcp-says]";
    notes.answering(typed, { waitMs: 6000, progress: ["Opening src/main.ts", "Reading the tests"], gapMs: 6000 });
    const session = crypto.randomUUID();
    seat(astra, session, s.home);
    expect(line(astra, session)).toContain("astra-large");

    const sending = send(astra, session, [{ role: "user", content: opening(typed) }]);
    await until("the tool is called", () => notes.of(typed).length === 1);
    const from = Date.now();
    const at = async (ms: number) => {
      await Bun.sleep(Math.max(0, from + ms - Date.now()));
      return line(astra, session);
    };

    // before the first notification: the capability's own message
    expect(age(await at(3000), READING)).not.toBeNull();

    // the first notification's message, said a moment ago
    const opened = await at(9000);
    expect(opened).not.toContain(READING);
    expect(age(opened, "Opening src/main.ts")).not.toBeNull();
    expect(age(opened, "Opening src/main.ts")!).toBeLessThanOrEqual(5);
    expect(opened.indexOf("Opening src/main.ts")).toBeLessThan(opened.indexOf("astra-large"));

    // the second replaces it, and its age starts again
    const read = await at(15_000);
    expect(read).not.toContain("Opening src/main.ts");
    expect(age(read, "Reading the tests")).not.toBeNull();
    expect(age(read, "Reading the tests")!).toBeLessThanOrEqual(5);

    const sent = await sending;
    expect(sent.status).toBe(200);
    expect(sent.body).toContain(up.reply());
    const after = line(astra, session);
    expect(after).not.toContain("Reading the tests");
    expect(after).toContain("astra-large");
  }, 300_000);
});
