import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import type { CurrentInventory, SourceUnit } from "./current-inventory";
import type { CoreValueWitness } from "./current-core-values";
import type { CliRun, ObjectValue } from "./current-cli/types";
import { mcpSearchProbes, mcpSearchScenario } from "./current-cli/mcp-search-cases";
import { sha256 } from "./qualification";

type Pin = { id: string; sha256: string; start: number; end: number; source: string };
type Review = { sourceRevision: string; sourceTree: string; root: { path: string; sha256: string; gitBlob: string };
  owner: Pin; members: Pin[]; routes: Pin[] };
const review: Review = JSON.parse(readFileSync(resolve(import.meta.dir, "current-cli-search-values.json"), "utf8"));
const values = [review.owner, ...review.members], pins = [...values, ...review.routes];
const object = (value: unknown): value is ObjectValue => value !== null && typeof value === "object" && !Array.isArray(value);
const kind = (pin: Pin) => pin.id.slice(pin.id.indexOf("#") + 1, pin.id.lastIndexOf("@"));

function sourceBound(inventory: CurrentInventory): boolean {
  if (inventory.sourceRevision !== review.sourceRevision || inventory.sourceTree !== review.sourceTree ||
    !inventory.files.some(file => file.path === review.root.path && file.sha256 === review.root.sha256 && file.gitBlob === review.root.gitBlob)) return false;
  const units = new Map(inventory.units.map(unit => [unit.id, unit]));
  return units.size === inventory.units.length && pins.every(pin => {
    const unit = units.get(pin.id);
    return unit?.sha256 === pin.sha256 && unit.file === review.root.path && unit.start === pin.start && unit.end === pin.end && unit.kind === kind(pin);
  });
}

/** Serialized search results only; handler/cache layouts and every similarly named DTO stay outside this set. */
export function reviewedCliSearchMember(unit: SourceUnit, inventory: CurrentInventory): boolean {
  return sourceBound(inventory) && values.some(pin => unit.id === pin.id && unit.sha256 === pin.sha256 && unit.start === pin.start &&
    unit.end === pin.end && unit.file === review.root.path && unit.kind === kind(pin));
}

/** Decode only an unchanged, complete script JSON value whose actual reference root is Vec<CodeModeSearchResult>. */
export function cliSearchMemberValues(source: CliRun, candidate: ObjectValue, inventory: CurrentInventory): CoreValueWitness[] {
  if (!source.scenario.mcp || !source.scenario.rankedSearch) return [];
  assert.deepEqual(source.scenario, mcpSearchScenario(), "Search DTO values require the exact real reviewed probes");
  assert.ok(sourceBound(inventory), "Search DTO source bytes or typed serialization route changed");
  const observation = source.observations.length - 1, input = source.observations[observation]?.input;
  assert.ok(input && Array.isArray(candidate.posted) && Array.isArray(candidate.posted.at(-1)), "Search DTO values require paired actual model inputs");
  const actual = candidate.posted.at(-1) as ObjectValue[], found = new Map<string, CoreValueWitness>();
  const observe = (pin: Pin, value: unknown, path: Array<string | number>) => {
    if (!found.has(pin.id)) found.set(pin.id, { id: pin.id, sha256: pin.sha256, relation: "typed-source-shape-v1", path,
      valueSha256: sha256(JSON.stringify(value)) });
  };
  for (const probe of mcpSearchProbes) {
    const id = `probe-${source.scenario.id}-${probe.action}`;
    const outputs: Array<{ item: ObjectValue; index: number }> = input.map((item, index) => ({ item, index })).filter(({ item }) => item.type === "custom_tool_call_output" && item.call_id === id);
    const targets: ObjectValue[] = actual.filter(item => object(item) && item.type === "custom_tool_call_output" && item.call_id === id);
    assert.equal(outputs.length, 1); assert.equal(targets.length, 1);
    const sourceOutput = outputs[0]!, output = sourceOutput.item.output, target = targets[0]!.output;
    assert.ok(Array.isArray(output) && output.length === 2 && Array.isArray(target) && target.length === 2);
    const text = output[1], other = target[1];
    assert.ok(object(text) && text.type === "input_text" && typeof text.text === "string" && object(other) && other.type === "input_text");
    // Normalized equivalent ties deliberately earn no value credit. Whole raw text equality is the precondition.
    if (text.text !== other.text) continue;
    const parsed = JSON.parse(text.text); assert.equal(JSON.stringify(parsed), text.text, "Search script JSON has duplicate or unobserved structure");
    assert.deepEqual(Object.keys(parsed), ["tag", "value"]); assert.equal(parsed.tag, probe.tag);
    assert.ok(object(parsed.value)); assert.deepEqual(Object.keys(parsed.value), ["tools"]); assert.ok(Array.isArray(parsed.value.tools));
    for (const [index, result] of parsed.value.tools.entries()) {
      assert.ok(object(result)); assert.deepEqual(Object.keys(result), ["name", "description"]);
      assert.equal(typeof result.name, "string"); assert.equal(typeof result.description, "string");
      const path = ["observations", observation, "input", sourceOutput.index, "output", 1, "text", "JSON.parse", "value", "tools", index];
      observe(review.owner, result, path);
      for (const member of review.members) {
        const key = member.source.split(":")[0]!; observe(member, result[key], [...path, key]);
      }
    }
  }
  return [...found.values()].sort((a, b) => a.id.localeCompare(b.id));
}
