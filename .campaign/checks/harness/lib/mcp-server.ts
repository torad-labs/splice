// A stdio MCP server for the harness checks: what a person declares in Claude Code's config and splice's MCP hub runs.
// It speaks MCP's JSON-RPC, one message per line, and is told what to do through a file the check writes:
// `FAKE_MCP_SCRIPT` names it, and it is read again on every call, so a check changes the next answer by rewriting it.
// Every tools/call is appended to the file `FAKE_MCP_RECORD` names, one JSON line each: the tool, the arguments it was
// called with, when, and the folder the server runs in. Run with bun: `bun mcp-server.ts`.

import { appendFileSync, readFileSync } from "node:fs";

/** What the server does with its next calls. */
export interface McpScript {
  /** The tools it lists. */
  tools: string[];
  /** Milliseconds before it answers at all. */
  waitMs: number;
  /** The progress notifications it sends first, `gapMs` apart, each carrying its message. */
  progress: string[];
  gapMs: number;
  /** What the call ends on: a text result, an error result, a result whose content holds no text, or no answer. */
  end: { text: string } | { error: string } | "no-text" | "never";
}

/** One call as the server recorded it. */
export interface McpCall {
  tool: string;
  arguments: Record<string, unknown>;
  /** Whether the call asked for progress (`_meta.progressToken`). */
  progressToken: boolean;
  at: number;
  cwd: string;
}

const SCRIPT = process.env.FAKE_MCP_SCRIPT ?? "";
const RECORD = process.env.FAKE_MCP_RECORD ?? "";

function script(): McpScript {
  return JSON.parse(readFileSync(SCRIPT, "utf8")) as McpScript;
}

function send(message: Record<string, unknown>): void {
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", ...message })}\n`);
}

async function call(id: unknown, params: { name?: string; arguments?: Record<string, unknown>; _meta?: { progressToken?: unknown } }): Promise<void> {
  const s = script();
  const token = params._meta?.progressToken;
  const record: McpCall = { tool: String(params.name ?? ""), arguments: params.arguments ?? {}, progressToken: token !== undefined, at: Date.now(), cwd: process.cwd() };
  if (RECORD) appendFileSync(RECORD, `${JSON.stringify(record)}\n`);
  if (!s.tools.includes(record.tool)) {
    send({ id, error: { code: -32602, message: `Unknown tool: ${record.tool}` } });
    return;
  }
  if (s.waitMs) await Bun.sleep(s.waitMs);
  let n = 0;
  for (const message of s.progress) {
    n += 1;
    // MCP sends progress only against a token the caller gave
    if (token !== undefined) send({ method: "notifications/progress", params: { progressToken: token, progress: n, total: s.progress.length + 1, message } });
    if (s.gapMs) await Bun.sleep(s.gapMs);
  }
  const end = s.end;
  if (end === "never") return;
  if (end === "no-text") {
    send({ id, result: { content: [{ type: "image", data: "iVBORw0KGgo=", mimeType: "image/png" }] } });
  } else if ("error" in end) {
    send({ id, result: { content: [{ type: "text", text: end.error }], isError: true } });
  } else {
    send({ id, result: { content: [{ type: "text", text: end.text }] } });
  }
}

function handle(message: { id?: unknown; method?: string; params?: Record<string, unknown> }): void {
  switch (message.method) {
    case "initialize":
      send({ id: message.id, result: { protocolVersion: String(message.params?.protocolVersion ?? "2025-06-18"), capabilities: { tools: {} }, serverInfo: { name: "harness-check", version: "1" } } });
      return;
    case "ping":
      send({ id: message.id, result: {} });
      return;
    case "tools/list":
      send({
        id: message.id,
        result: { tools: script().tools.map((name) => ({ name, description: `The check's ${name} tool.`, inputSchema: { type: "object", additionalProperties: true } })) },
      });
      return;
    case "tools/call":
      void call(message.id, (message.params ?? {}) as Parameters<typeof call>[1]);
      return;
    default:
      // a notification needs no answer; any other request is one this server does not have
      if (message.id !== undefined && message.method) send({ id: message.id, error: { code: -32601, message: `Method not found: ${message.method}` } });
  }
}

if (import.meta.main) {
  let buffered = "";
  process.stdin.setEncoding("utf8");
  process.stdin.on("data", (chunk: string) => {
    buffered += chunk;
    for (let at = buffered.indexOf("\n"); at >= 0; at = buffered.indexOf("\n")) {
      const line = buffered.slice(0, at).trim();
      buffered = buffered.slice(at + 1);
      if (!line) continue;
      try {
        handle(JSON.parse(line));
      } catch {}
    }
  });
  process.stdin.on("end", () => process.exit(0));
}
