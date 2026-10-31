import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { cliResponseMemberValues, reviewedCliResponseMember } from "./current-cli-response-values";
import type { CurrentInventory } from "./current-inventory";
import type { CliRun, ObjectValue } from "./current-cli/types";
import { sha256 } from "./qualification";

const inventory: CurrentInventory = JSON.parse(readFileSync(resolve(import.meta.dir, "current-inventory.json"), "utf8"));
const id = (kind: string, offset: number) => `codex-rs/protocol/src/models.rs#${kind}@${offset}`;
const customInput = id("field_declaration", 44249), customOutput = id("field_declaration", 45059);
function paired(items: ObjectValue[]) {
  const source = { observations: [{ request: 1, input: items }] } as CliRun;
  const candidate: ObjectValue = { posted: [structuredClone(items)] };
  return { source, candidate, rows: (candidate.posted as ObjectValue[][])[0]! };
}

test("CLI DTO observations bind exact present raw fields and keep the two response item families distinct", () => {
  const { source, candidate } = paired([
    { type: "custom_tool_call", call_id: "exec-1", name: "exec", input: 'text("literal");' },
    { type: "custom_tool_call_output", id: "volatile-output-id", call_id: "exec-1", name: "exec", output: "actual notification" },
    { type: "function_call", call_id: "wait-1", name: "wait", arguments: '{"cell_id":"1"}' },
    { type: "function_call_output", call_id: "wait-1", output: "actual wait output" },
    { type: "message", role: "assistant", content: [{ type: "output_text", text: "filtered message" }] },
  ]);
  const values = cliResponseMemberValues(source, candidate, inventory), mapped = new Map(values.map(value => [value.id, value]));
  expect(mapped.get(customInput)?.path).toEqual(["observations", 0, "input", 0, "input"]);
  expect(mapped.get(customInput)?.valueSha256).toBe(sha256(JSON.stringify('text("literal");')));
  expect(mapped.get(customOutput)?.path).toEqual(["observations", 0, "input", 1, "output"]);
  expect(mapped.has(id("enum_item", 37444))).toBe(true);
  expect(mapped.has(id("enum_variant", 43666))).toBe(true);
  expect(mapped.has(id("enum_variant", 44657))).toBe(false); // stripped output IDs never grant a whole item.
  expect(values.every(value => value.id.startsWith("codex-rs/protocol/src/models.rs#"))).toBe(true);
  expect(values.some(value => inventory.units.find(unit => unit.id === value.id)?.label === "success")).toBe(false);
  expect(mapped.has(id("field_declaration", 44000))).toBe(false); // no same-name or nearest-span guessing.
});

test("normalized wall time cannot certify a raw output field or its handwritten payload projection", () => {
  const { source, candidate, rows } = paired([{ type: "custom_tool_call_output", call_id: "exec-1", output: [
    { type: "input_text", text: "Script completed\nWall time 0.1 seconds\nOutput:\n" },
    { type: "input_text", text: "same actual result" },
  ] }]);
  (rows[0]!.output as ObjectValue[])[0]!.text = "Script completed\nWall time 0.2 seconds\nOutput:\n";
  const mapped = new Set(cliResponseMemberValues(source, candidate, inventory).map(value => value.id));
  for (const missing of [customOutput, id("struct_item", 85218), id("field_declaration", 85261), id("enum_item", 85423), id("enum_variant", 85479)]) expect(mapped.has(missing)).toBe(false);
  expect(mapped.has(id("field_declaration", 44815))).toBe(true);
});

test("raw object-key order and absent optional members remain material to CLI value credit", () => {
  const { source, candidate, rows } = paired([{ type: "custom_tool_call_output", call_id: "exec-1", output: [
    { type: "input_text", text: "actual output" },
  ] }]);
  rows[0]!.output = [{ text: "actual output", type: "input_text" }];
  const mapped = new Set(cliResponseMemberValues(source, candidate, inventory).map(value => value.id));
  expect(mapped.has(customOutput)).toBe(false);
  expect(mapped.has(id("field_declaration", 44931))).toBe(false);
  expect(mapped.has(id("field_declaration", 45193))).toBe(false);
});

test("a reviewed source declaration, its full file and the immutable typed root cannot drift", () => {
  const { source, candidate } = paired([{ type: "custom_tool_call", call_id: "exec-1", name: "exec", input: "text(1)" }]);
  const changed = structuredClone(inventory), field = changed.units.find(unit => unit.id === customInput)!;
  field.sha256 = "0".repeat(64);
  expect(() => cliResponseMemberValues(source, candidate, changed)).toThrow("reviewed declaration changed");
  const relabeled = structuredClone(inventory); relabeled.units.find(unit => unit.id === customInput)!.kind = "function_item";
  expect(() => cliResponseMemberValues(source, candidate, relabeled)).toThrow("declaration kind changed");
  const changedFile = structuredClone(inventory); changedFile.files.find(file => file.path === "codex-rs/protocol/src/models.rs")!.sha256 = "0".repeat(64);
  expect(() => cliResponseMemberValues(source, candidate, changedFile)).toThrow("source file changed");
  expect(() => cliResponseMemberValues(source, candidate, { ...inventory, sourceTree: "0".repeat(40) })).toThrow("source tree changed");
});

test("a raw typed member cannot inherit a sibling call or an unrelated frontend envelope", () => {
  const { source, candidate, rows } = paired([{ type: "custom_tool_call", call_id: "exec-1", name: "exec", input: "text(1)" }]);
  rows[0]!.call_id = "another-call";
  expect(() => cliResponseMemberValues(source, candidate, inventory)).toThrow("call ownership changed");
  rows[0]!.call_id = "exec-1"; rows[0]!.type = "function_call";
  expect(() => cliResponseMemberValues(source, candidate, inventory)).toThrow();
});

test("gateway value plans cannot waive unrelated fields, functions or changed source bindings", () => {
  const unit = inventory.units.find(unit => unit.id === customInput)!;
  expect(reviewedCliResponseMember(unit, inventory)).toBe(true);
  expect(reviewedCliResponseMember({ ...unit, kind: "function_item" }, inventory)).toBe(false);
  expect(reviewedCliResponseMember({ ...unit, sha256: "0".repeat(64) }, inventory)).toBe(false);
  expect(reviewedCliResponseMember({ ...unit, end: unit.end + 1 }, inventory)).toBe(false);
  const other = inventory.units.find(unit => unit.kind === "field_declaration" && !unit.file.endsWith("/models.rs"))!;
  expect(reviewedCliResponseMember(other, inventory)).toBe(false);
});
