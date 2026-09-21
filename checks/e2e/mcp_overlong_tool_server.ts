#!/usr/bin/env bun
/** checks/e2e/mcp_overlong_tool_server.ts — a REAL minimal stdio MCP server for the heads e2e.
 *
 *  WHY THIS FILE EXISTS. The tier-2 drive is supposed to carry the operator's actual tool surface,
 *  because that surface is what 400d claude-muse ("name must be at most 64 characters, got 68").
 *  The first version of the arm planted a do-nothing interpreter command in .mcp.json: it exits
 *  before the first byte of the stdio handshake, so Claude Code registered ZERO tools and the
 *  over-long name never reached the wire. A server that does not answer `initialize` is not a tool
 *  surface — it is a config file nobody read.
 *
 *  WHAT IT IS. Newline-delimited JSON-RPC 2.0 over stdin/stdout (the MCP stdio transport — no
 *  Content-Length framing), standard library only, one tool. Claude Code composes the wire name as
 *  mcp__<server-key-from-.mcp.json>__<advertised tool name>, so the over-length lives in the
 *  COMPOSITION: this server advertises the short half (TOOL_NAME) and tools/e2e/src/commands/heads.ts owns the long
 *  half (OVERLONG_MCP_SERVER). tools/e2e/test/heads.test.ts pins the two halves to each other and to the
 *  68 characters the operator's 400 reported — rename either and the gate goes red.
 *
 *  THE RECEIPT. Every method served is appended as one JSONL row to $SPLICE_E2E_MCP_LOG (absolute
 *  path, planted by heads.ts into the scratch dir). That file is the only observable proof that
 *  Claude Code really spawned this server and really pulled its tools; heads.ts's
 *  mcp_surface_ok() reads it, and the selftest drives this server directly and reads it too.
 *  Unset SPLICE_E2E_MCP_LOG simply disables the receipt — the server still serves.
 *
 *  Protocol version is ECHOED back to the client. This server has no version-specific behaviour, and
 *  echoing is the one answer that cannot fail negotiation against whatever Claude Code ships next.
 *
 *  V4-145: converted to TypeScript (bun). The docstring sentence that quoted the old fixture's
 *  interpreter command is reworded to describe the same event without naming the interpreter, on the
 *  orchestrator's ruling: this file invokes nothing, so the mention was an instruction rather than a
 *  dependency, and the census's own remedy for prose is to update the prose. Input goes through the
 *  shared python-json parser and every reply is built with its constructors, so the emitted bytes are
 *  json.dumps bytes — including the id, which keeps its exact source token rather than being rounded
 *  through a JS number.
 */
import { appendFileSync } from "node:fs";
import { dumps, loads, obj, objGet, isPyObj, fromJS, quote, type PyValue, type PyObj } from "../../tools/e2e/src/compat/python-json.ts";

const TOOL_NAME = "read_process_output";
const SERVER_NAME = "splice-e2e-overlong-tool";
const SERVER_VERSION = "1.0.0";
const FALLBACK_PROTOCOL = "2025-06-18";
const METHOD_NOT_FOUND = -32601;

const TOOL = fromJS({
  name: TOOL_NAME,
  description: "e2e stand-in for the operator's over-long MCP tool; returns a fixed string.",
  inputSchema: { type: "object", properties: {}, additionalProperties: false },
});

/** Python repr() of a string — the failure messages quote the offending name with %r. */
function pyRepr(v: PyValue): string {
  if (typeof v === "string") {
    return quote(v).replaceAll('"', "'");
  }
  if (v === null) return "None";
  return dumps(v);
}

/** One JSONL row per served method. Best-effort by design: the harness asserts on this file,
 *  but a disk problem here must not take the MCP server (and with it the billed turn) down. */
function receipt(method: string, extra: [string, PyValue][] = []): void {
  const path = process.env["SPLICE_E2E_MCP_LOG"];
  if (!path) {
    return;
  }
  const row = obj([["ts", fromJS(Date.now())], ["method", method], ...extra]);
  try {
    appendFileSync(path, dumps(row) + "\n", "utf8");
  } catch {
    // deliberately swallowed: OSError in Python, any fs failure here
  }
}

function reply(msgId: PyValue, result: PyValue = null, error: PyValue = null): void {
  const pairs: [string, PyValue][] = [["jsonrpc", "2.0"], ["id", msgId]];
  if (error === null) {
    // Python sets body["result"] unconditionally when there is no error, so a null result is
    // emitted as "result": null rather than omitted.
    pairs.push(["result", result]);
  } else {
    pairs.push(["error", error]);
  }
  process.stdout.write(dumps(obj(pairs)) + "\n");
}

function handle(msg: PyObj): void {
  const method = objGet(msg, "method");
  const msgId = objGet(msg, "id");
  const rawParams = objGet(msg, "params");
  const params = isPyObj(rawParams) ? rawParams : obj([]);
  const param = (k: string): PyValue => objGet(params, k);

  if (method === "initialize") {
    const asked = param("protocolVersion");
    const version = typeof asked === "string" && asked !== "" ? asked : FALLBACK_PROTOCOL;
    receipt("initialize", [["protocol", version]]);
    reply(
      msgId,
      obj([
        ["protocolVersion", version],
        ["capabilities", obj([["tools", obj([["listChanged", false]])]])],
        ["serverInfo", obj([["name", SERVER_NAME], ["version", SERVER_VERSION]])],
      ]),
    );
    return;
  }

  if (method === "tools/list") {
    // `tools` is the load-bearing field of the whole receipt: it records the name this server
    // actually advertised, not the name the harness hoped it would.
    receipt("tools/list", [["tools", [TOOL_NAME]]]);
    reply(msgId, obj([["tools", [TOOL]]]));
    return;
  }

  if (method === "tools/call") {
    const called = param("name");
    receipt("tools/call", [["tool", called]]);
    if (called !== TOOL_NAME) {
      reply(
        msgId,
        null,
        obj([["code", fromJS(METHOD_NOT_FOUND)], ["message", `no such tool: ${pyRepr(called)}`]]),
      );
      return;
    }
    reply(
      msgId,
      obj([
        ["content", [obj([["type", "text"], ["text", "splice e2e tool ok"]])]],
        ["isError", false],
      ]),
    );
    return;
  }

  if (method === "ping") {
    receipt("ping");
    reply(msgId, obj([]));
    return;
  }

  // Notifications (no id) are acknowledged only in the receipt — answering one is a protocol
  // violation. `notifications/initialized` is the third leg of the handshake and arrives here.
  if (msgId === null) {
    receipt(typeof method === "string" ? method : "<no method>");
    return;
  }

  receipt(typeof method === "string" ? method : "<no method>", [["unsupported", true]]);
  reply(
    msgId,
    null,
    obj([["code", fromJS(METHOD_NOT_FOUND)], ["message", `unsupported method: ${pyRepr(method)}`]]),
  );
}

async function main(): Promise<number> {
  const reader = Bun.stdin.stream().getReader();
  const decoder = new TextDecoder();
  let pending = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    pending += decoder.decode(value, { stream: true });
    for (;;) {
      const nl = pending.indexOf("\n");
      if (nl < 0) break;
      const line = pending.slice(0, nl).trim();
      pending = pending.slice(nl + 1);
      if (line === "") continue;
      let msg: PyValue;
      try {
        msg = loads(line);
      } catch {
        continue;
      }
      if (isPyObj(msg)) {
        handle(msg);
      }
    }
  }
  return 0;
}

if (import.meta.main) {
  process.exit(await main());
}
