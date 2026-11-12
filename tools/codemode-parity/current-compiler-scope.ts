import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { sha256 } from "./qualification";
import { loadCompilerBindings } from "./current-compiler-bindings";
import type { CompilerBindingIndex, CompilerDeclaration, CompilerImmutableFile, CompilerNamespace, CompilerRange, CompilerReference } from "./current-compiler-types";

export type CompilerScopeNode = { id: string; file: string; kind: string; start: number; end: number; sha256: string };
export type CompilerScopeSource = { sourceRevision: string; sourceTree: string; sourceCargoLockSha256: string };
export type CompilerScopeIdentity = { algorithm: "rustc-exact-source-scope-v1"; proofSha256: string; semanticDigest: string };
export type CompilerScopeQuery = CompilerRange & { owner: string; namespace: CompilerNamespace; kind: CompilerReference["kind"] };
type DeclarationProof = { compilerOwner: string; kind: string; range?: CompilerRange; sourceSha256?: string };
type DeclarationMapping = { status: "mapped"; node: CompilerScopeNode; declaration: DeclarationProof } |
  { status: "unmapped" | "ambiguous" | "unstaged"; reason: string; declaration?: DeclarationProof };
export type CompilerScopeEvidence = {
  proofSha256: string; compilerOwner: string; compilerTarget: string; declaration: DeclarationProof;
  reference: CompilerRange; namespace: CompilerNamespace; kind: CompilerReference["kind"]; resolution: string;
};
export type CompilerScopeResolution = {
  status: "bound"; owner: CompilerScopeNode; target: CompilerScopeNode; evidence: CompilerScopeEvidence[]; behavioralCoverage: false;
} | { status: "unresolved" | "ambiguous" | "unstaged"; reason: string; declarations: DeclarationProof[]; behavioralCoverage: false };
export function unstagedCompilerFiles(traces: readonly { query: CompilerScopeQuery; resolution: CompilerScopeResolution }[], files: ReadonlyMap<string, CompilerImmutableFile>): Array<{ file: string; sha256: string; requiredBy: string[] }> {
  const targets = new Map<string, { file: string; sha256: string; requiredBy: Set<string> }>();
  for (const trace of traces) if (trace.resolution.status === "unstaged") for (const declaration of trace.resolution.declarations) {
    if (!declaration.range) continue;
    const file = declaration.range.file, source = files.get(file);
    assert.ok(source && sha256(source.source) === source.sha256 && source.sha256 === declaration.sourceSha256, "exact compiler target is absent from immutable Git source");
    const target = targets.get(file) ?? { file, sha256: source.sha256, requiredBy: new Set<string>() };
    target.requiredBy.add(trace.query.file); targets.set(file, target);
  }
  return [...targets.values()].map(target => ({ ...target, requiredBy: [...target.requiredBy].sort() })).sort((a,b)=>a.file.localeCompare(b.file));
}
type ReferenceMapping = { reference: CompilerReference; owner: DeclarationMapping; target?: DeclarationMapping };
export type CompilerScope = {
  algorithm: "rustc-exact-source-scope-v1"; proofSha256: string; semanticDigest: string; behavioralCoverage: false;
  issues: readonly { owner?: { id: string; sha256: string }; reference?: CompilerRange; namespace: CompilerNamespace;
    kind: CompilerReference["kind"]; resolution: string; reason: string; declaration?: DeclarationProof }[];
  resolve(query: CompilerScopeQuery): CompilerScopeResolution;
  sites(owner: string): readonly CompilerScopeQuery[];
};

/** The explicitly supplied manifest is the reviewed proof authority. Reproduction pins its exact bytes. */
export async function loadCompilerScopeIndex(proofPath: string, source: CompilerScopeSource,
  files: ReadonlyMap<string, CompilerImmutableFile>, expectedProofSha256?: string): Promise<CompilerBindingIndex> {
  const bytes = await readFile(proofPath), proofSha256 = sha256(bytes), proof = JSON.parse(bytes.toString());
  if (expectedProofSha256 !== undefined) assert.equal(proofSha256, expectedProofSha256, "explicit compiler binding proof changed");
  return loadCompilerBindings(proofPath, { revision: source.sourceRevision, tree: source.sourceTree,
    originalCargoLockSha256: source.sourceCargoLockSha256, buildCargoLockSha256: proof.source?.buildCargoLockSha256,
    compilerSha256: proof.compiler?.sha256, compilerVersion: proof.compiler?.version, proofSha256 }, files);
}

/** Exact debug forms observed from the pinned rustc 1.99.1 driver; unknown payloads never inherit a mapping. */
function compatibleKinds(kind: string): readonly string[] {
  switch (kind) {
    case "Fn": case "AssocFn": return ["function_item", "function_signature_item"];
    case "Struct": case "Ctor(Struct, Fn)": case "Ctor(Struct, Const)": return ["struct_item"];
    case "Enum": return ["enum_item"];
    case "Variant": case "Ctor(Variant, Fn)": case "Ctor(Variant, Const)": return ["enum_variant"];
    case "Field": case "FieldDef": return ["field_declaration"];
    case "Trait": return ["trait_item"];
    case "TyAlias": case "AssocTy": return ["type_item"];
    case "Const": case "AssocConst": case "Const { is_type_const: false }": return ["const_item"];
    case "Static": case "Static { safety: Safe, mutability: Not, nested: false }": return ["static_item"];
    case "Impl": return ["impl_item"];
    case "Mod": return ["mod_item"];
    case "Macro": return ["macro_definition"];
    default: return [];
  }
}
const key = (range: CompilerRange, namespace: CompilerNamespace, kind: CompilerReference["kind"]) =>
  JSON.stringify([range.file, range.start, range.end, namespace, kind]);
const nodeIdentity = (node: CompilerScopeNode) => ({ id: node.id, sha256: node.sha256 });
function mappingIdentity(mapping: DeclarationMapping | undefined): unknown {
  if (!mapping) return { status: "unmapped", reason: "missing-target" };
  const declaration = mapping.declaration;
  return { status: mapping.status, ...(mapping.status === "mapped" ? { node: nodeIdentity(mapping.node) } : { reason: mapping.reason }),
    ...(declaration ? { declaration: { kind: declaration.kind, range: declaration.range, sourceSha256: declaration.sourceSha256 } } : {}) };
}

/** Exact compiler facts may select source obligations; they never prove that candidate behavior executed. */
export function compilerScope(
  index: CompilerBindingIndex,
  source: CompilerScopeSource,
  files: ReadonlyMap<string, CompilerImmutableFile>,
  census: readonly CompilerScopeNode[],
): CompilerScope {
  assert.equal(index.schema, 1); assert.equal(index.behavioralCoverage, false);
  assert.match(index.proofSha256, /^[a-f0-9]{64}$/);
  const proofSha256 = index.proofSha256;
  assert.equal(index.source.revision, source.sourceRevision, "compiler scope source revision differs");
  assert.equal(index.source.tree, source.sourceTree, "compiler scope source tree differs");
  assert.equal(index.source.originalCargoLockSha256, source.sourceCargoLockSha256, "compiler scope original lock differs");
  const sourceIdentity = { sourceRevision: source.sourceRevision, sourceTree: source.sourceTree, sourceCargoLockSha256: source.sourceCargoLockSha256 };
  const bytes = new Map<string, Buffer>();
  const immutable = (file: string): Buffer | undefined => {
    if (bytes.has(file)) return bytes.get(file);
    const input = files.get(file); if (!input) return;
    assert.equal(sha256(input.source), input.sha256, `compiler scope immutable source changed: ${file}`);
    const data = Buffer.from(input.source); bytes.set(file, data); return data;
  };
  const nodes = new Map<string, CompilerScopeNode>(), starts = new Map<string, CompilerScopeNode[]>(), staged = new Set<string>();
  for (const value of census) {
    assert.ok(!nodes.has(value.id), `duplicate compiler scope census ID: ${value.id}`);
    assert.equal(value.id, `${value.file}#${value.kind}@${value.start}`, "compiler scope census identity differs");
    const input = immutable(value.file); assert.ok(input, `compiler scope census source is absent: ${value.file}`);
    assert.ok(Number.isSafeInteger(value.start) && Number.isSafeInteger(value.end) && value.start >= 0 && value.end >= value.start &&
      (value.end > value.start || value.kind === "source-file") && value.end <= input.length, "compiler scope census range differs");
    assert.equal(sha256(input.subarray(value.start, value.end)), value.sha256, `compiler scope census bytes changed: ${value.id}`);
    const node = Object.freeze({ ...value }); nodes.set(node.id, node); staged.add(node.file);
    const at = JSON.stringify([node.file, node.start]), found = starts.get(at) ?? []; found.push(node); starts.set(at, found);
  }
  const definitions = new Set<string>();
  for (const definition of index.definitions) {
    assert.ok(!definitions.has(definition.key), "duplicate compiler definition identity"); definitions.add(definition.key);
  }
  const declarations = new Map<string, CompilerDeclaration[]>();
  for (const declaration of index.declarations) {
    const found = declarations.get(declaration.owner) ?? []; found.push(declaration); declarations.set(declaration.owner, found);
  }
  const mapped = new Map<string, DeclarationMapping>();
  const declaration = (owner: string): DeclarationMapping => {
    const cached = mapped.get(owner); if (cached) return cached;
    const found = declarations.get(owner) ?? [];
    let result: DeclarationMapping;
    if (!definitions.has(owner) || !found.length) result = { status: "unmapped", reason: "compiler declaration source is unavailable" };
    else if (found.length !== 1) result = { status: "ambiguous", reason: "compiler identity has multiple declaration records" };
    else {
      const item = found[0]!, range = item.range && Object.freeze({ ...item.range });
      const proof: DeclarationProof = Object.freeze({ compilerOwner: owner, kind: item.kind, ...(range ? { range, sourceSha256: files.get(range.file)?.sha256 } : {}) });
      const input = range && immutable(range.file);
      if (!range || !input) result = { status: "unmapped", reason: "compiler declaration has no immutable project source range", declaration: proof };
      else {
        assert.ok(Number.isSafeInteger(range.start) && Number.isSafeInteger(range.end) && range.start >= 0 && range.end >= range.start &&
          (range.end > range.start || item.kind === "Mod") && range.end <= input.length, "compiler declaration range exceeds immutable source");
        const kinds = compatibleKinds(item.kind);
        const candidates = (starts.get(JSON.stringify([range.file, range.start])) ?? []).filter(node =>
          kinds.includes(node.kind) && range.end <= node.end || item.kind === "Mod" && node.kind === "source-file" &&
            range.start === 0 && range.end === input.length && node.end === range.end);
        if (candidates.length === 1) result = { status: "mapped", node: candidates[0]!, declaration: proof };
        else if (candidates.length > 1) result = { status: "ambiguous", reason: "compiler declaration matches multiple exact-start AST declarations", declaration: proof };
        else result = { status: staged.has(range.file) ? "unmapped" : "unstaged", reason: kinds.length || item.kind === "Mod" ? "compiler declaration lacks a unique compatible exact-start AST declaration" : "compiler declaration kind has no reviewed census representation", declaration: proof };
      }
    }
    mapped.set(owner, result); return result;
  };
  const references = new Map<string, ReferenceMapping[]>(), sites = new Map<string, Map<string, CompilerScopeQuery>>(), semantic: string[] = [], issues: CompilerScope["issues"][number][] = [];
  for (const value of index.references) {
    const reference: CompilerReference = { ...value, ...(value.range ? { range: { ...value.range } } : {}) };
    const owner = declaration(reference.owner), target = reference.target ? declaration(reference.target) : undefined;
    semantic.push(sha256(JSON.stringify({ reference: reference.range, namespace: reference.namespace, kind: reference.kind, resolution: reference.resolution,
      owner: mappingIdentity(owner), target: mappingIdentity(target) })));
    const issue = (reason: string) => issues.push(Object.freeze({ ...(owner.status === "mapped" ? { owner: nodeIdentity(owner.node) } : {}),
      ...(reference.range ? { reference: Object.freeze({ ...reference.range }) } : {}), namespace: reference.namespace, kind: reference.kind,
      resolution: reference.resolution, reason, ...(target?.declaration ? { declaration: target.declaration } : {}) }));
    if (owner.status !== "mapped") { issue("compiler reference owner lacks a unique source AST binding"); continue; }
    if (!reference.range) { issue("compiler reference has no root-context immutable source span"); continue; }
    assert.ok(Number.isSafeInteger(reference.range.start) && Number.isSafeInteger(reference.range.end), "compiler reference is not an exact byte range");
    if (!(reference.range.file === owner.node.file && reference.range.start >= owner.node.start && reference.range.end <= owner.node.end && reference.range.start < reference.range.end)) {
      issue("compiler reference is outside its exact source AST owner"); continue;
    }
    if (reference.namespace === "unknown") issue("unknown compiler namespace remains debt");
    else if (!["Def", "FnDef"].includes(reference.resolution) || !reference.target) issue("dynamic, generic, indirect or unresolved compiler reference remains debt");
    else if (target?.status !== "mapped") issue("compiler target lacks a unique staged source AST binding");
    const at = JSON.stringify([owner.node.id, key(reference.range, reference.namespace, reference.kind)]), found = references.get(at) ?? [];
    found.push({ reference, owner, target }); references.set(at, found);
    const ownerSites = sites.get(owner.node.id) ?? new Map<string, CompilerScopeQuery>();
    ownerSites.set(at, Object.freeze({ ...reference.range, owner: owner.node.id, namespace: reference.namespace, kind: reference.kind }));
    sites.set(owner.node.id, ownerSites);
  }
  const semanticDigest = sha256(JSON.stringify({ algorithm: "rustc-exact-source-scope-v1", source: sourceIdentity, referenceDigests: [...new Set(semantic)].sort() }));
  const debt = (status: "unresolved" | "ambiguous" | "unstaged", reason: string, declarations: DeclarationProof[] = []): CompilerScopeResolution =>
    ({ status, reason, declarations, behavioralCoverage: false });
  return Object.freeze({ algorithm: "rustc-exact-source-scope-v1" as const, proofSha256, semanticDigest, behavioralCoverage: false as const,
    issues: Object.freeze(issues.sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)))),
    sites(owner: string): readonly CompilerScopeQuery[] { return Object.freeze([...(sites.get(owner)?.values() ?? [])].sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)))); },
    resolve(query: CompilerScopeQuery): CompilerScopeResolution {
      const owner = nodes.get(query.owner), found = references.get(JSON.stringify([query.owner, key(query, query.namespace, query.kind)])) ?? [];
      if (!owner || !found.length) return debt("unresolved", "no exact compiler reference and source owner match");
      const targets = found.map(item => item.target), proofs = targets.flatMap(item => item?.declaration ? [item.declaration] : []);
      if (query.namespace === "unknown") return debt("unresolved", "unknown compiler namespace cannot bind a source obligation", proofs);
      if (found.some(item => !["Def", "FnDef"].includes(item.reference.resolution) || !item.reference.target)) return debt("unresolved", "dynamic, generic, indirect or unresolved compiler reference remains debt", proofs);
      if (targets.some(item => item?.status === "ambiguous")) return debt("ambiguous", "compiler target declaration is ambiguous", proofs);
      if (targets.some(item => !item || item.status === "unmapped")) return debt("unresolved", "compiler target has no exact source AST binding", proofs);
      if (targets.some(item => item?.status === "unstaged")) return debt("unstaged", "exact compiler target source must be staged and inventoried", proofs);
      const bound = targets.filter((item): item is Extract<DeclarationMapping, { status: "mapped" }> => item?.status === "mapped");
      if (new Set(bound.map(item => item.node.id)).size !== 1) return debt("ambiguous", "exact compiler facts select different source declarations", proofs);
      return { status: "bound", owner, target: bound[0]!.node, behavioralCoverage: false, evidence: found.map(item => ({
        proofSha256, compilerOwner: item.reference.owner, compilerTarget: item.reference.target!, declaration: item.target!.declaration!,
        reference: { ...item.reference.range! }, namespace: item.reference.namespace, kind: item.reference.kind, resolution: item.reference.resolution,
      })) };
    },
  });
}
