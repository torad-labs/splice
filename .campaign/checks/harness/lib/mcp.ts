// What the MCP worker checks stand on (docs/specs/harness.md, decision 13): a person's stdio MCP server, declared the
// way Claude Code reads it (user scope in the home's `.claude.json`, or a folder's own `.mcp.json`), scripted through a
// file and recording every call it gets; a capability's table whose worker is that server's tool; and a session whose
// folder Claude Code has registered, the way a running session registers it.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { Launched } from "../../speed/lib/session.ts";
import type { McpCall, McpScript } from "./mcp-server.ts";

export type { McpCall, McpScript };

const SERVER = join(import.meta.dir, "mcp-server.ts");

/** A server that answers its tools at once with its own text. */
export const READY = (tools: string[]): McpScript => ({ tools, waitMs: 0, progress: [], gapMs: 0, end: { text: "" } });

/** One stdio MCP server a person declares: `bun mcp-server.ts`, told what to do through `script`, recording to
 *  `record`. An `end` with empty text answers with `text(call)`. */
export class McpServer {
  readonly name: string;
  readonly tools: string[];
  readonly scriptFile: string;
  readonly recordFile: string;
  private current: McpScript;

  constructor(name: string, dir: string, tools: string[]) {
    this.name = name;
    this.tools = tools;
    mkdirSync(dir, { recursive: true });
    this.scriptFile = join(dir, `${name}.script.json`);
    this.recordFile = join(dir, `${name}.calls.jsonl`);
    this.current = READY(tools);
    this.write();
    writeFileSync(this.recordFile, "");
  }

  /** The entry Claude Code's config declares it with. */
  entry(): Record<string, unknown> {
    return { type: "stdio", command: process.execPath, args: [SERVER], env: { FAKE_MCP_SCRIPT: this.scriptFile, FAKE_MCP_RECORD: this.recordFile } };
  }

  /** What the server does with its next calls; `end: { text: "" }` answers with the server's own text. */
  set script(s: Partial<McpScript>) {
    this.current = { ...READY(this.tools), ...s };
    this.write();
  }

  /** The text a call answered with its own text carries. */
  text(marker: string): string {
    return `What ${this.name} found for "${marker}".\nsrc/main.ts:10-42 holds the entry point.`;
  }

  /** Answer with `text(marker)` for a call whose arguments carry `marker`. Kept simple: the script holds one text, so
   *  the check names the marker it is about to send. */
  answering(marker: string, s: Partial<McpScript> = {}): void {
    this.script = { ...s, end: { text: this.text(marker) } };
  }

  /** Every call the server got, oldest first. */
  get calls(): McpCall[] {
    if (!existsSync(this.recordFile)) return [];
    return readFileSync(this.recordFile, "utf8")
      .split("\n")
      .filter((line) => line.trim())
      .map((line) => JSON.parse(line) as McpCall);
  }

  /** The calls whose arguments mention `marker` anywhere. */
  of(marker: string): McpCall[] {
    return this.calls.filter((c) => JSON.stringify(c.arguments).includes(marker));
  }

  private write(): void {
    writeFileSync(this.scriptFile, JSON.stringify(this.current));
  }
}

/** The home's `.claude.json` with `servers` at user scope, as a person declares them for Claude Code. */
export function claudeJson(home: string, servers: McpServer[]): void {
  writeFileSync(join(home, ".claude.json"), JSON.stringify({ mcpServers: Object.fromEntries(servers.map((s) => [s.name, s.entry()])) }, null, 2));
}

/** A project folder with a git root and its own `.mcp.json` declaring `servers`. */
export function projectFolder(folder: string, servers: McpServer[]): void {
  mkdirSync(join(folder, ".git"), { recursive: true });
  mkdirSync(join(folder, ".claude"), { recursive: true });
  writeFileSync(join(folder, ".mcp.json"), JSON.stringify({ mcpServers: Object.fromEntries(servers.map((s) => [s.name, s.entry()])) }, null, 2));
}

/** The person trusted `folder` and approved its `.mcp.json` servers `names` in a session of `l`: Claude Code
 *  records both in its config home's `.claude.json`, under the folder's `projects` record, and the approval in the
 *  folder's local settings. */
export function approve(l: Launched, folder: string, names: string[]): void {
  const file = join(l.dir, ".claude.json");
  const state = existsSync(file) ? (JSON.parse(readFileSync(file, "utf8")) as Record<string, unknown>) : {};
  const projects = (state.projects as Record<string, unknown> | undefined) ?? {};
  projects[folder] = { ...((projects[folder] as Record<string, unknown> | undefined) ?? {}), hasTrustDialogAccepted: true, enabledMcpjsonServers: names };
  writeFileSync(file, JSON.stringify({ ...state, projects }, null, 2));
  mkdirSync(join(folder, ".claude"), { recursive: true });
  writeFileSync(join(folder, ".claude", "settings.local.json"), JSON.stringify({ enabledMcpjsonServers: names }, null, 2));
}

let pid = 4_000_000;

/** Session `session` of `l` runs in `folder`: Claude Code registers a running session under its config home's
 *  `sessions` folder, by the pid it runs as, with its id and its working folder. */
export function seat(l: Launched, session: string, folder: string): void {
  const dir = join(l.dir, "sessions");
  mkdirSync(dir, { recursive: true });
  pid += 1;
  writeFileSync(join(dir, `${pid}.json`), JSON.stringify({ pid, sessionId: session, cwd: folder, startedAt: Date.now(), kind: "interactive", entrypoint: "cli" }));
}

export interface McpDeclared {
  heads: string[];
  server: string;
  tool: string;
  when?: string;
  place?: string;
  may?: string;
  message?: string;
  color?: string;
  timeout?: number;
  onFailure?: string;
  send?: string[];
}

/** A capability's table in splice.toml whose worker is the tool `tool` of the MCP server `server`. */
export function mcpCapability(name: string, o: McpDeclared): string {
  return `
[capabilities.${name}]
heads = [${o.heads.map((h) => `"${h}"`).join(", ")}]
call = "rule"
${o.when ? `when = ${o.when}` : ""}
place = "${o.place ?? "first-user-message"}"
may = "${o.may ?? "add"}"
message = "${o.message ?? "Reading the request"}"
color = "${o.color ?? "cyan"}"
timeout_s = ${o.timeout ?? 30}
on_failure = "${o.onFailure ?? "open"}"
send = [${(o.send ?? ["first_user_message"]).map((f) => `"${f}"`).join(", ")}]
worker = { kind = "mcp", server = "${o.server}", tool = "${o.tool}" }
`;
}
