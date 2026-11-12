import { expect, test } from "bun:test";
import { coreValueWitnesses } from "./current-core-values";
import { sha256 } from "./qualification";
import type { CurrentInventory, SourceUnit } from "./current-inventory";

function fixture(wait = false) {
  const file = wait ? "codex-rs/core/src/tools/code_mode/wait_handler.rs" : "codex-rs/code-mode-protocol/src/description.rs";
  const declarations = wait ? [
    ["struct_item", "ExecWaitArgs", "struct ExecWaitArgs { cell_id: String, yield_time_ms: u64, max_tokens: Option<usize>, terminate: bool }"],
    ["function_item", "parse_arguments", "fn parse_arguments() {}"],
  ] : [
    ["const_item", "MAX_JS_SAFE_INTEGER", "const MAX_JS_SAFE_INTEGER: u64 = (1_u64 << 53) - 1;"],
    ["const_item", "CODE_MODE_PRAGMA_PREFIX", 'pub const CODE_MODE_PRAGMA_PREFIX: &str = "// @exec:";'],
    ["struct_item", "CodeModeExecPragma", "struct CodeModeExecPragma { yield_time_ms: Option<u64>, max_output_tokens: Option<usize> }"],
    ["struct_item", "ParsedExecSource", "pub struct ParsedExecSource { pub code: String, pub yield_time_ms: Option<u64>, pub max_output_tokens: Option<usize> }"],
    ["function_item", "parse_exec_source", "pub fn parse_exec_source() {}"],
  ];
  const source = Buffer.from(declarations.map(row => row[2]).join("\n")), units: SourceUnit[] = [];
  const add = (kind: string, label: string, text: string, from = 0) => {
    const start = source.indexOf(text, from), unit = { id: `${file}#${kind}@${start}`, file, kind, label, start, end: start + Buffer.byteLength(text), line: 1, sha256: sha256(text), ownership: "ported" as const };
    units.push(unit); return unit;
  };
  for (const [kind, label, text] of declarations) {
    const owner = add(kind!, label!, text!);
    if (kind === "struct_item") for (const field of text!.slice(text!.indexOf("{") + 1, text!.lastIndexOf("}")).split(",")) {
      const text = field.trim(), label = text.match(/(?:pub\s+)?(\w+)\s*:/)![1]!;
      add("field_declaration", label, text, owner.start);
    }
  }
  const inventory = { schema: 2, units, files: [{ path: file, sha256: sha256(source) }] } as CurrentInventory;
  const parser = units.find(unit => unit.kind === "function_item")!;
  const fields = (owner: string) => { const type = units.find(unit => unit.kind === "struct_item" && unit.label === owner)!; return units.filter(unit => unit.kind === "field_declaration" && type.start <= unit.start && unit.end <= type.end); };
  const observe = (input: any, result: any, entered = true) => coreValueWitnesses(input, result, entered ? [{ id: parser.id, sha256: parser.sha256 }] : [], inventory, new Map([[file, source]]));
  return { inventory, fields, observe };
}

test("parsed members require an actual source parser entry and an equal returned value", () => {
  const { observe, fields } = fixture(), input = { operation: "exec_arguments", source: '// @exec: {"yield_time_ms":23,"max_output_tokens":17}\ntext(1);' };
  const result = { source: "text(1);", yield_time_ms: "23", max_output_tokens: "17" };
  expect(observe(input, result, false)).toEqual([]);
  const values = observe(input, result), yieldField = fields("CodeModeExecPragma").find(unit => unit.label === "yield_time_ms")!;
  expect(values.some(value => value.id === yieldField.id && value.relation === "parsed-source-value-v1")).toBe(true);
  expect(observe(input, { ...result, yield_time_ms: "24" }).some(value => value.id === yieldField.id)).toBe(false);
});

test("resolved defaults cannot claim an absent optional parser field", () => {
  const { observe, fields } = fixture(), yieldField = fields("ParsedExecSource").find(unit => unit.label === "yield_time_ms")!;
  const values = observe({ operation: "exec_arguments", source: "text(1);" }, { source: "text(1);", yield_time_ms: "30000", max_output_tokens: "10000" });
  expect(values.some(value => value.id === yieldField.id)).toBe(false);
  expect(values.some(value => value.id === fields("ParsedExecSource").find(unit => unit.label === "code")!.id)).toBe(true);
});

test("wrong stripped code cannot claim the parsed code or pragma prefix", () => {
  const { observe, fields, inventory } = fixture(), code = fields("ParsedExecSource").find(unit => unit.label === "code")!;
  const prefix = inventory.units.find(unit => unit.label === "CODE_MODE_PRAGMA_PREFIX")!;
  const values = observe({ operation: "exec_arguments", source: "// @exec: {}\ntext(1);" }, { source: "substituted", yield_time_ms: "30000", max_output_tokens: "10000" });
  expect(values.some(value => value.id === code.id || value.id === prefix.id)).toBe(false);
});

test("the safe integer constant needs its exact accepted or rejected boundary output", () => {
  const { observe, inventory } = fixture(), maximum = inventory.units.find(unit => unit.label === "MAX_JS_SAFE_INTEGER")!;
  const accepted = { operation: "exec_arguments", source: '// @exec: {"yield_time_ms":9007199254740991}\ntext(1);' };
  expect(observe(accepted, { source: "text(1);", yield_time_ms: "9007199254740991" }).some(value => value.id === maximum.id)).toBe(true);
  const rejected = { operation: "exec_arguments", source: '// @exec: {"yield_time_ms":9007199254740992}\ntext(1);' };
  expect(observe(rejected, { error: "exec pragma field `yield_time_ms` must be a non-negative safe integer" }).some(value => value.id === maximum.id)).toBe(true);
  expect(observe(rejected, { error: "unrelated failure" }).some(value => value.id === maximum.id)).toBe(false);
});

test("wait parser observations retain each supplied field and reject a wrong budget", () => {
  const { observe, fields } = fixture(true), input = { operation: "wait_arguments", arguments: '{"cell_id":"a","yield_time_ms":37,"max_tokens":19,"terminate":false}' };
  const result = { source: null, cell_id: "a", yield_time_ms: "37", max_output_tokens: "19", terminate: false };
  const values = observe(input, result);
  expect(fields("ExecWaitArgs").every(field => values.some(value => value.id === field.id))).toBe(true);
  const budget = fields("ExecWaitArgs").find(unit => unit.label === "max_tokens")!;
  expect(observe(input, { ...result, max_output_tokens: "20" }).some(value => value.id === budget.id)).toBe(false);
});
