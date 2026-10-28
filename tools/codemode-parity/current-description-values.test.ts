import { expect, test } from "bun:test";
import { coreValueWitnesses } from "./current-core-values";
import type { CurrentInventory, SourceUnit } from "./current-inventory";
import { sha256 } from "./qualification";

function fixture() {
  const file = "codex-rs/code-mode-protocol/src/description.rs";
  const source = Buffer.from("pub struct ToolNamespaceDescription { pub name: String, pub description: String }\npub fn build_exec_tool_description() {}");
  const units: SourceUnit[] = [];
  const add = (kind: string, label: string, text: string) => {
    const start = source.indexOf(text), unit = { id: `${file}#${kind}@${start}`, file, kind, label, start, end: start + text.length, sha256: sha256(text), ownership: "ported" as const, line: 1 };
    units.push(unit); return unit;
  };
  const declaration = add("struct_item", "ToolNamespaceDescription", "pub struct ToolNamespaceDescription { pub name: String, pub description: String }");
  add("field_declaration", "name", "pub name: String"); add("field_declaration", "description", "pub description: String");
  const entered = add("function_item", "build_exec_tool_description", "pub fn build_exec_tool_description() {}");
  const inventory = { schema: 2, units, files: [{ path: file, sha256: sha256(source) }] } as CurrentInventory;
  const input = { operation: "describe", tools: [{ name: "read", namespace: "mcp__fixed__", namespace_description: " Namespace guidance. " }] };
  const observe = (description: string, request: any = input, called = true) => coreValueWitnesses(request, { description }, called ? [entered] : [], inventory, new Map([[file, source]]));
  return { declaration, units, input, observe };
}

test("a namespace declaration and each field need their own actual rendered heading and guidance", () => {
  const { units, observe } = fixture(), description = "## mcp__fixed__\nNamespace guidance.\n\n### read";
  const values = observe(description);
  expect(units.filter(unit => unit.kind !== "function_item").every(unit => values.some(value => value.id === unit.id && value.fragmentSha256))).toBe(true);
  expect(observe(description, undefined, false)).toEqual([]);
  expect(observe("## mcp__other__\nNamespace guidance.")).toEqual([]);
  expect(observe("## mcp__fixed__\nDifferent guidance.")).toEqual([]);
});

test("hidden or empty namespace guidance cannot claim a declaration from a coincidental output", () => {
  const { input, observe } = fixture(), description = "## mcp__fixed__\nNamespace guidance.";
  expect(observe(description, { ...input, options: { code_mode_only: false } })).toEqual([]);
  expect(observe(description, { ...input, tools: [{ ...input.tools[0], namespace_description: " " }] })).toEqual([]);
});

test("namespace map replacement is bound to the last supplied typed description", () => {
  const { input, observe } = fixture(), request = { ...input, tools: [...input.tools, { ...input.tools[0], namespace_description: "Replacement." }] };
  expect(observe("## mcp__fixed__\nNamespace guidance.", request)).toEqual([]);
  expect(observe("## mcp__fixed__\nReplacement.", request)).toHaveLength(3);
});
