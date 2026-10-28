import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import type { CliRun, CliScenario, Json, ObjectValue } from "./types";
import { compareMcpBoundary, mcpGatewayFields, sourceMcpTranscript, type McpTranscript } from "./mcp-contract";
import { compareFreeformBoundary, freeformGatewayFields, sourceFreeformTranscript, type FreeformTranscript } from "./freeform-contract";
import { compareInterruption, sourceInterruption } from "./interruption-contract";
import { canonicalMcpSearchPackets } from "./mcp-search-contract";
import { canonicalSelectedMcpCall, mcpSearchCallbackPlan, selectedMcpTranscript } from "./mcp-search-call";

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

export function compareControlPackets(source: CliRun, candidate: ObjectValue, candidateDirectory?: string): void {
  const hash = (value: string) => createHash("sha256").update(value).digest("hex");
  assert.equal(source.inputSha256, hash(JSON.stringify(source.scenario)), "Reference scenario identity differs");
  assert.equal(candidate.nonce, source.nonce, "Gateway result belongs to a different run");
  assert.equal(candidate.inputSha256, hash(sourceGatewayInputBytes(source)), "Gateway did not consume the bound scenario bytes");
  if (!Array.isArray(candidate.posted) || !candidate.posted.length) throw Error("Candidate gateway supplied no actual model requests");
  if (!source.observations.length) throw Error("Actual reference supplied no actual model requests");
  const input = candidate.posted.at(-1);
  if (!Array.isArray(input) || input.some(item => item === null || typeof item !== "object" || Array.isArray(item))) throw Error("Invalid candidate model input");
  const reference = protocolSequence(source.observations.at(-1)!.input), actual = protocolSequence(input as ObjectValue[]);
  const mcp = source.scenario.mcp ? sourceMcpTranscript(source) : undefined;
  let packets = [reference, actual];
  if (mcp && source.scenario.rankedSearch) {
    packets = canonicalSelectedMcpCall(source, mcp, reference, actual);
    packets = canonicalMcpSearchPackets(source, packets[0]!, packets[1]!);
  }
  assert.deepStrictEqual(packets[1], packets[0], "Actual source and gateway calls/results/history order differ");
  if (mcp) compareMcpBoundary(source.scenario.rankedSearch ? selectedMcpTranscript(source, mcp, actual) : mcp, candidate);
  if (source.scenario.applyPatch) {
    assert.ok(candidateDirectory, "Freeform comparison requires the actual candidate directory");
    compareFreeformBoundary(sourceFreeformTranscript(source), candidate, candidateDirectory);
  }
  if (source.scenario.interruption) compareInterruption(source, candidate);
}

export function candidateOutcomesMatch(source: CliRun, candidate: ObjectValue): boolean {
  if (source.scenario.interruption) return JSON.stringify(candidate.outcomes) === JSON.stringify(["Cancelled", "Success"]);
  return Array.isArray(candidate.outcomes) && candidate.outcomes.length > 0 && candidate.outcomes.every(value => value === "Success");
}

/** The real gateway fixture accepts exactly the same resolved outer scripts and wait operations. */
export function gatewayInput(scenario: CliScenario, mcp?: McpTranscript, freeform?: FreeformTranscript, interruption?: ObjectValue): ObjectValue {
  if (scenario.mcp && !mcp) throw Error("MCP result envelopes require the actual reference peer transcript");
  if (!scenario.mcp && mcp) throw Error("A generic scenario cannot claim MCP origin");
  if (scenario.applyPatch && !freeform) throw Error("Freeform effects require the actual reference transcript and tool build");
  if (!scenario.applyPatch && freeform) throw Error("A generic scenario cannot claim builtin freeform effects");
  if (scenario.interruption && !interruption) throw Error("User interruption requires the actual PTY and native aborted-turn evidence");
  if (!scenario.interruption && interruption) throw Error("A generic scenario cannot claim user interruption");
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
    ...(freeform ? freeformGatewayFields(freeform) : {}),
    ...(interruption ? { interruption } : {}),
    ...(scenario.rankedSearch ? { rankedSearch: true } : {}),
  };
}

export function gatewayInputBytes(scenario: CliScenario, mcp?: McpTranscript, freeform?: FreeformTranscript, interruption?: ObjectValue): string { return JSON.stringify(gatewayInput(scenario, mcp, freeform, interruption)) + "\n"; }

/** MCP candidate inputs are derived from measured source exchanges, never invented from a case label. */
export function sourceGatewayInputBytes(source: CliRun): string {
  const mcp = source.scenario.mcp ? sourceMcpTranscript(source) : undefined;
  const input = gatewayInput(source.scenario, mcp, source.scenario.applyPatch ? sourceFreeformTranscript(source) : undefined,
    source.scenario.interruption ? sourceInterruption(source) : undefined);
  if (mcp && source.scenario.rankedSearch) input.callbacks = mcpSearchCallbackPlan(source, mcp);
  return JSON.stringify(input) + "\n";
}
