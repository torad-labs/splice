import assert from "node:assert/strict";
import { mcpFixtureNames, mcpFixtureTools } from "./mcp-contract";
import { mcpSearchProbes, mcpSearchScenario, mcpSearchScores, type McpSearchProbe } from "./mcp-search-cases";
import type { CliRun, Json, ObjectValue } from "./types";

function object(value: Json | undefined, label: string): ObjectValue {
  assert.ok(value !== null && typeof value === "object" && !Array.isArray(value), `${label} must be an object`);
  return value;
}

function output(packets: ObjectValue[], callId: string): { packet: ObjectValue; text: ObjectValue; value: ObjectValue } {
  const calls = packets.filter(packet => packet.type === "custom_tool_call" && packet.call_id === callId);
  const matches = packets.filter(packet => packet.type === "custom_tool_call_output" && packet.call_id === callId);
  assert.equal(calls.length, 1, "Search result lacks exactly one originating exec");
  assert.equal(matches.length, 1, "Search result is absent or duplicated");
  const packet = matches[0]!;
  assert.ok(Array.isArray(packet.output) && packet.output.length === 2, "Search script output changed shape");
  const text = object(packet.output[1], "Search script text");
  assert.deepEqual(Object.keys(text), ["type", "text"]); assert.equal(text.type, "input_text");
  assert.equal(typeof text.text, "string");
  const value = object(JSON.parse(text.text as string), "Search script value");
  assert.equal(JSON.stringify(value), text.text, "Search result JSON serialization changed");
  return { packet, text, value };
}

function tools(value: ObjectValue, probe: McpSearchProbe): ObjectValue[] {
  assert.deepEqual(Object.keys(value), ["tag", "value"]); assert.equal(value.tag, probe.tag);
  const result = object(value.value, "Search result"); assert.deepEqual(Object.keys(result), ["tools"]);
  assert.ok(Array.isArray(result.tools), "Search tools must be an array");
  return result.tools.map(value => object(value, "Search declaration"));
}

function catalog(source: ObjectValue[], scenarioId: string): Map<string, ObjectValue> {
  const complete = mcpSearchProbes.find(probe => probe.tag === "ranked-search-all")!;
  const declarations = tools(output(source, `probe-${scenarioId}-${complete.action}`).value, complete);
  const names = mcpFixtureNames.map(name => `mcp__parity__${name}`).sort();
  assert.deepEqual(declarations.map(tool => tool.name).sort(), names, "Actual complete reference search omitted a fixture declaration");
  const result = new Map<string, ObjectValue>();
  for (const declaration of declarations) {
    assert.deepEqual(Object.keys(declaration), ["name", "description"]);
    const name = String(declaration.name), raw = mcpFixtureTools().find(tool => name === `mcp__parity__${tool.name}`)!;
    assert.equal(typeof declaration.description, "string");
    assert.ok((declaration.description as string).startsWith(String(raw.description) + "\n\nexec tool declaration:\n```ts\n"), "Reference search lost raw MCP documentation");
    result.set(name, declaration);
  }
  return result;
}

function canonicalTools(selected: ObjectValue[], probe: McpSearchProbe, declarations: Map<string, ObjectValue>): ObjectValue[] {
  const scores = mcpSearchScores(probe.query), ranked = [...scores.values()].sort((a, b) => b - a);
  const count = Math.min(probe.limit, ranked.length), seen = new Set<string>();
  assert.equal(selected.length, count, "Search result count differs from the exact score cutoff");
  let prior = Infinity;
  for (const tool of selected) {
    assert.equal(typeof tool.name, "string"); const name = tool.name as string;
    assert.ok(scores.has(name) && !seen.has(name), "Search selected an unranked or duplicate tool"); seen.add(name);
    assert.deepEqual(tool, declarations.get(name), "Search declaration differs from the actual reference declaration");
    const score = scores.get(name)!;
    assert.ok(score <= prior, "Search results are not in descending score order"); prior = score;
    assert.ok(score >= ranked[count - 1]!, "Search selected below the exact score cutoff");
  }
  for (const [name, score] of scores) if (score > ranked[count - 1]!) {
    assert.ok(seen.has(name), "Search omitted a tool strictly above its cutoff");
  }
  return selected.map(tool => {
    const score = scores.get(String(tool.name))!;
    const tied = [...scores].filter(([_name, other]) => score === other).map(([name]) => name).sort();
    // Every concrete name and its full declaration has already been validated. Only the source's randomized
    // choice/order inside a proven equal-score group is represented canonically for the packet comparison.
    return tied.length === 1 ? tool : { equalScore: score, eligibleNames: tied };
  });
}

/** Narrow equivalence for the reviewed real MCP search probes; all other bytes and packets stay exact. */
export function canonicalMcpSearchPackets(source: CliRun, reference: ObjectValue[], candidate: ObjectValue[]): [ObjectValue[], ObjectValue[]] {
  assert.deepEqual(source.scenario, mcpSearchScenario(), "MCP search comparison requires the complete reviewed probes");
  const declarations = catalog(reference, source.scenario.id);
  return [reference, candidate].map(packets => {
    const result = structuredClone(packets);
    for (const probe of mcpSearchProbes) {
      const callId = `probe-${source.scenario.id}-${probe.action}`;
      const actual = output(result, callId);
      const call = result.find(packet => packet.type === "custom_tool_call" && packet.call_id === callId)!;
      assert.equal(call.input, source.scenario.actions[probe.action]!.kind === "exec" ? (source.scenario.actions[probe.action] as { source: string }).source : undefined);
      const canonical = canonicalTools(tools(actual.value, probe), probe, declarations);
      actual.text.text = JSON.stringify({ ...actual.value, value: { tools: canonical } });
    }
    return result;
  }) as [ObjectValue[], ObjectValue[]];
}
