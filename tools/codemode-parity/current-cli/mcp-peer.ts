import { createInterface } from "node:readline";
import { appendFile } from "node:fs/promises";

const log = process.argv[2];
if (!log) throw Error("The fixed MCP peer requires an owned protocol log");
const names = ["echo", "ordinary_failure"];
const inputSchema = { type: "object", properties: { value: { type: "string" } }, required: ["value"], additionalProperties: false };
for await (const line of createInterface({ input: process.stdin, crlfDelay: Infinity })) {
  if (!line.trim()) continue;
  const request = JSON.parse(line);
  await appendFile(log, JSON.stringify({ direction: "received", request }) + "\n", { mode: 0o600 });
  if (request.id === undefined) continue;
  let result: unknown;
  if (request.method === "initialize") result = { protocolVersion: request.params?.protocolVersion ?? "2025-06-18", capabilities: { tools: { listChanged: true } }, serverInfo: { name: "Fixed local CodeMode reference", version: "1" } };
  else if (request.method === "ping") result = {};
  else if (request.method === "tools/list") result = { tools: names.map(name => ({ name, description: `Fixed local ${name} CodeMode fixture; signature marker quasar-zephyr.`, inputSchema, annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false } })) };
  else if (request.method === "tools/call" && names.includes(request.params?.name)) result = { content: [{ type: "text", text: `mcp-${request.params.name}-${request.params.arguments.value}` }], isError: request.params.name === "ordinary_failure", _meta: { privateFixtureMetadata: "excluded-from-code-mode-result" } };
  else throw Error(`Unexpected fixed-peer method ${request.method}`);
  const response = { jsonrpc: "2.0", id: request.id, result };
  await appendFile(log, JSON.stringify({ direction: "sent", response }) + "\n");
  process.stdout.write(JSON.stringify(response) + "\n");
}
