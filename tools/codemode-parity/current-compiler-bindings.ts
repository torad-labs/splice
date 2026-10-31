import { compilerDef, compilerSpan, walkCompilerDebug, type CompilerDef, type CompilerFrame } from "./current-compiler-format";
import type { CompilerBindingIndex, CompilerDeclaration, CompilerNamespace, CompilerRawIndex, CompilerReference } from "./current-compiler-types";

export type { CompilerBindingIndex, CompilerDeclaration, CompilerExpectation, CompilerImmutableFile, CompilerNamespace, CompilerProof, CompilerRange, CompilerReference, CompilerSourceIdentity } from "./current-compiler-types";
export { loadCompilerBindings } from "./current-compiler-proof";
export type { CompilerDriverProof } from "./current-compiler-driver";

function namespace(kind: string, parents: readonly CompilerFrame[]): CompilerNamespace {
  const explicit = parents.findLast(frame => ["type_ns", "value_ns", "macro_ns"].includes(frame.key ?? ""))?.key;
  if (explicit) return explicit === "type_ns" ? "type" : explicit === "value_ns" ? "value" : "macro";
  if (["Struct", "Enum", "Trait", "TyAlias", "AssocTy", "Mod", "ForeignTy", "Union", "TyParam"].includes(kind)) return "type";
  if (["Fn", "AssocFn", "Ctor", "Const", "AssocConst", "Static", "Variant", "ConstParam"].includes(kind)) return "value";
  if (kind.startsWith("Macro")) return "macro";
  return "unknown";
}

/** Collects only compiler-recorded facts; no source identifier or module-name lookup occurs here. */
export async function parseCompilerBindings(hir: AsyncIterable<string>, thir: AsyncIterable<string>): Promise<CompilerRawIndex> {
  const definitions = new Map<string, CompilerDef>(), declarations = new Map<string, CompilerDeclaration>(), references = new Map<string, CompilerReference>();
  // Local definitions share the same checked source/argv in HIR and THIR. Extern crate numbers are
  // session-local, so external facts remain separate between the two compiler invocations.
  const define = (value: CompilerDef, mode: "hir" | "thir") => {
    const scoped = { ...value, key: `${value.crateNumber === 0 ? "local" : mode}:${value.key}` };
    definitions.set(scoped.key, scoped); return scoped;
  };
  const reference = (value: CompilerReference) => { references.set(JSON.stringify(value), value); };
  type PathResolution = { target?: CompilerDef; namespace: CompilerNamespace; resolution: string };
  const pathResolutions = new WeakMap<CompilerFrame, PathResolution[]>();
  await walkCompilerDebug(hir, "hir", {
    open(frame) { define(frame.owner, "hir"); },
    close(frame, parents) {
      if (frame.type === "Def") {
        const pathIndex = parents.findLastIndex(parent => parent.type === "Path");
        const path = parents[pathIndex];
        const chain = parents.slice(pathIndex + 1);
        if (path && !chain.some(parent => parent.type === "PathSegment") && (frame.key === "res" || chain.some(parent => parent.key === "res"))) {
          const targets = frame.values.map(compilerDef).filter((value): value is CompilerDef => value !== undefined);
          if (targets.length !== 1) throw new Error("compiler path resolution has no unique definition");
          const target = define(targets[0]!, "hir");
          const found = pathResolutions.get(path) ?? [];
          found.push({ target, namespace: namespace(frame.children.get("$first") ?? frame.values[0] ?? "", [...parents, frame]), resolution: "Def" });
          pathResolutions.set(path, found);
        }
      }
      if (frame.type === "Path") {
        const span = compilerSpan(frame.fields.get("span"));
        const found = pathResolutions.get(frame) ?? [{ namespace: "unknown" as const, resolution: frame.fields.get("res") ?? frame.children.get("res") ?? "unresolved" }];
        for (const item of found) reference({ owner: define(frame.owner, "hir").key, span, kind: parents.some(parent => parent.type === "Use") ? "import" : "path",
          namespace: item.namespace, target: item.target?.key, resolution: item.resolution });
      }
      if (["Item", "TraitItem", "ImplItem", "ForeignItem"].includes(frame.type)) {
        const owner = compilerDef(frame.fields.get("owner_id") ?? "");
        if (owner?.key === frame.owner.key) {
          const span = compilerSpan(frame.fields.get("span"));
          const kind = frame.children.get("kind") ?? frame.fields.get("kind") ?? frame.type;
          const key = define(owner, "hir").key, prior = declarations.get(key);
          if (prior && JSON.stringify(prior.span) !== JSON.stringify(span)) throw new Error("compiler declaration has conflicting spans");
          declarations.set(key, { owner: key, kind, span });
        }
      }
      if (["FieldDef", "Variant", "GenericParam"].includes(frame.type)) {
        const item = compilerDef(frame.fields.get("def_id") ?? "");
        if (!item) throw new Error("compiler nested declaration omitted its definition");
        const key = define(item, "hir").key;
        declarations.set(key, { owner: key, parentOwner: define(frame.owner, "hir").key, kind: frame.type, span: compilerSpan(frame.fields.get("span")) });
      }
    },
  });
  type Target = { target: CompilerDef; resolution: string };
  type ExprFacts = { value?: Target; call?: { target?: CompilerDef; resolution: string; fnSpan?: ReturnType<typeof compilerSpan> } };
  const exprs = new WeakMap<CompilerFrame, ExprFacts>(), calls = new WeakMap<CompilerFrame, Target>(), dependent = new WeakMap<CompilerFrame, string>();
  await walkCompilerDebug(thir, "thir", {
    open(frame) { define(frame.owner, "thir"); },
    scalar(frame, _key, value, parents) {
      const fn = [...parents, frame].findLast(parent => parent.type === "FnDef");
      if (!fn) return;
      if (/\bdyn\s+\[/.test(value)) dependent.set(fn, "dynamic-call");
      else if (/[A-Za-z_][A-Za-z_0-9]*\/#\d+/.test(value) && dependent.get(fn) !== "dynamic-call") dependent.set(fn, "generic-call");
    },
    close(frame, parents) {
      const parent = parents.at(-1);
      if (frame.type === "FnDef" && frame.key === "ty") {
        const target = compilerDef(frame.values[0] ?? "");
        if (!target) throw new Error("compiler FnDef omitted its exact definition");
        const typed = { target: define(target, "thir"), resolution: dependent.get(frame) ?? "FnDef" };
        if (parent?.type === "Call") calls.set(parent, typed);
        if (parent?.type === "Expr") {
          const facts = exprs.get(parent) ?? {}; facts.value = typed; exprs.set(parent, facts);
        }
      }
      if (frame.type === "Call" && frame.key === "kind" && parent?.type === "Expr") {
        const facts = exprs.get(parent) ?? {};
        facts.call = { target: calls.get(frame)?.target, resolution: calls.get(frame)?.resolution ?? "indirect-call", fnSpan: compilerSpan(frame.fields.get("fn_span")) };
        exprs.set(parent, facts);
      }
      if (frame.type === "Expr") {
        const facts = exprs.get(frame), span = compilerSpan(frame.fields.get("span"));
        if (facts?.value) reference({ owner: define(frame.owner, "thir").key, span, namespace: "value", kind: "typed-value", target: facts.value.target.key, resolution: facts.value.resolution });
        if (facts?.call) reference({ owner: define(frame.owner, "thir").key, span, namespace: "value", kind: "call", target: facts.call.target?.key,
          fnSpan: facts.call.fnSpan, resolution: facts.call.resolution });
      }
    },
  });
  if (!declarations.size) throw new Error("compiler HIR supplied no declarations");
  return { definitions: [...definitions.values()], declarations: [...declarations.values()], references: [...references.values()] };
}

/** Only an exact range/namespace/owner match can bind; ambiguity and missing compiler evidence remain unresolved. */
const lookupTables = new WeakMap<CompilerBindingIndex, { count: number; references: Map<string, CompilerReference[]>; definitions: Map<string, CompilerDef>; declarations: Map<string, CompilerDeclaration> }>();
function lookupKey(file: string, start: number, end: number, namespace: CompilerNamespace): string { return `${file}\0${start}\0${end}\0${namespace}`; }
export function findCompilerBinding(index: CompilerBindingIndex, query: { file: string; start: number; end: number; namespace: CompilerNamespace; owner?: string; kind?: CompilerReference["kind"] }):
  { status: "bound"; target: CompilerDef; declaration?: CompilerDeclaration } | { status: "ambiguous" | "unresolved" } {
  let table = lookupTables.get(index);
  if (!table || table.count !== index.references.length) {
    const references = new Map<string, CompilerReference[]>();
    for (const reference of index.references) {
      if (!reference.range) continue;
      const key = lookupKey(reference.range.file, reference.range.start, reference.range.end, reference.namespace), rows = references.get(key) ?? [];
      rows.push(reference); references.set(key, rows);
    }
    table = { count: index.references.length, references, definitions: new Map(index.definitions.map(item => [item.key, item])), declarations: new Map(index.declarations.map(item => [item.owner, item])) };
    lookupTables.set(index, table);
  }
  const found = (table.references.get(lookupKey(query.file, query.start, query.end, query.namespace)) ?? []).filter(reference =>
    (query.owner === undefined || query.owner === reference.owner) && (query.kind === undefined || query.kind === reference.kind));
  if (!found.length || found.some(reference => !reference.target || !["Def", "FnDef"].includes(reference.resolution))) return { status: "unresolved" };
  const targets = new Set(found.map(reference => reference.target));
  if (targets.size !== 1) return { status: "ambiguous" };
  const target = table.definitions.get([...targets][0]!);
  return target ? { status: "bound", target, declaration: table.declarations.get(target.key) } : { status: "unresolved" };
}
