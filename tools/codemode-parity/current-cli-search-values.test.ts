import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { cliSearchMemberValues, reviewedCliSearchMember } from "./current-cli-search-values";
import { mcpSearchProbes, mcpSearchScenario } from "./current-cli/mcp-search-cases";
import { sha256 } from "./qualification";
import type { CurrentInventory, SourceUnit } from "./current-inventory";
import type { CliRun, ObjectValue } from "./current-cli/types";

const review = JSON.parse(readFileSync(resolve(import.meta.dir, "current-cli-search-values.json"), "utf8"));
function inventory(): CurrentInventory {
  return { sourceRevision: review.sourceRevision, sourceTree: review.sourceTree, files: [structuredClone(review.root)],
    units: [review.owner, ...review.members, ...review.routes].map(pin => ({ ...pin, file: review.root.path,
      kind: pin.id.slice(pin.id.indexOf("#") + 1, pin.id.lastIndexOf("@")), ownership: "ported" })) } as unknown as CurrentInventory;
}
function paired(): { source: CliRun; candidate: ObjectValue } {
  const input = mcpSearchProbes.map(probe => ({ type: "custom_tool_call_output", call_id: `probe-mcp-search-${probe.action}`, output: [
    { type: "input_text", text: "Script completed\nWall time 0.0 seconds\nOutput:\n" },
    { type: "input_text", text: JSON.stringify({ tag: probe.tag, value: { tools: probe.action === 7 ? [] : [{ name: "mcp__parity__echo", description: "Actual source declaration" }] } }) },
  ] }));
  return { source: { scenario: mcpSearchScenario(), observations: [{ request: 1, input }] } as unknown as CliRun, candidate: { posted: [structuredClone(input)] } };
}
function output(value: ObjectValue, index = 0): ObjectValue { return ((value.posted as ObjectValue[][])[0]![index]!.output as ObjectValue[])[1]!; }

describe("actual CLI search DTO members", () => {
  test("raw equal complete search result objects emit only the reviewed owner and its two fields", () => {
    const pair = paired(), found = cliSearchMemberValues(pair.source, pair.candidate, inventory());
    expect(found.map(value => value.id)).toEqual([review.owner, ...review.members].map(pin => pin.id).sort());
    expect(found.find(value => value.id === review.members[0].id)!.valueSha256).toBe(sha256(JSON.stringify("mcp__parity__echo")));
    expect(found.find(value => value.id === review.members[1].id)!.path).toEqual(["observations", 0, "input", 0, "output", 1, "text", "JSON.parse", "value", "tools", 0, "description"]);
    for (const pin of [review.owner, ...review.members]) expect(reviewedCliSearchMember(inventory().units.find(unit => unit.id === pin.id)!, inventory())).toBe(true);
    expect(found.some(value => value.id.includes("function_item"))).toBe(false);
  });

  test("equivalent normalized ties never grant raw value credit", () => {
    const pair = paired();
    for (let index = 0; index < mcpSearchProbes.length; index++) {
      const text = output(pair.candidate, index), value = JSON.parse(text.text as string);
      if (value.value.tools.length) value.value.tools[0].name = "mcp__parity__structured";
      text.text = JSON.stringify(value);
    }
    expect(cliSearchMemberValues(pair.source, pair.candidate, inventory())).toEqual([]);
  });

  test("serialization roots, exact declaration hashes and route bodies are required", () => {
    for (const mutate of [
      (value: CurrentInventory) => { value.sourceRevision = "0".repeat(40); },
      (value: CurrentInventory) => { value.sourceTree = "0".repeat(40); },
      (value: CurrentInventory) => { value.files[0]!.sha256 = "0".repeat(64); },
      (value: CurrentInventory) => { value.units[1]!.sha256 = "0".repeat(64); },
      (value: CurrentInventory) => { value.units.at(-1)!.start++; },
      (value: CurrentInventory) => { value.units.push(structuredClone(value.units[0]!)); },
    ]) {
      const changed = inventory(); mutate(changed); const pair = paired();
      expect(reviewedCliSearchMember(changed.units[0]!, changed)).toBe(false);
      expect(() => cliSearchMemberValues(pair.source, pair.candidate, changed)).toThrow("source bytes");
    }
  });

  test("field names on another declaration and internal handler layouts cannot inherit a search DTO observation", () => {
    const current = inventory(), owner = current.units[0]!;
    for (const forged of [
      { ...owner, id: owner.id.replace("struct_item@1310", "struct_item@1386") },
      { ...current.units[1]!, file: "codex-rs/tools/src/tool_search.rs" },
      { ...current.units[1]!, kind: "function_item" },
      { ...current.units[1]!, id: review.root.path + "#field_declaration@1421" },
    ]) expect(reviewedCliSearchMember(forged as SourceUnit, current)).toBe(false);
  });

  test("missing packets, fabricated shape, duplicate JSON members and wrong probe identity refuse claims", () => {
    const absent = paired(); (absent.candidate.posted as ObjectValue[][])[0]!.pop();
    expect(() => cliSearchMemberValues(absent.source, absent.candidate, inventory())).toThrow();
    for (const transform of [
      (value: any) => { value.value.tools[0].extra = "unobserved"; },
      (value: any) => { value.value.tools[0].name = 1; },
      (value: any) => { value.tag = "different probe"; },
      (value: any) => { value.value.sibling = true; },
    ]) {
      const pair = paired(), text = output(pair.candidate), value = JSON.parse(text.text as string); transform(value);
      text.text = JSON.stringify(value); ((pair.source.observations[0]!.input[0]!.output as ObjectValue[])[1]!).text = text.text;
      expect(() => cliSearchMemberValues(pair.source, pair.candidate, inventory())).toThrow();
    }
    const duplicate = paired(), text = output(duplicate.candidate);
    text.text = String(text.text).replace('"name":"mcp__parity__echo"', '"name":"mcp__parity__echo","name":"mcp__parity__echo"');
    ((duplicate.source.observations[0]!.input[0]!.output as ObjectValue[])[1]!).text = text.text;
    expect(() => cliSearchMemberValues(duplicate.source, duplicate.candidate, inventory())).toThrow("duplicate");
  });
});
