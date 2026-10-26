#!/usr/bin/env bun
import { createInterface } from "node:readline";

// A stdio MCP server that keeps MCP's cancellation rule, as the TypeScript SDK does: a request it was told to cancel
// gets no response (modelcontextprotocol.io/specification/2025-06-18/basic/utilities/cancellation). Its `wait` tool
// never answers and says `holding <n>` on stderr once the call reached it, so a test cancels only a call the server
// holds. `count` answers at once with how many `count` calls this process has answered.
let count = 0;

interface Message {
  id?: unknown;
  method?: unknown;
  params?: { protocolVersion?: unknown; name?: unknown; arguments?: { n?: unknown } };
}

function send(message: object): void {
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", ...message })}\n`);
}

createInterface({ input: process.stdin, crlfDelay: Infinity }).on("line", (line) => {
  let request: Message;
  try {
    request = JSON.parse(line) as Message;
  } catch {
    return;
  }
  // A notification, notifications/cancelled included, gets nothing back.
  if (request.id === undefined) return;
  const { id, params } = request;
  switch (request.method) {
    case "initialize":
      send({ id, result: {
        protocolVersion: typeof params?.protocolVersion === "string" ? params.protocolVersion : "2025-06-18",
        capabilities: { tools: {} },
        serverInfo: { name: "splice-hub-holding", version: "1" },
      } });
      break;
    case "tools/list":
      send({ id, result: { tools: ["wait", "count"].map((name) => ({ name, inputSchema: { type: "object" } })) } });
      break;
    case "tools/call":
      if (params?.name === "wait") {
        process.stderr.write(`holding ${String(params.arguments?.n)}\n`);
      } else {
        send({ id, result: { content: [{ type: "text", text: String(++count) }] } });
      }
      break;
    default:
      send({ id, error: { code: -32601, message: "The stand-in does not implement that method" } });
  }
});
