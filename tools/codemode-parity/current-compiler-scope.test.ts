import { expect, test } from "bun:test";
import { compilerScope, type CompilerScopeNode, type CompilerScopeQuery } from "./current-compiler-scope";
import type { CompilerBindingIndex, CompilerImmutableFile } from "./current-compiler-types";
import type { CompilerDef } from "./current-compiler-format";
import { sha256 } from "./qualification";

const file = "codex-rs/fixture/src/lib.rs", foreign = "codex-rs/foreign/src/lib.rs";
function fixture() {
  const text = "// λ 😀 byte offsets are not UTF16 columns\n" +
    "pub fn owner() { alpha::run(); }\n" +
    "mod alpha { pub fn run() {} }\nmod beta { pub fn run() {} }\n";
  const files = new Map<string, CompilerImmutableFile>([
    [file, { source: text, sha256: sha256(text) }],
    [foreign, { source: "pub fn run() {}\n", sha256: sha256("pub fn run() {}\n") }],
  ]);
  const range = (value: string, occurrence = 0, path = file) => {
    const source = files.get(path)!.source; let at = -1;
    for (let index = 0; index <= occurrence; index++) at = source.indexOf(value, at + 1);
    if (at < 0) throw new Error("fixture source token missing");
    const start = Buffer.byteLength(source.slice(0, at)); return { file: path, start, end: start + Buffer.byteLength(value) };
  };
  const node = (kind: string, value: string, occurrence = 0, path = file): CompilerScopeNode => {
    const span = range(value, occurrence, path); return { id: `${path}#${kind}@${span.start}`, kind, ...span, sha256: sha256(value) };
  };
  const owner = node("function_item", "pub fn owner() { alpha::run(); }"), alpha = node("function_item", "pub fn run() {}"),
    beta = node("function_item", "pub fn run() {}", 1), external = node("function_item", "pub fn run() {}", 0, foreign);
  const define = (key: string, path: string, index: number): CompilerDef => ({ key, raw: "opaque compiler identity", crate: "fixture",
    crateNumber: 0, crateHash: "f".repeat(64), index, path });
  const source = { sourceRevision: "a".repeat(40), sourceTree: "b".repeat(40), sourceCargoLockSha256: "c".repeat(64) };
  const index: CompilerBindingIndex = { schema: 1, source: { revision: source.sourceRevision, tree: source.sourceTree,
    originalCargoLockSha256: source.sourceCargoLockSha256, buildCargoLockSha256: "e".repeat(64) }, proofSha256: "d".repeat(64), behavioralCoverage: false,
    definitions: [define("owner", "wrong::owner", 1), define("alpha", "wrong::beta::run", 2), define("beta", "wrong::alpha::run", 3)],
    declarations: [{ owner: "owner", kind: "Fn", range: range("pub fn owner()") },
      { owner: "alpha", kind: "AssocFn", range: range("pub fn run()") }, { owner: "beta", kind: "AssocFn", range: range("pub fn run()", 1) }],
    references: [{ owner: "owner", target: "alpha", namespace: "value", kind: "call", resolution: "FnDef", range: range("alpha::run()") }],
  };
  const query: CompilerScopeQuery = { ...range("alpha::run()"), owner: owner.id, namespace: "value", kind: "call" };
  const census = [owner, alpha, beta];
  return { files, source, index, census, query, range, owner, alpha, beta, external };
}

test("exact source prefixes bind full UTF8 AST declarations while pretty paths and same-name siblings have no authority", () => {
  const f = fixture(), scope = compilerScope(f.index, f.source, f.files, f.census), found = scope.resolve(f.query);
  expect(found.status).toBe("bound");
  if (found.status !== "bound") throw new Error("source-backed fixture did not bind");
  expect(found.target.id).toBe(f.alpha.id); expect(found.target.sha256).toBe(f.alpha.sha256);
  expect(found.owner.id).toBe(f.owner.id); expect(found.evidence[0]!.compilerTarget).toBe("alpha");
  expect(found.evidence[0]!.declaration.range).toEqual(f.range("pub fn run()"));
  expect(found.behavioralCoverage).toBe(false); expect(scope.behavioralCoverage).toBe(false);
  expect(scope.issues).toEqual([]);
});

test("reference byte range, namespace, kind and exact census owner are all mandatory", () => {
  const f = fixture(), scope = compilerScope(f.index, f.source, f.files, f.census);
  for (const query of [{ ...f.query, start: f.query.start + 1 }, { ...f.query, end: f.query.end - 1 },
    { ...f.query, namespace: "type" as const }, { ...f.query, namespace: "unknown" as const },
    { ...f.query, kind: "path" as const }, { ...f.query, owner: f.beta.id }]) expect(scope.resolve(query).status).toBe("unresolved");
});

test("a nested or nearby declaration cannot inherit a compiler declaration with a different start or kind", () => {
  const f = fixture(); f.index.declarations[1]!.range!.start++;
  expect(compilerScope(f.index, f.source, f.files, f.census).resolve(f.query).status).toBe("unresolved");
  const changed = fixture(); changed.index.declarations[1]!.kind = "Struct";
  expect(compilerScope(changed.index, changed.source, changed.files, changed.census).resolve(changed.query).status).toBe("unresolved");
  const ctor = fixture(); ctor.index.declarations[1]!.kind = "Ctor(Struct, Fn)";
  expect(compilerScope(ctor.index, ctor.source, ctor.files, ctor.census).resolve(ctor.query).status).toBe("unresolved");
});

test("ambiguous compatible AST nodes and conflicting compiler targets remain debt", () => {
  const f = fixture();
  const signature = { ...f.alpha, id: f.alpha.id.replace("#function_item@", "#function_signature_item@"), kind: "function_signature_item" };
  expect(compilerScope(f.index, f.source, f.files, [...f.census, signature]).resolve(f.query).status).toBe("ambiguous");
  const conflict = fixture(); conflict.index.references.push({ ...conflict.index.references[0]!, target: "beta" });
  expect(compilerScope(conflict.index, conflict.source, conflict.files, conflict.census).resolve(conflict.query).status).toBe("ambiguous");
});

test("dynamic generic trait-contract and indirect facts never masquerade as concrete calls", () => {
  for (const resolution of ["dynamic-call", "generic-call", "trait-contract-call", "indirect-call", "unresolved"]) {
    const f = fixture(); f.index.references[0]!.resolution = resolution;
    const scope = compilerScope(f.index, f.source, f.files, f.census);
    expect(scope.resolve(f.query).status).toBe("unresolved");
    expect(scope.issues.some(issue => issue.reason.includes("remains debt"))).toBe(true);
  }
});

test("missing generated-context reference or target spans remain explicit unresolved facts", () => {
  const f = fixture(); delete f.index.references[0]!.range;
  const scope = compilerScope(f.index, f.source, f.files, f.census);
  expect(scope.resolve(f.query).status).toBe("unresolved");
  expect(scope.issues[0]!.reason).toContain("no root-context immutable source span");
  const target = fixture(); delete target.index.declarations[1]!.range;
  expect(compilerScope(target.index, target.source, target.files, target.census).resolve(target.query).status).toBe("unresolved");
  const owner = fixture(); delete owner.index.declarations[0]!.range;
  expect(compilerScope(owner.index, owner.source, owner.files, owner.census).resolve(owner.query).status).toBe("unresolved");
});

test("an exact unstaged project target requests its immutable file before any AST binding is granted", () => {
  const f = fixture(); f.index.declarations[1]!.range = f.range("pub fn run()", 0, foreign);
  const pending = compilerScope(f.index, f.source, f.files, f.census).resolve(f.query);
  expect(pending.status).toBe("unstaged");
  if (pending.status === "bound") throw new Error("unstaged source earned a target");
  expect(pending.declarations[0]!.range?.file).toBe(foreign);
  expect(pending.declarations[0]!.sourceSha256).toBe(f.files.get(foreign)!.sha256);
  const resolved = compilerScope(f.index, f.source, f.files, [...f.census, f.external]).resolve(f.query);
  expect(resolved.status).toBe("bound"); if (resolved.status === "bound") expect(resolved.target.id).toBe(f.external.id);
});

test("changed reference source identity, whole file bytes, node bytes and duplicate IDs fail closed", () => {
  const f = fixture();
  expect(() => compilerScope(f.index, { ...f.source, sourceTree: "0".repeat(40) }, f.files, f.census)).toThrow("source tree differs");
  expect(() => compilerScope(f.index, { ...f.source, sourceRevision: "0".repeat(40) }, f.files, f.census)).toThrow("source revision differs");
  expect(() => compilerScope(f.index, { ...f.source, sourceCargoLockSha256: "0".repeat(64) }, f.files, f.census)).toThrow("original lock differs");
  const changed = new Map(f.files); changed.set(file, { ...changed.get(file)!, sha256: "0".repeat(64) });
  expect(() => compilerScope(f.index, f.source, changed, f.census)).toThrow("immutable source changed");
  expect(() => compilerScope(f.index, f.source, f.files, [{ ...f.owner, sha256: "0".repeat(64) }, f.alpha, f.beta])).toThrow("census bytes changed");
  expect(() => compilerScope(f.index, f.source, f.files, [...f.census, f.owner])).toThrow("duplicate compiler scope census ID");
});

test("semantic source decisions ignore debug names and input order while proof identity remains separately bound", () => {
  const f = fixture(), first = compilerScope(f.index, f.source, f.files, f.census);
  const changed = structuredClone(f.index); changed.proofSha256 = "1".repeat(64); changed.definitions.reverse(); changed.declarations.reverse();
  for (const definition of changed.definitions) { definition.path = "a deliberately misleading pretty path"; definition.raw = "another pretty rendering"; }
  const next = compilerScope(changed, f.source, f.files, [...f.census].reverse());
  expect(next.semanticDigest).toBe(first.semanticDigest); expect(next.proofSha256).not.toBe(first.proofSha256);
  expect(next.resolve(f.query).status).toBe("bound");
});

test("the scope snapshots its facts and never exposes mutable internal declaration proofs", () => {
  const f = fixture(), scope = compilerScope(f.index, f.source, f.files, f.census);
  f.index.references[0]!.target = "beta"; f.index.proofSha256 = "0".repeat(64); f.alpha.sha256 = "0".repeat(64);
  const found = scope.resolve(f.query); expect(found.status).toBe("bound");
  if (found.status !== "bound") throw new Error("scope lost its snapshot");
  expect(found.target.id).toBe(f.alpha.id); expect(found.target.sha256).not.toBe(f.alpha.sha256);
  expect(found.evidence[0]!.proofSha256).toBe("d".repeat(64));
  expect(() => { found.evidence[0]!.declaration.range!.start++; }).toThrow();
});

test("fractional byte ranges fail closed and an unknown source namespace remains visible debt", () => {
  const f = fixture(); f.index.references[0]!.range!.start += 0.5;
  expect(() => compilerScope(f.index, f.source, f.files, f.census)).toThrow("exact byte range");
  const unknown = fixture(); unknown.index.references[0]!.namespace = "unknown";
  const scope = compilerScope(unknown.index, unknown.source, unknown.files, unknown.census);
  expect(scope.resolve({ ...unknown.query, namespace: "unknown" }).status).toBe("unresolved");
  expect(scope.issues.some(issue => issue.reason.includes("unknown compiler namespace"))).toBe(true);
});

test("source identity digest excludes unrelated inventory fields and empty source files remain in the census", () => {
  const f = fixture(), first = compilerScope(f.index, f.source, f.files, f.census);
  const augmented = { arbitraryUnrelatedInventoryData: "retained but not a source identity", ...f.source };
  expect(compilerScope(f.index, augmented, f.files, f.census).semanticDigest).toBe(first.semanticDigest);
  const empty = "codex-rs/empty/src/lib.rs";
  f.files.set(empty, { source: "", sha256: sha256("") });
  const node: CompilerScopeNode = { id: `${empty}#source-file@0`, file: empty, kind: "source-file", start: 0, end: 0, sha256: sha256("") };
  expect(compilerScope(f.index, f.source, f.files, [...f.census, node]).resolve(f.query).status).toBe("bound");
});
