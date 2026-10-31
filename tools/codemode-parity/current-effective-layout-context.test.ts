import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { effectiveLayoutContextReason } from "./current-effective-layout-context";
import type { CurrentInventory, SourceUnit } from "./current-inventory";

const original: CurrentInventory = JSON.parse(readFileSync(new URL("./current-inventory.json", import.meta.url), "utf8"));
const base = "codex-rs/core/src/";
const invocationId = `${base}tools/context.rs#struct_item@3895`;
const fieldId = `${base}tools/context.rs#field_declaration@3927`;
const ownerIds = ["enum_item@2154", "struct_item@10227", "struct_item@12577", "struct_item@13534", "struct_item@3895", "struct_item@5900"]
  .map(suffix => `${base}tools/context.rs#${suffix}`);
const pinnedFiles = ["lib.rs", "tools/mod.rs", "tools/context.rs", "tools/router.rs", "tools/lifecycle.rs", "tools/registry.rs", "session/step_context.rs"];
const behaviorFiles = ["tools/context.rs", "tools/router.rs", "tools/lifecycle.rs", "tools/registry.rs"];
const preserved = original.units.filter(unit => unit.kind === "function_item" && unit.foreignScope?.state === "required" &&
  behaviorFiles.includes(unit.file.slice(base.length)));

function fixture(): CurrentInventory { return structuredClone(original); }
function unit(inventory: CurrentInventory, id = invocationId): SourceUnit {
  const result = inventory.units.find(unit => unit.id === id);
  if (!result) throw new Error(`fixture unit is missing: ${id}`);
  return result;
}

describe("exact effectively private Rust layout context", () => {
  test("only the 31 reviewed nonwire members receive representation context", () => {
    const accepted = original.units.filter(unit => effectiveLayoutContextReason(unit, original) !== undefined);
    expect(accepted).toHaveLength(31);
    expect(new Set(accepted.map(unit => unit.kind))).toEqual(new Set(["struct_item", "enum_item", "enum_variant", "field_declaration"]));
    for (const found of accepted) {
      expect(effectiveLayoutContextReason(found, original)).toContain("receives no execution PASS");
      expect(effectiveLayoutContextReason(found, original)).toContain("All 47 exact selected executable declarations");
    }
  });

  test("all 47 selected bodies remain independent mandatory behavior", () => {
    expect(preserved).toHaveLength(47);
    for (const behavior of preserved) expect(effectiveLayoutContextReason(behavior, original)).toBeUndefined();
  });

  test("removing any one of the 47 bodies refuses layout context", () => {
    for (const behavior of preserved) {
      const inventory = { ...original, units: original.units.filter(found => found.id !== behavior.id) };
      expect(effectiveLayoutContextReason(unit(inventory), inventory)).toBeUndefined();
    }
  });

  test("mutating any selected body hash or excluding its selected scope refuses context", () => {
    for (const behavior of preserved) {
      const inventory = { ...original, units: original.units.map(found => found.id === behavior.id ? { ...found, sha256: "0".repeat(64) } : found) };
      expect(effectiveLayoutContextReason(unit(inventory), inventory)).toBeUndefined();
    }
    const inventory = fixture();
    unit(inventory, preserved[0]!.id).foreignScope!.state = "foreign-context";
    expect(effectiveLayoutContextReason(unit(inventory), inventory)).toBeUndefined();
  });

  test("parent visibility, reexport, signature and conversion evidence stays hash bound", () => {
    for (const file of pinnedFiles) {
      const inventory = fixture();
      inventory.files.find(found => found.path === base + file)!.sha256 = "0".repeat(64);
      expect(effectiveLayoutContextReason(unit(inventory), inventory)).toBeUndefined();
      const blob = fixture();
      blob.files.find(found => found.path === base + file)!.gitBlob = "0".repeat(40);
      expect(effectiveLayoutContextReason(unit(blob), blob)).toBeUndefined();
    }
  });

  test("all six concrete owners remain present with their exact identities", () => {
    for (const id of ownerIds) {
      const missing = fixture();
      missing.units = missing.units.filter(found => found.id !== id);
      expect(effectiveLayoutContextReason(unit(missing, fieldId), missing)).toBeUndefined();
      const changed = fixture();
      unit(changed, id).sha256 = "0".repeat(64);
      expect(effectiveLayoutContextReason(unit(changed, fieldId), changed)).toBeUndefined();
    }
  });

  test("unknown declarations and same-named serialized DTO fields never inherit layout context", () => {
    const wire = original.units.filter(found => found.file === base + "tools/context.rs" &&
      found.start >= 15946 && found.end <= 16477);
    expect(wire.some(found => found.label === "UnifiedExecCodeModeResult")).toBe(true);
    expect(wire.some(found => found.label === "chunk_id")).toBe(true);
    for (const found of wire) expect(effectiveLayoutContextReason(found, original)).toBeUndefined();
    const unknown = { ...unit(original, fieldId), id: `${base}tools/context.rs#field_declaration@99999`, start: 99999 };
    expect(effectiveLayoutContextReason(unknown, original)).toBeUndefined();
  });

  test("a function cannot masquerade as an approved layout member", () => {
    const found = unit(original, fieldId);
    expect(effectiveLayoutContextReason({ ...found, kind: "function_item" }, original)).toBeUndefined();
    expect(effectiveLayoutContextReason({ ...found, kind: "function_signature_item" }, original)).toBeUndefined();
    const inventory = fixture();
    const added = { ...preserved[0]!, id: `${base}tools/context.rs#function_item@4000`, start: 4000, end: 4050 };
    added.foreignScope = { ...added.foreignScope!, state: "foreign-context" };
    inventory.units.push(added);
    expect(effectiveLayoutContextReason(unit(inventory), inventory)).toBeUndefined();
  });

  test("new selected behavior is not silently added to the reviewed set", () => {
    const inventory = fixture();
    inventory.units.push({ ...preserved[0]!, id: `${base}tools/context.rs#function_item@99999`, start: 99999, end: 100100 });
    expect(effectiveLayoutContextReason(unit(inventory), inventory)).toBeUndefined();
  });

  test("current revision, full source tree, exact census membership and unique identities remain required", () => {
    for (const field of ["sourceRevision", "sourceTree"] as const) {
      const inventory = fixture();
      inventory[field] = "0".repeat(40);
      expect(effectiveLayoutContextReason(unit(inventory), inventory)).toBeUndefined();
    }
    const inventory = fixture();
    const candidate = unit(inventory, fieldId);
    inventory.units = inventory.units.filter(found => found.id !== fieldId);
    expect(effectiveLayoutContextReason(candidate, inventory)).toBeUndefined();
    const duplicate = fixture();
    duplicate.units.push({ ...unit(duplicate, fieldId) });
    expect(effectiveLayoutContextReason(unit(duplicate), duplicate)).toBeUndefined();
    const duplicateFile = fixture();
    duplicateFile.files.push({ ...duplicateFile.files.find(found => found.path === base + "lib.rs")! });
    expect(effectiveLayoutContextReason(unit(duplicateFile), duplicateFile)).toBeUndefined();
  });

  test("mutated layout ranges, source identities and labels require a new review", () => {
    const found = unit(original, fieldId);
    for (const changed of [
      { ...found, sha256: "0".repeat(64) }, { ...found, start: found.start + 1 }, { ...found, end: found.end + 1 },
      { ...found, label: "serialized_value" }, { ...found, file: base + "tools/wire.rs" },
      { ...found, ownership: "native-host" as const },
    ]) expect(effectiveLayoutContextReason(changed, original)).toBeUndefined();
  });
});
