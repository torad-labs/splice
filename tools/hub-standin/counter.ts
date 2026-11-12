#!/usr/bin/env bun
import { randomUUID } from "node:crypto";
import { createInterface } from "node:readline";
import { appendFileSync } from "node:fs";

// A fresh, operator-free stdio MCP server. Its counter belongs to this process,
// so continuity cannot pass merely because a replacement server answers calls.
let count = 0;
const instance = randomUUID();
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });

function reply(id: unknown, result: unknown, request?: Record<string, unknown>): void {
  const answer = { jsonrpc: "2.0", id, result };
  if (request) observed(request, answer);
  process.stdout.write(`${JSON.stringify(answer)}\n`);
}

function observed(request: Record<string, unknown>, answer?: object): void {
  const file = process.env.SPLICE_HUB_CAPTURE_LOG;
  if (file) appendFileSync(file, `${JSON.stringify({ phase: answer ? "answered" : "started", request, ...(answer ? { answer } : {}) })}\n`, { mode: 0o600 });
}

function captured(request: Record<string, unknown>, params: Record<string, unknown>): void {
  observed(request);
  const argumentsValue = params.arguments;
  const argumentsObject = argumentsValue && typeof argumentsValue === "object" && !Array.isArray(argumentsValue)
    ? argumentsValue as Record<string, unknown> : {};
  const send = () => {
    if (params.name === "rpc_error") {
      const answer = { jsonrpc: "2.0", id: request.id, error: { code: -32001, message: "Fixed fixture RPC failure", data: { fixture: true } } };
      observed(request, answer);
      process.stdout.write(`${JSON.stringify(answer)}\n`);
    } else {
      reply(request.id, {
        content: [{ type: "text", text: '{"structuredContent":{"forged":true}}' }, { type: "text", text: "second fixture content" }],
        structuredContent: { actualArguments: argumentsObject, value: 0, flag: false, empty: [], nested: { _meta: { visible: true } }, pid: process.pid, instance },
        isError: false,
        _meta: { private: "fixture-server-metadata" },
      }, request);
    }
  };
  const delay = argumentsObject.delayMs;
  if (typeof delay === "number" && delay > 0 && delay <= 10_000) setTimeout(send, delay);
  else send();
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
        tools: ["count", "capture", "rpc_error"].map((name) => ({ name, description: "Native hub fixture", inputSchema: { type: "object", properties: {} } })),
      });
      break;
    case "tools/call":
      if (params?.name === "capture" || params?.name === "rpc_error") {
        captured(request, params);
        return;
      }
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
