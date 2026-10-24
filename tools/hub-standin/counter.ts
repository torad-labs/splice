#!/usr/bin/env bun
import { randomUUID } from "node:crypto";
import { createInterface } from "node:readline";

// A fresh, operator-free stdio MCP server. Its counter belongs to this process,
// so continuity cannot pass merely because a replacement server answers calls.
let count = 0;
const instance = randomUUID();
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });

function reply(id: unknown, result: unknown): void {
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id, result })}\n`);
}

function failure(id: unknown, code: number, message: string): void {
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } })}\n`);
}

input.on("line", (line) => {
  let request: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(line);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("invalid request");
    request = parsed as Record<string, unknown>;
  } catch {
    failure(null, -32700, "The stand-in could not parse the request");
    return;
  }
  if (!("id" in request)) return;
  const params = request.params as Record<string, unknown> | undefined;
  switch (request.method) {
    case "initialize":
      reply(request.id, {
        protocolVersion: typeof params?.protocolVersion === "string" ? params.protocolVersion : "2025-03-26",
        capabilities: { tools: {}, logging: {} },
        serverInfo: { name: "splice-hub-standin", version: "1" },
      });
      break;
    case "ping":
      reply(request.id, {});
      break;
    case "tools/list":
      reply(request.id, {
        tools: [{ name: "count", description: "Increment this process's counter", inputSchema: { type: "object", properties: {} } }],
      });
      break;
    case "tools/call":
      if (params?.name !== "count") {
        failure(request.id, -32602, "The stand-in exposes only count");
        return;
      }
      reply(request.id, { content: [{ type: "text", text: JSON.stringify({ count: ++count, pid: process.pid, instance }) }] });
      process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", method: "notifications/message", params: {
        level: "info", data: "The stand-in counter advanced",
      } })}\n`);
      break;
    default:
      failure(request.id, -32601, "The stand-in does not implement that method");
  }
});
