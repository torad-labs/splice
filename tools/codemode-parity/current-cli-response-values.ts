import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import type { CurrentInventory, SourceUnit } from "./current-inventory";
import type { CoreValueWitness } from "./current-core-values";
import type { CliRun, ObjectValue } from "./current-cli/types";
import { sha256 } from "./qualification";

type Pin = { id: string; sha256: string; start: number; end: number };
type Review = {
  sourceRevision: string; sourceTree: string;
  roots: Array<{ path: string; sha256: string; gitBlob: string }>;
  root: { owner: Pin };
  variants: Array<{ wireType: string; unit: Pin; fields: Array<{ wireKey: string; unit: Pin }> }>;
  outputProjection: { payload: Pin; bodyField: Pin; body: Pin; variants: Array<{ unit: Pin; tuple: Pin; shape: string }> };
};
const review: Review = JSON.parse(readFileSync(resolve(import.meta.dir, "current-cli-response-values.json"), "utf8"));
const calls = new Set(review.variants.map(variant => variant.wireType));
type Path = Array<string | number>;
const object = (value: unknown): value is ObjectValue => value !== null && typeof value === "object" && !Array.isArray(value);
const reviewed = [review.root.owner, ...review.variants.flatMap(variant => [variant.unit, ...variant.fields.map(field => field.unit)]),
  review.outputProjection.payload, review.outputProjection.bodyField, review.outputProjection.body,
  ...review.outputProjection.variants.flatMap(variant => [variant.unit, variant.tuple])];

/** Only this independently reviewed typed-root/member set may name a gateway value-observation plan. */
export function reviewedCliResponseMember(unit: SourceUnit, inventory: CurrentInventory): boolean {
  const file = review.roots.find(item => item.path === "codex-rs/protocol/src/models.rs")!;
  return inventory.sourceRevision === review.sourceRevision && inventory.sourceTree === review.sourceTree &&
    inventory.files.some(item => item.path === file.path && item.sha256 === file.sha256) && unit.file === file.path &&
    reviewed.some(pin => pin.id === unit.id && pin.sha256 === unit.sha256 && pin.start === unit.start && pin.end === unit.end &&
      unit.kind === pin.id.slice(pin.id.indexOf("#") + 1, pin.id.lastIndexOf("@")));
}

/** Raw source values earn their own witnesses; status normalization, stripped IDs and absent options earn none. */
export function cliResponseMemberValues(source: CliRun, candidate: ObjectValue, inventory: CurrentInventory): CoreValueWitness[] {
  assert.equal(inventory.sourceRevision, review.sourceRevision, "CLI DTO source revision changed");
  assert.equal(inventory.sourceTree, review.sourceTree, "CLI DTO serialization root source tree changed");
  const file = review.roots.find(item => item.path === "codex-rs/protocol/src/models.rs"); assert.ok(file);
  const inventoried = inventory.files.find(item => item.path === file.path);
  assert.equal(inventoried?.sha256, file.sha256, "CLI DTO serialization source file changed");
  const units = new Map(inventory.units.map(unit => [unit.id, unit])), values = new Map<string, CoreValueWitness>();
  const bound = (pin: Pin): SourceUnit => {
    const unit = units.get(pin.id);
    assert.ok(unit && unit.sha256 === pin.sha256 && unit.start === pin.start && unit.end === pin.end, `CLI DTO reviewed declaration changed: ${pin.id}`);
    assert.equal(unit.file, file.path);
    assert.equal(unit.kind, pin.id.slice(pin.id.indexOf("#") + 1, pin.id.lastIndexOf("@")), "CLI DTO declaration kind changed");
    return unit;
  };
  // The immutable full source tree binds the reviewed Prompt -> ResponsesApiRequest.input:Vec<ResponseItem>
  // -> EncodedJsonBody::encode route. No ResponseInputItem or similarly named content type inherits this root.
  reviewed.forEach(bound);
  const observe = (pin: Pin, sourceValue: unknown, candidateValue: unknown, path: Path): boolean => {
    // JSON serialization equality deliberately includes object-key order and unmodified measured headers.
    if (sourceValue === undefined || JSON.stringify(sourceValue) !== JSON.stringify(candidateValue)) return false;
    const unit = bound(pin);
    if (!values.has(unit.id)) values.set(unit.id, { id: unit.id, sha256: unit.sha256, relation: "typed-source-shape-v1", path,
      valueSha256: sha256(JSON.stringify(sourceValue)) });
    return true;
  };
  const observation = source.observations.length - 1, input = source.observations[observation]?.input;
  const posted = candidate.posted;
  assert.ok(input && Array.isArray(posted) && Array.isArray(posted.at(-1)), "CLI DTO values require actual paired model inputs");
  const actual = posted.at(-1) as ObjectValue[];
  const selected = input.map((value, index) => ({ value, index })).filter(item => calls.has(String(item.value.type)));
  const targets = actual.filter(value => object(value) && calls.has(String(value.type)));
  assert.equal(targets.length, selected.length, "CLI DTO call sequence changed");
  for (const [index, { value, index: sourceIndex }] of selected.entries()) {
    const target = targets[index]!;
    assert.equal(target.type, value.type); assert.equal(target.call_id, value.call_id, "CLI DTO call ownership changed");
    const variant = review.variants.find(item => item.wireType === value.type)!;
    const path: Path = ["observations", observation, "input", sourceIndex];
    // Whole declaration observations require the whole raw item. In particular, an output's stripped `id`
    // cannot silently disappear, and matching normalized timing arrays cannot certify the raw payload body.
    if (!String(value.type).endsWith("_output") && JSON.stringify(value) === JSON.stringify(target)) {
      observe(review.root.owner, value, target, path); observe(variant.unit, value, target, path);
    }
    for (const field of variant.fields) {
      const memberPath = [...path, field.wireKey];
      if (!observe(field.unit, value[field.wireKey], target[field.wireKey], memberPath) || field.wireKey !== "output") continue;
      const projection = review.outputProjection, output = value.output;
      observe(projection.payload, output, target.output, memberPath);
      observe(projection.bodyField, output, target.output, memberPath);
      observe(projection.body, output, target.output, memberPath);
      const body = projection.variants.find(item => item.shape === (typeof output === "string" ? "string" : Array.isArray(output) ? "array" : "unrecognized"));
      if (body) { observe(body.unit, output, target.output, memberPath); observe(body.tuple, output, target.output, memberPath); }
    }
  }
  return [...values.values()].sort((a, b) => a.id.localeCompare(b.id));
}
