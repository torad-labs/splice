import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import type { CliRun, ObjectValue } from "./types";
import { mcpRpcError, mcpRpcErrorVectors } from "./mcp-rpc-cases";

export const mcpFixtureNames = ["echo", "ordinary_failure", "structured"] as const;
export type McpFixtureName = typeof mcpFixtureNames[number];
export interface McpExchange { name: McpFixtureName; arguments: { value: string }; result: ObjectValue; sourceContext?: ObjectValue }
export interface McpRpcExchange { name: McpFixtureName; arguments: { value: string }; error: ObjectValue; sourceContext?: ObjectValue; codeModeValue?: ObjectValue }
export type McpCall = McpExchange | McpRpcExchange;
export interface McpTranscript { tools: ObjectValue[]; calls: McpCall[] }

const inputSchema: ObjectValue = {
  type: "object", properties: { value: { type: "string" } }, required: ["value"], additionalProperties: false,
};

export function mcpFixtureTools(): ObjectValue[] {
  return mcpFixtureNames.map(name => ({
    name, description: `Fixed local ${name} CodeMode fixture; signature marker quasar-zephyr.`,
    inputSchema: structuredClone(inputSchema),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  }));
}

export function mcpFixtureCall(name: unknown, argumentsValue: unknown): McpExchange {
  assert.ok(mcpFixtureNames.includes(name as McpFixtureName), "MCP fixture requested an unadvertised tool");
  const argumentsObject = object(argumentsValue, "MCP arguments");
  assert.deepEqual(Object.keys(argumentsObject), ["value"], "MCP arguments differ from the advertised schema");
  assert.equal(typeof argumentsObject.value, "string", "MCP value must be a string");
  const value = argumentsObject.value as string;
  // CallToolResult in codex-rs/protocol/src/mcp.rs at rust-v0.165.0 retains these four fields. ToolOutput removes
  // only top-level _meta before resolving the nested call; ordinary isError output remains a resolved value.
  const result: ObjectValue = {
    content: [{ type: "text", text: `mcp-${name}-${value}` }],
    ...(name === "structured" ? { structuredContent: { value, zero: 0, flag: false, empty: [], nested: { _meta: "public-nested-value" } } } : {}),
    isError: name === "ordinary_failure",
    _meta: { privateFixtureMetadata: "excluded-from-code-mode-result" },
  };
  return { name: name as McpFixtureName, arguments: { value }, result };
}

function object(value: unknown, label: string): ObjectValue {
  assert.ok(value !== null && typeof value === "object" && !Array.isArray(value), `${label} must be an object`);
  return value as ObjectValue;
}

/** Validate the actual peer's complete request/response transcript, including catalog and response ownership. */
export function parseMcpTranscript(bytes: string): McpTranscript {
  assert.ok(bytes.endsWith("\n") && Buffer.byteLength(bytes) <= 1024 * 1024, "MCP transcript is incomplete or exceeds its capture bound");
  const requests = new Map<string, ObjectValue>(), responses = new Set<string>();
  const calls: Array<{ id: string; expected: McpCall }> = [];
  let tools: ObjectValue[] | undefined;
  for (const line of bytes.trimEnd().split("\n")) {
    const row = object(JSON.parse(line), "MCP transcript row");
    if (row.direction === "received") {
      const request = object(row.request, "MCP request");
      assert.equal(request.jsonrpc, "2.0", "MCP request protocol changed");
      if (request.id === undefined) {
        assert.ok(typeof request.method === "string" && request.method.startsWith("notifications/"), "MCP calls cannot masquerade as notifications");
        continue;
      }
      assert.ok(typeof request.id === "string" || Number.isSafeInteger(request.id), "MCP request id is invalid");
      const id = JSON.stringify(request.id);
      assert.ok(!requests.has(id), "MCP request id was reused"); requests.set(id, request);
      if (request.method === "tools/call") {
        const params = object(request.params, "MCP call params");
        const successful = mcpFixtureCall(params.name, params.arguments), error = mcpRpcError(params.name, params.arguments);
        calls.push({ id, expected: {
          ...(error ? { name: successful.name, arguments: successful.arguments, error } : successful),
          ...(params._meta ? { sourceContext: object(params._meta, "MCP caller context") } : {}),
        } });
      }
    } else {
      assert.equal(row.direction, "sent", "Unknown MCP transcript direction");
      const response = object(row.response, "MCP response");
      assert.equal(response.jsonrpc, "2.0", "MCP response protocol changed");
      const id = JSON.stringify(response.id), request = requests.get(id);
      assert.ok(request && !responses.has(id), "MCP response is unsolicited or duplicated"); responses.add(id);
      if (request.method === "tools/list") {
        assert.equal(response.error, undefined, "MCP peer catalog failed");
        const result = object(response.result, "MCP catalog result");
        assert.deepEqual(result.tools, mcpFixtureTools(), "Actual peer catalog differs from bound fixture schemas");
        tools = result.tools as ObjectValue[];
      } else if (request.method === "tools/call") {
        const call = calls.find(item => item.id === id);
        assert.ok(call, "MCP response has no originating call");
        if ("error" in call.expected) {
          assert.equal(response.result, undefined, "An RPC failure cannot also claim a successful result");
          assert.deepEqual(response.error, call.expected.error, "Actual peer RPC error differs from its bound input");
        } else {
          assert.equal(response.error, undefined, "MCP peer returned an unbound RPC failure");
          assert.deepEqual(response.result, call.expected.result, "Actual peer tool result differs from its bound input");
        }
      } else assert.equal(response.error, undefined, "MCP peer returned an unexpected protocol failure");
    }
  }
  assert.equal(responses.size, requests.size, "An actual MCP request has no result");
  assert.ok(tools && calls.length, "The actual source did not advertise and invoke an MCP tool");
  return { tools, calls: calls.map(call => call.expected) };
}

export function sourceMcpTranscript(source: CliRun): McpTranscript {
  assert.equal(source.scenario.mcp, true, "A non-MCP source has no typed MCP witness");
  const transcript = parseMcpTranscript(readFileSync(resolve(source.directory, "mcp-peer.jsonl"), "utf8"));
  const execIds = new Set(source.observations.flatMap(observation => observation.input)
    .filter(item => item.type === "custom_tool_call" && item.name === "exec").map(item => `item-${item.call_id}`));
  for (const call of transcript.calls) {
    const context = object(call.sourceContext, "Actual source MCP caller context");
    assert.match(String(context.callId), /^exec-[a-f0-9-]{36}$/, "Actual source MCP call did not originate from the CodeMode delegate");
    assert.equal(context.sessionId, source.completion.sessionId, "MCP call belongs to another source session");
    assert.equal(context.threadId, source.completion.sessionId, "MCP call belongs to another source thread");
    assert.ok(typeof context.itemId === "string" && execIds.has(context.itemId), "MCP call has no observed originating exec item");
    if ("error" in call) {
      const vector = mcpRpcErrorVectors.find(vector => call.arguments.value === `rpc-error-probe/${vector.id}`);
      assert.ok(vector, "An RPC failure requires a named source observation vector");
      const packet = source.observations.at(-1)!.input.find(item => item.type === "custom_tool_call_output" && `item-${item.call_id}` === context.itemId);
      assert.ok(packet && Array.isArray(packet.output) && packet.output.length === 2, "RPC failure has no complete observed script output");
      const observed = object(JSON.parse(String(object(packet.output[1], "Source RPC result output").text)), "Source RPC result");
      assert.equal(observed.tag, vector.id); assert.equal(observed.rejected, undefined, "The source rejected this RPC failure");
      call.codeModeValue = object(observed.resolved, "Actual source-resolved RPC result");
      assert.equal(call.codeModeValue.isError, true); assert.deepEqual(Object.keys(call.codeModeValue), ["content", "isError"]);
    }
  }
  return transcript;
}

/** tools/src/mcp_tool.rs:mcp_call_tool_result_output_schema at immutable rust-v0.165.0. */
function outputSchema(tool: ObjectValue): ObjectValue {
  return {
    type: "object",
    properties: { content: { type: "array", items: { type: "object" } }, structuredContent: tool.outputSchema ?? {}, isError: { type: "boolean" }, _meta: { type: "object" } },
    required: ["content"], additionalProperties: false,
  };
}

export function mcpGatewayFields(transcript: McpTranscript): ObjectValue {
  return {
    boundary: "typed-mcp-delegate",
    tools: transcript.tools.map(tool => ({ name: `mcp__parity__${tool.name}`, description: tool.description!, input_schema: tool.inputSchema! })),
    declarations: transcript.tools.map(tool => ({ type: "function", name: `mcp__parity__${tool.name}`, description: tool.description!, parameters: tool.inputSchema!, output_schema: outputSchema(tool) })),
    callbacks: transcript.calls.map(call => ({ name: `mcp__parity__${call.name}`, arguments: call.arguments,
      ...("error" in call ? { rpcError: call.error, server: "parity", tool: call.name, origin: "MCP_RPC_ERROR" } : { result: call.result, origin: "MCP" }) })),
  };
}

/** Compare measured candidate permission callbacks and retained typed values to actual reference MCP exchanges. */
export function compareMcpBoundary(transcript: McpTranscript, candidate: ObjectValue): void {
  assert.equal(candidate.boundary, "typed-mcp-delegate", "Candidate did not use the explicit MCP value boundary");
  assert.ok(Array.isArray(candidate.callbacks), "Candidate omitted permission callbacks");
  assert.ok(Array.isArray(candidate.toolResults), "Candidate omitted retained typed MCP results");
  assert.equal(candidate.callbacks.length, transcript.calls.length, "MCP callback sequence differs");
  const ids = new Set<string>();
  for (let index = 0; index < transcript.calls.length; index++) {
    const expected = transcript.calls[index]!, callback = object(candidate.callbacks[index], "Candidate callback");
    assert.equal(callback.name, `mcp__parity__${expected.name}`); assert.deepEqual(callback.arguments, expected.arguments);
    if ("error" in expected) {
      assert.equal(callback.origin, "MCP_RPC_ERROR"); assert.equal(callback.result, undefined);
      assert.equal(callback.server, "parity"); assert.equal(callback.tool, expected.name); assert.deepEqual(callback.rpcError, expected.error);
    } else { assert.equal(callback.origin, "MCP"); assert.deepEqual(callback.result, expected.result); assert.equal(callback.rpcError, undefined); }
    assert.ok(typeof callback.id === "string" && callback.id.length > 0 && !ids.has(callback.id), "Candidate callback id is absent or reused"); ids.add(callback.id);
    const results: ObjectValue[] = candidate.toolResults.map(value => object(value, "Candidate typed result")).filter(value => value.id === callback.id);
    assert.equal(results.length, 1, "Candidate MCP result does not have exactly one permission callback owner");
    const result = results[0]!;
    assert.equal(result.origin, "MCP"); assert.equal(result.disposition, "TOOL_OUTPUT");
    if ("error" in expected) {
      assert.ok(expected.codeModeValue, "An RPC error cannot invent a synthesized MCP value without actual source output");
      assert.equal(result.isError, true); assert.deepEqual(result.payload, expected.codeModeValue);
      assert.deepEqual(result.value, expected.codeModeValue, "Candidate RPC normalization differs from the actual source-resolved value");
    } else {
      assert.equal(result.isError, expected.result.isError); assert.deepEqual(result.payload, expected.result);
      const { _meta: privateMetadata, ...visible } = expected.result;
      assert.ok(privateMetadata, "The MCP metadata exclusion witness is missing");
      assert.deepEqual(result.value, visible, "Candidate changed the MCP envelope or exposed private metadata");
    }
  }
  assert.equal(candidate.toolResults.length, ids.size, "Candidate retained an unowned or duplicate MCP result");
}
