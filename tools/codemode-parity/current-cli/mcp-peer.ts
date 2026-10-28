import { createInterface } from "node:readline";
import { appendFile } from "node:fs/promises";
import { mcpFixtureCall, mcpFixtureTools } from "./mcp-contract";
import { mcpRpcError } from "./mcp-rpc-cases";

const log = process.argv[2];
if (!log) throw Error("The fixed MCP peer requires an owned protocol log");
for await (const line of createInterface({ input: process.stdin, crlfDelay: Infinity })) {
  if (!line.trim()) continue;
  const request = JSON.parse(line);
  await appendFile(log, JSON.stringify({ direction: "received", request }) + "\n", { mode: 0o600 });
  if (request.id === undefined) continue;
  let result: unknown;
  if (request.method === "initialize") result = { protocolVersion: request.params?.protocolVersion ?? "2025-06-18", capabilities: { tools: { listChanged: true } }, serverInfo: { name: "Fixed local CodeMode reference", version: "1" } };
  else if (request.method === "ping") result = {};
  else if (request.method === "tools/list") result = { tools: mcpFixtureTools() };
  else if (request.method === "tools/call") result = mcpFixtureCall(request.params?.name, request.params?.arguments).result;
  else throw Error(`Unexpected fixed-peer method ${request.method}`);
  // A separate source probe measures a genuine JSON-RPC error, not isError content.
  const rpcFailure = request.method === "tools/call" ? mcpRpcError(request.params?.name, request.params?.arguments) : undefined;
  const response = rpcFailure ? { jsonrpc: "2.0", id: request.id, error: rpcFailure }
    : { jsonrpc: "2.0", id: request.id, result };
  await appendFile(log, JSON.stringify({ direction: "sent", response }) + "\n");
  process.stdout.write(JSON.stringify(response) + "\n");
}
