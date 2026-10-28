import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import type { CliRun, CliScenario, Json, ObjectValue } from "./types";
import { compareMcpBoundary, mcpGatewayFields, sourceMcpTranscript, type McpTranscript } from "./mcp-contract";

const callTypes = new Set(["function_call", "custom_tool_call", "function_call_output", "custom_tool_call_output"]);
const status = /^(Script (?:completed|failed|terminated|running with cell ID [^\n]+)\n)Wall time [0-9]+\.[0-9] seconds\nOutput:\n$/;

function measurement(output: Json): Json {
  if (typeof output === "string" && status.test(output)) return output.replace(status, "$1Wall time <measured> seconds\nOutput:\n");
  if (Array.isArray(output) && output.length && output[0] !== null && typeof output[0] === "object" && !Array.isArray(output[0])) {
    const first = output[0];
    if (first.type === "input_text" && typeof first.text === "string") return [{ ...first, text: measurement(first.text) }, ...output.slice(1)];
  }
  return output;
}

/** Only generated optional output-item IDs and exact first status-header measurements are volatile here. */
export function protocolSequence(input: ObjectValue[]): ObjectValue[] {
  return input.filter(item => callTypes.has(String(item.type))).map(item => Object.fromEntries(
    Object.entries(item).filter(([key]) => key !== "id" || !String(item.type).endsWith("_output")).map(([key, value]) => [key, key === "output" ? measurement(value) : value]),
  ));
}

export function compareControlPackets(source: CliRun, candidate: ObjectValue): void {
  const hash = (value: string) => createHash("sha256").update(value).digest("hex");
  assert.equal(source.inputSha256, hash(JSON.stringify(source.scenario)), "Reference scenario identity differs");
  assert.equal(candidate.nonce, source.nonce, "Gateway result belongs to a different run");
  assert.equal(candidate.inputSha256, hash(sourceGatewayInputBytes(source)), "Gateway did not consume the bound scenario bytes");
  if (!Array.isArray(candidate.posted) || !candidate.posted.length) throw Error("Candidate gateway supplied no actual model requests");
  if (!source.observations.length) throw Error("Actual reference supplied no actual model requests");
  const input = candidate.posted.at(-1);
  if (!Array.isArray(input) || input.some(item => item === null || typeof item !== "object" || Array.isArray(item))) throw Error("Invalid candidate model input");
  assert.deepStrictEqual(protocolSequence(input as ObjectValue[]), protocolSequence(source.observations.at(-1)!.input), "Actual source and gateway calls/results/history order differ");
  if (source.scenario.mcp) compareMcpBoundary(sourceMcpTranscript(source), candidate);
}

/** The real gateway fixture accepts exactly the same resolved outer scripts and wait operations. */
export function gatewayInput(scenario: CliScenario, mcp?: McpTranscript): ObjectValue {
  if (scenario.mcp && !mcp) throw Error("MCP result envelopes require the actual reference peer transcript");
  if (!scenario.mcp && mcp) throw Error("A generic scenario cannot claim MCP origin");
  const actions = scenario.actions.map((action, index): ObjectValue => {
    const id = `probe-${scenario.id}-${index}`;
    if (action.kind === "wait") return { type: "function_call", name: "wait", call_id: id, arguments: {
      cell_id: action.cellId ?? "last_yielded_cell", yield_time_ms: action.yieldTimeMs ?? 1000, ...(action.terminate ? { terminate: true } : {}),
    } };
    const calls = [action.source, ...(action.followingSources ?? [])].map((input, extra) => ({
      type: "custom_tool_call", name: "exec", call_id: id + (extra ? `-${extra}` : ""), input,
    }));
    return calls.length > 1 ? { calls } : calls[0]!;
  });
  return {
    schema: 1,
    tools: [{ name: "Ping", description: "Unused control-case catalog entry", input_schema: { type: "object", properties: {} } }],
    declarations: [{ type: "function", name: "Ping", description: "Unused control-case catalog entry", parameters: { type: "object", properties: {} } }],
    actions, callbacks: [],
    ...(mcp ? mcpGatewayFields(mcp) : {}),
    ...(scenario.rankedSearch ? { rankedSearch: true } : {}),
  };
}

export function gatewayInputBytes(scenario: CliScenario, mcp?: McpTranscript): string { return JSON.stringify(gatewayInput(scenario, mcp)) + "\n"; }

/** MCP candidate inputs are derived from measured source exchanges, never invented from a case label. */
export function sourceGatewayInputBytes(source: CliRun): string {
  return gatewayInputBytes(source.scenario, source.scenario.mcp ? sourceMcpTranscript(source) : undefined);
}
