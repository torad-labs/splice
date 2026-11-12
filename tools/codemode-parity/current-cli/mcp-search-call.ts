import assert from "node:assert/strict";
import { mcpFixtureNames, mcpGatewayFields, type McpExchange, type McpTranscript } from "./mcp-contract";
import { mcpSearchEvidenceStart, mcpSearchProbes, mcpSearchSelectedAction, mcpSearchSelectedArguments } from "./mcp-search-cases";
import type { CliRun, ObjectValue } from "./types";

function callItem(source: CliRun, action: number): string { return `item-probe-${source.scenario.id}-${action}`; }

function branches(source: CliRun, transcript: McpTranscript): McpExchange[] {
  return mcpFixtureNames.map((name, index) => {
    const calls = transcript.calls.filter(call => call.sourceContext?.itemId === callItem(source, mcpSearchEvidenceStart + index));
    assert.equal(calls.length, 1, "A selected callable lacks an actual source peer observation");
    const call = calls[0]!; assert.equal(call.name, name); assert.ok("result" in call, "A resolved MCP branch cannot use an RPC error");
    assert.deepEqual(call.arguments, mcpSearchSelectedArguments);
    return call as McpExchange;
  });
}

function selectionIndex(source: CliRun, transcript: McpTranscript): number {
  const indices = transcript.calls.flatMap((call, index) => call.sourceContext?.itemId === callItem(source, mcpSearchSelectedAction) ? [index] : []);
  assert.equal(indices.length, 1, "The actual selected tool invocation is absent or duplicated");
  return indices[0]!;
}

/** Every permitted alternative is a complete real peer response to the identical arguments, with an exact schema. */
export function mcpSearchCallbackPlan(source: CliRun, transcript: McpTranscript): ObjectValue[] {
  const callbacks = mcpGatewayFields(transcript).callbacks as ObjectValue[];
  const alternatives = mcpGatewayFields({ ...transcript, calls: branches(source, transcript) }).callbacks as ObjectValue[];
  callbacks[selectionIndex(source, transcript)] = { alternatives };
  return callbacks;
}

function selectedName(source: CliRun, packets: ObjectValue[]): string {
  const probe = mcpSearchProbes.find(probe => probe.storeSelected)!;
  const packet = packets.find(packet => packet.type === "custom_tool_call_output" && packet.call_id === `probe-${source.scenario.id}-${probe.action}`)!;
  const value = JSON.parse(String((packet.output as ObjectValue[])[1]!.text));
  assert.equal(value.tag, probe.tag); assert.equal(value.value.tools.length, 1);
  const name = value.value.tools[0].name;
  assert.ok(mcpFixtureNames.some(member => name === `mcp__parity__${member}`), "Selected callable is not in the advertised MCP catalog");
  return name;
}

function visible(call: McpExchange): ObjectValue { const { _meta, ...result } = call.result; assert.ok(_meta); return result; }

/** A different tied winner must still execute its own actual permission callback and retain its own complete value. */
export function selectedMcpTranscript(source: CliRun, transcript: McpTranscript, candidatePackets: ObjectValue[]): McpTranscript {
  const name = selectedName(source, candidatePackets), choice = branches(source, transcript).find(call => name === `mcp__parity__${call.name}`)!;
  const calls = [...transcript.calls]; calls[selectionIndex(source, transcript)] = choice;
  return { ...transcript, calls };
}

export function canonicalSelectedMcpCall(source: CliRun, transcript: McpTranscript, reference: ObjectValue[], candidate: ObjectValue[]): [ObjectValue[], ObjectValue[]] {
  const options = branches(source, transcript), selectedIndex = selectionIndex(source, transcript);
  const actualReference = transcript.calls[selectedIndex]!;
  assert.equal(`mcp__parity__${actualReference.name}`, selectedName(source, reference), "Source invoked a different member from its selected search result");
  assert.deepEqual(actualReference.arguments, mcpSearchSelectedArguments);
  return [reference, candidate].map(packets => {
    const name = selectedName(source, packets), chosen = options.find(call => name === `mcp__parity__${call.name}`)!;
    const result = structuredClone(packets), callId = `probe-${source.scenario.id}-${mcpSearchSelectedAction}`;
    const outputs = result.filter(packet => packet.type === "custom_tool_call_output" && packet.call_id === callId);
    assert.equal(outputs.length, 1); const output = outputs[0]!;
    assert.ok(Array.isArray(output.output) && output.output.length === 2, "Selected call script did not resolve its complete MCP value");
    const text = output.output[1] as ObjectValue; assert.deepEqual(Object.keys(text), ["type", "text"]); assert.equal(text.type, "input_text");
    const value = JSON.parse(String(text.text));
    assert.equal(text.text, JSON.stringify(value)); assert.deepEqual(Object.keys(value), ["tag", "name", "value"]);
    assert.equal(value.tag, "mcp-selected-call"); assert.equal(value.name, name);
    assert.deepEqual(value.value, visible(chosen), "Selected MCP value lost its own error, content or structured data");
    text.text = JSON.stringify({ tag: "mcp-selected-call", equalScoreBranches: options.map(call => ({ name: `mcp__parity__${call.name}`, value: visible(call) })) });
    return result;
  }) as [ObjectValue[], ObjectValue[]];
}
