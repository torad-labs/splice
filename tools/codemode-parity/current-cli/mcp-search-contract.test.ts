import { describe, expect, test } from "bun:test";
import assert from "node:assert/strict";
import { canonicalMcpSearchPackets } from "./mcp-search-contract";
import { assertMcpSearchRank, mcpSearchCorpus, mcpSearchProbes, mcpSearchRankFixtures, mcpSearchScenario, mcpSearchSelectedArguments } from "./mcp-search-cases";
import { canonicalSelectedMcpCall, mcpSearchCallbackPlan, selectedMcpTranscript } from "./mcp-search-call";
import { mcpFixtureCall, mcpFixtureNames, mcpFixtureTools, type McpTranscript } from "./mcp-contract";
import { currentCoreFixtures } from "../current-core";
import type { CliRun, ObjectValue } from "./types";

const declarations = mcpFixtureTools().map(tool => ({ name: `mcp__parity__${tool.name}`,
  description: `${tool.description}\n\nexec tool declaration:\n\`\`\`ts\ndeclare const tools: { mcp__parity__${tool.name}(args: { value: string; }): Promise<CallToolResult>; };\n\`\`\`` }));
const source = { scenario: mcpSearchScenario() } as CliRun;

function packets(first = 0, tiedOrder = [0, 1, 2], cutoffTie = 1): ObjectValue[] {
  return source.scenario.actions.flatMap((action, index) => {
    assert.equal(action.kind, "exec");
    const call = { type: "custom_tool_call", call_id: `probe-mcp-search-${index}`, name: "exec", input: action.source };
    const probe = mcpSearchProbes.find(probe => probe.action === index);
    const selected = !probe ? [] : index === 0 || index === 11 ? [first] : index === 2 ? tiedOrder : index === 6 ? [0, cutoffTie] : index === 7 ? [] : index === 8 ? [0] : index === 9 || index === 10 ? [0, 1] : [index - 3];
    const member = mcpFixtureNames[index === 12 ? first : index - 13] ?? "echo";
    const { _meta, ...visible } = mcpFixtureCall(member, mcpSearchSelectedArguments).result;
    const value = probe ? { tag: probe.tag, value: { tools: selected.map(index => declarations[index]!) } }
      : index >= 12 ? { tag: index === 12 ? "mcp-selected-call" : `mcp-selected-evidence-${member}`, name: `mcp__parity__${member}`, value: visible }
      : { tag: "discovered-call", value: "actual bound callback value" };
    return [call, { type: "custom_tool_call_output", call_id: call.call_id, output: [
      { type: "input_text", text: "Script completed\nWall time <measured> seconds\nOutput:\n" },
      { type: "input_text", text: JSON.stringify(value) },
    ] }];
  });
}

function transcript(winner: number): McpTranscript {
  const named = (name: typeof mcpFixtureNames[number], action: number, argumentsValue = mcpSearchSelectedArguments) => ({
    ...mcpFixtureCall(name, argumentsValue), sourceContext: { itemId: `item-probe-mcp-search-${action}` },
  });
  return { tools: mcpFixtureTools(), calls: [named("echo", 1, { value: "after-search" }), named(mcpFixtureNames[winner]!, 12),
    ...mcpFixtureNames.map((name, index) => named(name, 13 + index))] };
}

function changeResult(packets: ObjectValue[], action: number, transform: (value: ObjectValue) => void): void {
  const packet = packets.find(packet => packet.type === "custom_tool_call_output" && packet.call_id === `probe-mcp-search-${action}`)!;
  const text = (packet.output as ObjectValue[])[1]!, value = JSON.parse(text.text as string);
  transform(value); text.text = JSON.stringify(value);
}

describe("source-scored MCP search comparison", () => {
  test("only proven equal-score names and ordering vary while every raw declaration and packet remains retained", () => {
    const reference = packets(2, [2, 0, 1], 2), candidate = packets(0, [0, 1, 2], 1);
    const before = JSON.stringify([reference, candidate]);
    const [left, right] = canonicalMcpSearchPackets(source, reference, candidate);
    expect(right.filter(packet => packet.call_id !== "probe-mcp-search-12")).toEqual(left.filter(packet => packet.call_id !== "probe-mcp-search-12"));
    expect(JSON.stringify([reference, candidate])).toBe(before);
    expect(right.filter(packet => packet.call_id === "probe-mcp-search-1")).toEqual(candidate.filter(packet => packet.call_id === "probe-mcp-search-1"));
  });

  test("ties cannot hide missing declarations, duplicate names, fabricated documentation or too many results", () => {
    const mutations = [
      (value: ObjectValue) => { (value.value as ObjectValue).tools = []; },
      (value: ObjectValue) => { (value.value as ObjectValue).tools = [declarations[0]!, declarations[1]!]; },
      (value: ObjectValue) => { ((value.value as ObjectValue).tools as ObjectValue[])[0] = { name: "mcp__foreign__echo", description: "foreign" }; },
      (value: ObjectValue) => { ((value.value as ObjectValue).tools as ObjectValue[])[0] = { ...declarations[0]!, description: "fabricated" }; },
    ];
    for (const mutation of mutations) {
      const candidate = packets(); changeResult(candidate, 0, mutation);
      expect(() => canonicalMcpSearchPackets(source, packets(), candidate)).toThrow();
    }
    const duplicate = packets(); changeResult(duplicate, 2, value => { (value.value as ObjectValue).tools = [declarations[0]!, declarations[0]!, declarations[2]!]; });
    expect(() => canonicalMcpSearchPackets(source, packets(), duplicate)).toThrow("duplicate");
  });

  test("strictly higher scores, zero-score exclusion and cutoff order remain mandatory", () => {
    const mutations: Array<[number, number[]]> = [[6, [1, 2]], [6, [1, 0]], [3, [1]], [7, [0]]];
    for (const [action, indices] of mutations) {
      const candidate = packets(); changeResult(candidate, action, value => { (value.value as ObjectValue).tools = indices.map(index => declarations[index]!); });
      expect(() => canonicalMcpSearchPackets(source, packets(), candidate)).toThrow();
    }
  });

  test("different tied winners must execute their own permission callback and preserve ordinary errors and structured data", () => {
    const actualSource = transcript(2), reference = packets(2);
    for (const winner of [0, 1, 2]) {
      const candidate = packets(winner);
      const [left, right] = canonicalSelectedMcpCall(source, actualSource, reference, candidate);
      const canonical = canonicalMcpSearchPackets(source, left, right);
      expect(canonical[1]).toEqual(canonical[0]);
      const expected = selectedMcpTranscript(source, actualSource, candidate);
      expect(expected.calls[1]!.name).toBe(mcpFixtureNames[winner]!);
      const plan = mcpSearchCallbackPlan(source, actualSource);
      expect((plan[1]!.alternatives as ObjectValue[]).map(call => call.name)).toEqual(mcpFixtureNames.map(name => `mcp__parity__${name}`));
    }
    for (const mutate of [
      (value: ObjectValue) => { value.name = "mcp__parity__echo"; },
      (value: ObjectValue) => { delete (value.value as ObjectValue).structuredContent; },
      (value: ObjectValue) => { (value.value as ObjectValue).isError = true; },
      (value: ObjectValue) => { (value.value as ObjectValue)._meta = { private: true }; },
      (value: ObjectValue) => { value.value = "stringified result"; },
    ]) {
      const forged = packets(2); changeResult(forged, 12, mutate);
      expect(() => canonicalSelectedMcpCall(source, actualSource, reference, forged)).toThrow();
    }
    const ordinary = packets(1); changeResult(ordinary, 12, value => { (value.value as ObjectValue).isError = false; });
    expect(() => canonicalSelectedMcpCall(source, actualSource, reference, ordinary)).toThrow();
    const missing = transcript(2); missing.calls.pop();
    expect(() => mcpSearchCallbackPlan(source, missing)).toThrow("actual source peer observation");
    const differentArgs = transcript(2); differentArgs.calls[4]!.arguments = { value: "other observation" };
    expect(() => mcpSearchCallbackPlan(source, differentArgs)).toThrow();
  });

  test("a complete actual reference listing and exact reviewed query scripts cannot be omitted or replaced", () => {
    const missing = packets().filter(packet => packet.call_id !== "probe-mcp-search-2");
    expect(() => canonicalMcpSearchPackets(source, missing, packets())).toThrow();
    const wrongSource = structuredClone(source); (wrongSource.scenario.actions[0] as { source: string }).source = "text({tools:[]})";
    expect(() => canonicalMcpSearchPackets(wrongSource, packets(), packets())).toThrow("complete reviewed probes");
    const wrongInput = packets(); wrongInput[0]!.input = "text('different query')";
    expect(() => canonicalMcpSearchPackets(source, packets(), wrongInput)).toThrow();
    const extra = packets(); changeResult(extra, 0, value => { value.uncompared = "hidden"; });
    expect(() => canonicalMcpSearchPackets(source, packets(), extra)).toThrow();
  });

  test("the corrected raw MCP corpus and actual Rust score matrix are mandatory core inputs", () => {
    expect(mcpSearchCorpus()[0]).toBe("mcp__parityecho echo echo parity Fixed local echo CodeMode fixture; signature marker quasar-zephyr. value");
    const mandatory = currentCoreFixtures().filter(fixture => fixture.mcp_search_corpus === true);
    expect(mandatory.map(({ id: _id, ...fixture }) => fixture)).toEqual(mcpSearchRankFixtures());
    const tied = mandatory.find(fixture => fixture.query === "quasar-zephyr")!;
    const actualRust = { matches: mcpFixtureNames.map((_name, index) => ({ index, score: 0.26706287 })) };
    expect(() => assertMcpSearchRank(tied, actualRust)).not.toThrow();
    expect(() => assertMcpSearchRank(tied, { matches: [{ index: 0, score: 0.5 }] })).toThrow("Rust scorer");
    expect(() => assertMcpSearchRank({ ...tied, corpus: mcpSearchCorpus().map(value => value.replace("mcp__parity", "mcp__parity__")) }, actualRust)).toThrow();
    expect(() => assertMcpSearchRank({ ...tied, query: "unreviewed" }, actualRust)).toThrow();
  });
});
