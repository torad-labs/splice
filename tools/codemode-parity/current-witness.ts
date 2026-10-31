import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { sha256, type Artifact } from "./qualification";
import type { CurrentInventory, SourceUnit } from "./current-inventory";
import type { ClientCaseWitness } from "./current-client-profile";
import type { ApiCaseWitness } from "./current-core-api-witness";
import { sourceCliCaseArtifacts, type CliSourceCaseWitness } from "./current-cli-source-profile";
import { coreValueWitnesses, originalCoreSources, type CoreValueWitness } from "./current-core-values";

export type FunctionWitness = { id: string; sha256: string };
export type NamedWitness = { caseId: string; inputSha256: string; productionEntrypoint: string; referenceEntrypoint: string };
export type CoreCaseWitness = {
  producer?: "core-source";
  caseId: string; inputSha256: string; productionEntrypoint: string; referenceFunctions: FunctionWitness[];
  referenceValues?: CoreValueWitness[];
  input: Artifact; reference: Artifact; candidate: Artifact; sourceManifest: Artifact;
};
export type ExecutedCaseWitness = CoreCaseWitness | ClientCaseWitness | ApiCaseWitness | CliSourceCaseWitness;
export function caseWitnessArtifacts(witness: ExecutedCaseWitness): Artifact[] {
  return witness.producer === "core-cli-llvm" ? sourceCliCaseArtifacts(witness)
    : witness.producer === "rust-client-llvm" ? [witness.input, witness.reference, witness.candidate, witness.build, witness.profile, ...Object.values(witness.peerTrace)]
    : witness.producer === "core-api-llvm" ? [witness.input, witness.reference, witness.candidate, witness.build, witness.profile]
    : [witness.input, witness.reference, witness.candidate, witness.sourceManifest];
}
export type InstrumentedSource = {
  sourceFile: string; sourceFileSha256: string; sourceStart: number; original: Artifact; instrumented: Artifact;
  insertions: Array<{ offset: number; text: string; unitId: string; unitSha256: string }>;
};
/** Preserve JSON numeric lexemes before JavaScript can round u64 or collapse float/exponent visitors. */
export function parseLosslessJson(text: string): any {
  return JSON.parse(text, (_key, value, context?: { source?: string }) => typeof value === "number" && context?.source
    ? (JSON as typeof JSON & { rawJSON(source: string): unknown }).rawJSON(context.source) : value);
}
export function assertObservableJsonKeyOrder(actual: any, expected: any): void {
  if (actual === null || expected === null || typeof actual !== "object" || typeof expected !== "object") return;
  if ((JSON as typeof JSON & { isRawJSON(value: unknown): boolean }).isRawJSON(expected)) return;
  if (Array.isArray(expected)) { for (let index = 0; index < expected.length; index++) assertObservableJsonKeyOrder(actual[index], expected[index]); return; }
  assert.deepEqual(Object.keys(actual), Object.keys(expected), "JavaScript-observable JSON object key order differs");
  for (const key of Object.keys(expected)) assertObservableJsonKeyOrder(actual[key], expected[key]);
}
export function unitScopeSha256(unit: SourceUnit, inventory: CurrentInventory): string {
  const file = inventory.files.find(item => item.path === unit.file);
  return sha256(JSON.stringify({ file: unit.file, kind: unit.kind, label: unit.label, ownership: unit.ownership,
    requiredBy: unit.requiredBy ?? [], discovery: file?.discovery, fileSha256: file?.sha256, foreignScope: unit.foreignScope, representation: unit.representation, nativeScope: unit.nativeScope }));
}
export function coreCaseIdentity(input: Record<string, any>): { caseId: string; inputSha256: string; productionEntrypoint: string } {
  const { id: _id, ...body } = input, inputSha256 = sha256(JSON.stringify(body));
  return { caseId: `core/${body.operation}/${inputSha256.slice(0, 20)}`, inputSha256,
    productionEntrypoint: ["exec_arguments", "wait_arguments", "tool_search_arguments"].includes(body.operation)
      ? "splice.provider.codex.diagnostics.CodeModeArgumentsProbe.evaluate"
      : body.operation === "catalog_wait" ? "splice.provider.codex.diagnostics.CodeModeCatalogProbe.evaluate"
      : body.operation === "model_metadata" ? "splice.models.discovery.ModelMetadataProbe.evaluate"
      : "splice.upstream.codemode.diagnostics.CodeModeCoreProbe.evaluate" };
}
/** Insert only a function-entry counter. Original Git bytes and every insertion are retained and checked. */
export function instrumentRust(source: Uint8Array, file: string, inventory: CurrentInventory, sourceStart = 0): {
  bytes: Buffer; insertions: InstrumentedSource["insertions"];
} {
  const bytes = Buffer.from(source), insertions: InstrumentedSource["insertions"] = [];
  for (const unit of inventory.units.filter(item => item.file === file && item.kind === "function_item" && item.start >= sourceStart && item.end <= sourceStart + bytes.length)) {
    const text = bytes.subarray(unit.start - sourceStart, unit.end - sourceStart).toString();
    assert.equal(sha256(text), unit.sha256, `instrumentation source unit changed: ${unit.id}`);
    // Const functions cannot emit run-time counters. They stay unlinked until a separate witness exists.
    if (/\bconst\s+(?:unsafe\s+)?fn\b/.test(text.slice(0, text.indexOf("{")))) continue;
    const body = text.indexOf("{"); assert.ok(body >= 0, `function body unavailable: ${unit.id}`);
    // The selected source has no const-generic block in a signature. Fail closed if that stops being true.
    assert.ok(!/[<{][^>]*\{/.test(text.slice(0, body)), `ambiguous Rust signature: ${unit.id}`);
    const offset = unit.start - sourceStart + Buffer.byteLength(text.slice(0, body + 1));
    insertions.push({ offset, text: `crate::source_witness::enter(${JSON.stringify(unit.id)},${JSON.stringify(unit.sha256)});`, unitId: unit.id, unitSha256: unit.sha256 });
  }
  let result = bytes;
  for (const insertion of [...insertions].sort((a, b) => b.offset - a.offset)) result = Buffer.concat([result.subarray(0, insertion.offset), Buffer.from(insertion.text), result.subarray(insertion.offset)]);
  assert.ok(removeWitnessInsertions(result, insertions).equals(bytes), "source instrumentation changed bytes beyond the declared entry counters");
  return { bytes: result, insertions };
}
export function removeWitnessInsertions(bytes: Uint8Array, insertions: InstrumentedSource["insertions"]): Buffer {
  const source = Buffer.from(bytes), pieces: Buffer[] = []; let cursor = 0, extra = 0;
  for (const insertion of [...insertions].sort((a, b) => a.offset - b.offset)) {
    const at = insertion.offset + extra, text = Buffer.from(insertion.text);
    assert.ok(source.subarray(at, at + text.length).equals(text), `instrumentation counter changed: ${insertion.unitId}`);
    pieces.push(source.subarray(cursor, at)); cursor = at + text.length; extra += text.length;
  }
  pieces.push(source.subarray(cursor)); return Buffer.concat(pieces);
}
export const sourceWitnessRust = `use std::cell::RefCell;
thread_local! { static SEEN: RefCell<std::collections::BTreeMap<&'static str,&'static str>> = RefCell::new(std::collections::BTreeMap::new()); }
pub fn enter(id:&'static str,hash:&'static str) { SEEN.with(|seen| { seen.borrow_mut().insert(id,hash); }); }
pub fn take() -> Vec<serde_json::Value> { SEEN.with(|seen| std::mem::take(&mut *seen.borrow_mut()).into_iter().map(|(id,hash)|serde_json::json!({"id":id,"sha256":hash})).collect()) }
`;

/** Reconstruct witnesses from raw executions; copying a named-case label into a receipt cannot satisfy this. */
export async function executedCaseWitnessFindings(cases: CoreCaseWitness[], inventory: CurrentInventory, runId: string): Promise<string[]> {
  const findings: string[] = [], sourceUnits = new Map(inventory.units.map(unit => [unit.id, unit]));
  const cache = new Map<string, Promise<any>>();
  const json = (proof: Artifact) => {
    if (!cache.has(proof.path)) cache.set(proof.path, readFile(proof.path).then(bytes => {
      assert.equal(sha256(bytes), proof.sha256); assert.equal(bytes.length, proof.bytes); return JSON.parse(bytes.toString());
    }));
    return cache.get(proof.path)!;
  };
  const lines = new Map<string, Promise<Map<string, any>>>();
  const rows = (proof: Artifact, wrapped: boolean) => {
    const key = `${proof.path}/${wrapped}`;
    if (!lines.has(key)) lines.set(key, (async () => {
      const bytes = await readFile(proof.path); assert.equal(sha256(bytes), proof.sha256); assert.equal(bytes.length, proof.bytes);
      const result = wrapped ? JSON.parse(bytes.toString()) : undefined;
      if (wrapped) { assert.equal(result.exitCode, 0); assert.ok(Number.isSafeInteger(result.pid) && result.pid > 0); }
      const text = wrapped ? result.stdout : bytes.toString(), parsed = text.trimEnd().split("\n").map((line: string) => parseLosslessJson(line));
      const mapped = new Map<string, any>();
      for (const row of parsed) { assert.ok(typeof row.id === "string" && !mapped.has(row.id), "duplicate named case in raw execution"); mapped.set(row.id, row); }
      return mapped;
    })());
    return lines.get(key)!;
  };
  const checkedManifests = new Set<string>(), seen = new Set<string>();
  for (const witness of cases ?? []) try {
    assert.ok(!seen.has(witness.caseId), "duplicate executed named case"); seen.add(witness.caseId);
    const [inputs, reference, candidate, manifest] = await Promise.all([rows(witness.input, false), rows(witness.reference, true), rows(witness.candidate, true), json(witness.sourceManifest)]);
    if (!checkedManifests.has(witness.sourceManifest.path)) {
      assert.equal(manifest.sourceRevision, inventory.sourceRevision); assert.equal(manifest.instrumentation, "function-entry-insertions-v1");
      for (const entry of manifest.sources as InstrumentedSource[]) {
        const original = await readFile(entry.original.path), instrumented = await readFile(entry.instrumented.path);
        assert.equal(sha256(original), entry.original.sha256); assert.equal(sha256(instrumented), entry.instrumented.sha256);
        assert.equal(entry.sourceFileSha256, inventory.files.find(file => file.path === entry.sourceFile)?.sha256);
        assert.ok(entry.sourceStart === 0 && sha256(original) === entry.sourceFileSha256 || inventory.units.some(unit => unit.file === entry.sourceFile && unit.start === entry.sourceStart && unit.end === entry.sourceStart + original.length && unit.sha256 === sha256(original)), "instrumented original is not an exact pinned Git file/declaration");
        assert.ok(removeWitnessInsertions(instrumented, entry.insertions).equals(original), "source algorithm bytes changed beyond declared counters");
        for (const insertion of entry.insertions) {
          const unit = sourceUnits.get(insertion.unitId); assert.ok(unit && unit.kind === "function_item" && unit.sha256 === insertion.unitSha256);
          assert.equal(insertion.text, `crate::source_witness::enter(${JSON.stringify(unit.id)},${JSON.stringify(unit.sha256)});`);
        }
      }
      checkedManifests.add(witness.sourceManifest.path);
    }
    const input = inputs.get(witness.caseId), expected = reference.get(witness.caseId), actual = candidate.get(witness.caseId);
    assert.ok(input && expected && actual, "named case was not actually run on both reference and production");
    const identity = coreCaseIdentity(input);
    assert.equal(identity.caseId, witness.caseId); assert.equal(identity.inputSha256, witness.inputSha256); assert.equal(identity.productionEntrypoint, witness.productionEntrypoint);
    assert.equal(actual.nonce, runId); assert.deepEqual(actual.result, expected.result);
    if (input.operation === "tool_output") assertObservableJsonKeyOrder(actual.result.value, expected.result.value);
    const command = await json(witness.candidate);
    assert.ok(command.argv.includes("--current-core-fixture") && command.argv.at(-1) === runId, "named source case did not invoke the production fixture entrypoint");
    assert.deepEqual(witness.referenceFunctions, expected.source_witnesses);
    assert.deepEqual(witness.referenceValues ?? [], coreValueWitnesses(input, expected.result, expected.source_witnesses, inventory, await originalCoreSources(manifest, inventory)), "claimed source member was not observed in the actual typed/rendered result");
    for (const function_ of witness.referenceFunctions) {
      const unit = sourceUnits.get(function_.id); assert.ok(unit && unit.sha256 === function_.sha256 && unit.kind === "function_item", "unknown/mismatched source function counter");
      assert.ok(manifest.sources.some((entry: InstrumentedSource) => entry.insertions.some(item => item.unitId === function_.id && item.unitSha256 === function_.sha256)), "unlinked source function counter");
    }
  } catch (error) { findings.push(`${witness?.caseId ?? "unknown named case"}: invalid executed source witness: ${error instanceof Error ? error.message : String(error)}`); }
  return findings;
}
