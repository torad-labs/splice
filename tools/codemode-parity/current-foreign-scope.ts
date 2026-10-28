import { dirname, relative } from "node:path";
import { sha256 } from "./qualification";
import { generatedProtoSymbols } from "./current-proto-scope";

export type AstMatch = { ruleId: string; file: string; text: string; range: { byteOffset: { start: number; end: number }; start: { line: number } } };
export type ScopeFile = { path: string; source: string };
export type ForeignScopeIssue = { from: string; file: string; expression: string; paths: string[] };
export type GeneratedScopeResolution = { from: string; expression: string; path: string; kind: "tonic-prost-schema"; selectors: Array<{ path: string; sha256: string }>; schema: string[] };
export type RepresentationScope = { algorithm: "private-nonwire-layout-v1"; owner: { id: string; sha256: string }; methods: Array<{ id: string; sha256: string }>; predicate: string };
export type ForeignScope = { state: "required" | "foreign-context"; algorithm: "ast-import-member-closure-v1"; selectorsSha256: string; incoming: Array<{ from: string; relation: string }>; route: string[]; rootBoundary?: { kind: "application-entrypoint-not-codemode-root"; fileSha256: string; predicate: string } };
type Declaration = { id: string; file: string; kind: string; name: string; start: number; end: number; text: string; owner?: Declaration; implType?: string; implTypePath?: string; implTrait?: string };
const declarationKinds = new Set(["function_item", "const_item", "static_item", "type_item", "struct_item", "enum_item", "trait_item", "field_declaration", "ordered_field_declaration_list", "enum_variant", "function_signature_item", "impl_item", "mod_item"]);
const aggregates = new Set(["struct_item", "enum_item", "trait_item"]);
const marker = /code[_-]mode|CodeMode|codeMode/;
// This controls seeding only: an actual active CodeMode call/import into an
// application entrypoint still retains that exact helper and its dependencies.
const applicationRootBoundary = (file: string) => /^codex-rs\/(?:tui|cli|app-server(?:-daemon)?|thread-manager-sample|windows-sandbox-service|install-context|analytics|rollout-trace)\//.test(file) || /\/telemetry\.rs$/.test(file);
const identifiers = (text: string) => [...new Set(text.match(/[A-Za-z_][A-Za-z_0-9]*/g) ?? [])];
function label(kind: string, text: string): string {
  if (["function_item", "function_signature_item"].includes(kind)) return text.match(/\bfn\s+(\w+)/)?.[1] ?? "";
  return text.match(/\b(?:fn|struct|enum|type|const|static|trait|mod)\s+(\w+)/)?.[1] ??
    (kind === "field_declaration" ? text.match(/^(?:pub(?:\([^)]*\))?\s+)?(\w+)\s*:/)?.[1] : text.match(/^\s*(\w+)/)?.[1]) ?? "";
}
function splitItems(text: string): string[] {
  const items: string[] = []; let depth = 0, start = 0;
  for (let index = 0; index < text.length; index++) { if (text[index] === "{") depth++; if (text[index] === "}") depth--; if (text[index] === "," && depth === 0) { items.push(text.slice(start, index).trim()); start = index + 1; } }
  items.push(text.slice(start).trim()); return items.filter(Boolean);
}
function imports(text: string, prefix = ""): Array<{ path: string; alias: string }> {
  const result: Array<{ path: string; alias: string }> = [];
  for (const item of splitItems(text)) {
    const brace = item.indexOf("{");
    if (brace >= 0) { result.push(...imports(item.slice(brace + 1, item.lastIndexOf("}")), prefix + item.slice(0, brace))); continue; }
    const [path, alias] = item.split(/\s+as\s+/), full = prefix + path;
    result.push({ path: full.replace(/::self$/, ""), alias: alias ?? full.split("::").at(-1)! });
  }
  return result;
}
/**
 * A foreign import names declarations, never every declaration sharing their file.
 * Types retain explicit variants/fields/methods referenced by the selected callers.
 * Every decision retains the complete selector/file digest; unknown imports are
 * conservative and keep matching-module declarations required.
 */
export function foreignMemberScope(files: ScopeFile[], raw: AstMatch[], temporary: string, crates: string[], manifests: Array<{ path: string; source: string }>, explicitRoots?: string[]): { selections: Map<string, ForeignScope>; unresolved: ForeignScopeIssue[]; generated: GeneratedScopeResolution[]; representations: Map<string, RepresentationScope> } {
  const ast = raw.map(item => ({ ...item, file: relative(temporary, item.file) })).filter(item => item.file.endsWith(".rs"))
    .sort((a,b)=>a.file.localeCompare(b.file)||a.range.byteOffset.start-b.range.byteOffset.start||a.range.byteOffset.end-b.range.byteOffset.end||a.ruleId.localeCompare(b.ruleId));
  const crateRoots = manifests.map(file => ({ root: dirname(file.path), name: file.source.match(/\bname\s*=\s*"([^"]+)"/)?.[1]?.replaceAll("-", "_") })).filter(item => item.name);
  const externalCrates = new Set(["std", "core", "alloc"]);
  for (const manifest of manifests) {
    let dependencies = false;
    for (const line of manifest.source.split("\n")) {
      if (/^\s*\[/.test(line)) {
        const table = line.trim().slice(1, -1);
        dependencies = /(?:^|\.)(?:dependencies|dev-dependencies|build-dependencies)$/.test(table);
        const dependency = table.match(/(?:^|\.)(?:dependencies|dev-dependencies|build-dependencies)\.([\w-]+)$/)?.[1];
        if (dependency) externalCrates.add(dependency.replaceAll("-", "_"));
      } else if (dependencies) {
        const name = line.match(/^\s*([\w-]+)\s*=/)?.[1];
        if (name) externalCrates.add(name.replaceAll("-", "_"));
      }
    }
  }
  for (const crate of crateRoots) externalCrates.delete(crate.name!);
  const crateCache = new Map<string, typeof crateRoots[number] | undefined>(), moduleCache = new Map<string, string | undefined>();
  const crateFor = (file: string) => { if (!crateCache.has(file)) crateCache.set(file, crateRoots.filter(crate => file.startsWith(crate.root + "/")).sort((a, b) => b.root.length - a.root.length)[0]); return crateCache.get(file); };
  const moduleFor = (file: string) => { if (!moduleCache.has(file)) { const crate = crateFor(file); moduleCache.set(file, crate ? [crate.name, file.slice(crate.root.length + 5).replace(/\/(?:mod|lib|main)\.rs$|^(?:lib|main)\.rs$|\.rs$/g, "").replaceAll("/", "::")].filter(Boolean).join("::") : undefined); } return moduleCache.get(file); };
  // A Cargo package can contain both a library and a binary crate root. Public
  // package paths resolve through lib.rs; main.rs must not overwrite that route.
  const moduleFiles = new Map<string | undefined, ScopeFile>();
  for (const file of files.filter(file => file.path.endsWith(".rs"))) {
    const module = moduleFor(file.path), previous = moduleFiles.get(module);
    if (!previous || file.path.endsWith("/lib.rs") || !previous.path.endsWith("/lib.rs")) moduleFiles.set(module, file);
  }
  const declarations: Declaration[] = ast.filter(item => declarationKinds.has(item.ruleId)).map(item => ({ id: `${item.file}#${item.ruleId}@${item.range.byteOffset.start}`, file: item.file, kind: item.ruleId,
    name: label(item.ruleId, item.text), start: item.range.byteOffset.start, end: item.range.byteOffset.end, text: item.text }));
  for (const item of declarations) {
    const enclosing = declarations.filter(parent => parent.file === item.file && parent !== item && parent.start <= item.start && parent.end >= item.end).sort((a, b) => (a.end - a.start) - (b.end - b.start));
    item.owner = enclosing[0];
    const impl = item.kind === "impl_item" ? item : enclosing.find(parent => parent.kind === "impl_item");
    if (impl) {
      let header = impl.text.slice(0, impl.text.indexOf("{")).replace(/^\s*(?:unsafe\s+)?impl\s*/, "");
      if (header.startsWith("<")) { let depth = 0, end = 0; for (; end < header.length; end++) { if (header[end] === "<") depth++; if (header[end] === ">" && --depth === 0) { end++; break; } } header = header.slice(end).trim(); }
      if (/\sfor\s/.test(header)) { item.implTrait = header.match(/^((?:\w+::)*\w+)/)?.[1]?.split("::").at(-1); header = header.split(/\sfor\s/).at(-1)!.trim(); }
      item.implTypePath = header.match(/^((?:\w+::)*\w+)/)?.[1];
      item.implType = header.match(/^(?:\w+::)*(\w+)/)?.[1];
    }
  }
  const definitions = new Map<string, Declaration[]>(), byFile = new Map<string, Declaration[]>();
  const put = (key: string, item: Declaration) => { const found = definitions.get(key) ?? []; if (!found.includes(item)) found.push(item); definitions.set(key, found); };
  for (const item of declarations) {
    const list = byFile.get(item.file) ?? []; list.push(item); byFile.set(item.file, list);
    if (!item.name || item.kind === "impl_item") continue;
    const module = moduleFor(item.file); if (!module) continue;
    if (item.owner && aggregates.has(item.owner.kind)) put(`${module}::${item.owner.name}::${item.name}`, item);
    else if (item.implType) put(`${module}::${item.implType}::${item.name}`, item);
    else if (!item.owner || item.owner.kind === "impl_item") put(`${module}::${item.name}`, item);
  }
  const importMap = new Map<string, Map<string, string[]>>();
  const absolute = (path: string, file: string) => {
    const module = moduleFor(file), crate = crateFor(file); if (!module || !crate) return path;
    if (path.startsWith("crate::")) return crate.name + path.slice(5);
    if (path.startsWith("self::")) return module + path.slice(4);
    if (path.startsWith("super::")) { let parent = module, rest = path; while (rest.startsWith("super::")) { parent = parent.split("::").slice(0, -1).join("::"); rest = rest.slice(7); } return parent + "::" + rest; }
    if (crateRoots.some(root => path === root.name || path.startsWith(root.name + "::"))) return path;
    if (externalCrates.has(path.split("::")[0]!)) return path;
    return `${module}::${path}`;
  };
  for (const item of ast.filter(item => item.ruleId === "use_declaration")) {
    const map = importMap.get(item.file) ?? new Map<string, string[]>();
    for (const entry of imports(item.text.replace(/^\s*(?:pub(?:\([^)]*\))?\s+)?use\s+/, "").replace(/;\s*$/, ""))) {
      const values = map.get(entry.alias) ?? []; values.push(absolute(entry.path, item.file)); map.set(entry.alias, values);
    }
    importMap.set(item.file, map);
  }
  const resolvedNames = new Map<string, Declaration[]>();
  const projectPaths = (name: string, file: string, implType?: string): string[] => {
    if (name.startsWith("Self::") && implType) name = implType + name.slice(4);
    const [head, ...tail] = name.split("::"), imported = importMap.get(file)?.get(head!);
    // Locals and ordinary prelude identifiers are not unresolved project imports.
    const paths = imported?.map(path => [path, ...tail].join("::")) ??
      (/^(?:crate|self|super)::/.test(name) || crateRoots.some(crate => name.startsWith(crate.name + "::")) || definitions.has(absolute(head!, file)) ? [absolute(name, file)] : []);
    return paths.filter(path => crateRoots.some(crate => path.startsWith(crate.name + "::")));
  };
  const resolveName = (name: string, file: string, implType?: string, seen = new Set<string>()): Declaration[] => {
    const key = `${file}:${name}:${implType ?? ""}`; if (seen.has(key)) return [];
    // A failed branch of a reexport cycle must not poison another branch or a
    // later top-level lookup. Only context-free lookups may enter the cache.
    const visited = new Set(seen); visited.add(key);
    if (resolvedNames.has(key)) return resolvedNames.get(key)!;
    if (name.startsWith("Self::") && implType) name = implType + name.slice(4);
    const [head, ...tail] = name.split("::"), imported = importMap.get(file)?.get(head!);
    const wildcard = importMap.get(file)?.get("*") ?? [];
    const choices = [...(imported?.map(path => [path, ...tail].join("::")) ?? []),
      ...wildcard.map(path => path.replace(/\*$/, name)), absolute(name, file)];
    const results: Declaration[] = [];
    for (const path of choices) {
      results.push(...(definitions.get(path) ?? []));
      // Reexports are resolved through the actual target module's use declarations.
      const parts = path.split("::"), member = parts.pop()!, module = parts.join("::");
      const moduleFile = moduleFiles.get(module);
      if (moduleFile && (importMap.get(moduleFile.path)?.has(member) || importMap.get(moduleFile.path)?.has("*"))) results.push(...resolveName(member, moduleFile.path, undefined, visited));
      if (!definitions.has(path) && parts.length > 1) {
        const ownerName = parts.pop()!, ownerModule = parts.join("::"), ownerFile = moduleFiles.get(ownerModule);
        if (ownerFile) for (const owner of resolveName(ownerName, ownerFile.path, undefined, visited)) {
          results.push(...memberTargets(owner, member));
        }
      }
    }
    const answer = [...new Set(results)]; if (!seen.size) resolvedNames.set(key, answer); return answer;
  };
  const required = new Set<string>(), incoming = new Map<string, ForeignScope["incoming"]>(), routes = new Map<string, string[]>(), queue: Declaration[] = [];
  const unresolved = new Map<string, ForeignScopeIssue>();
  const generatedSymbols = generatedProtoSymbols(files, moduleFor), generated = new Map<string, GeneratedScopeResolution>();
  const astByFile = new Map(files.map(file => [file.path, ast.filter(item => item.file === file.path)]));
  const implementsType = (item: Declaration, type: Declaration) => item.implType === type.name &&
    (item.file === type.file || resolveName(item.implTypePath ?? item.implType, item.file).includes(type));
  const memberTargets = (type: Declaration, name: string, seen = new Set<string>()): Declaration[] => {
    if (seen.has(type.id)) return []; seen.add(type.id);
    const members = declarations.filter(item => item.name === name && (item.owner === type || implementsType(item, type)));
    // Aliases can expose constructors/methods of another project type. Retain
    // every matching local candidate instead of silently excluding the target.
    if (type.kind === "type_item") for (const ref of (astByFile.get(type.file) ?? []).filter(node => ["type_identifier", "scoped_type_identifier"].includes(node.ruleId) && node.range.byteOffset.start >= type.start && node.range.byteOffset.end <= type.end)) {
      for (const target of resolveName(ref.text, type.file)) if (target !== type) members.push(...memberTargets(target, name, seen));
    }
    if (!members.length && name === "default") {
      const source = files.find(file => file.path === type.file)?.source ?? "", prefix = Buffer.from(source).subarray(Math.max(0, type.start - 1200), type.start).toString().split(/\n\s*\n/).at(-1) ?? "";
      if (/#\[derive\([^\]]*\bDefault\b[^\]]*\)\]/.test(prefix)) members.push(type);
    }
    const headers = type.kind === "trait_item" ? [type] :
      declarations.filter(item => item.kind === "impl_item" && implementsType(item, type) && /\sfor\s/.test(item.text.slice(0, item.text.indexOf("{"))));
    for (const owner of headers) {
      const headerEnd = owner.start + Buffer.byteLength(owner.text.slice(0, owner.text.indexOf("{")));
      for (const ref of (astByFile.get(owner.file) ?? []).filter(node => ["type_identifier", "scoped_type_identifier"].includes(node.ruleId) && node.range.byteOffset.start >= owner.start && node.range.byteOffset.end <= headerEnd)) {
        for (const base of resolveName(ref.text, owner.file)) if (base.kind === "trait_item" && base !== type) members.push(...memberTargets(base, name, seen));
      }
    }
    return [...new Set(members)];
  };
  const mark = (item: Declaration, from: string, relation: string) => {
    const links = incoming.get(item.id) ?? []; if (!links.some(link => link.from === from && link.relation === relation)) links.push({ from, relation }); incoming.set(item.id, links);
    if (!required.has(item.id)) { required.add(item.id); routes.set(item.id, [...(routes.get(from) ?? [from]), item.id]); queue.push(item); }
  };
  const serialized = new Set<string>();
  const implicitMethods = (type: Declaration, traits: string[], from: string, relation: string) => {
    for (const method of declarations) if (method.kind === "function_item" && method.implTrait && traits.includes(method.implTrait) && implementsType(method, type)) mark(method, from, relation);
  };
  const serdeHelpers = (item: Declaration) => {
    const previousEnd = Math.max(item.owner?.start ?? 0, ...(byFile.get(item.file) ?? []).filter(previous => previous !== item && previous.owner === item.owner && previous.end <= item.start).map(previous => previous.end));
    const attributes = (astByFile.get(item.file) ?? []).filter(node => node.ruleId === "attribute_item" && node.range.byteOffset.start >= previousEnd && node.range.byteOffset.end <= item.start && /^#\[\s*serde\s*\(/.test(node.text));
    for (const attribute of attributes) for (const match of attribute.text.matchAll(/\b(deserialize_with|serialize_with|skip_serializing_if|default|with)\s*=\s*"([A-Za-z_]\w*(?:::[A-Za-z_]\w*)*)"/g)) {
      const names = match[1] === "with" ? [match[2] + "::serialize", match[2] + "::deserialize"] : [match[2]!];
      for (const name of names) {
        const targets = resolveName(name, item.file);
        for (const target of targets) mark(target, item.id, `exact serde ${match[1]} attribute helper: ${name}`);
        if (!targets.length) {
          const paths = projectPaths(name, item.file);
          if (paths.length) unresolved.set(`${item.id}:serde:${name}`, { from: item.id, file: item.file, expression: `serde ${match[1]}=${name}`, paths });
        }
      }
    }
  };
  const serializeType = (type: Declaration, from: string) => {
    if (!["struct_item", "enum_item"].includes(type.kind)) return;
    if (serialized.has(type.id)) return; serialized.add(type.id); mark(type, from, "complete typed serde schema");
    implicitMethods(type, ["Serialize", "Deserialize"], from, "implicit handwritten serde dispatch for actual typed schema");
    for (const member of byFile.get(type.file) ?? []) if (member.owner === type) {
      mark(member, type.id, "member of serialized/deserialized typed schema");
      for (const ref of (astByFile.get(member.file) ?? []).filter(node => ["type_identifier", "scoped_type_identifier"].includes(node.ruleId) && node.range.byteOffset.start >= member.start && node.range.byteOffset.end <= member.end)) for (const nested of resolveName(ref.text, member.file)) if (aggregates.has(nested.kind)) serializeType(nested, member.id);
    }
  };
  for (const item of declarations) {
    if (explicitRoots) { if (explicitRoots.some(root => item.file.startsWith(root + "/"))) mark(item, item.file, "native host/runtime root crate census"); }
    else if (crates.some(crate => item.file.startsWith(crate + "/")) || !applicationRootBoundary(item.file) && marker.test(item.file)) mark(item, item.file, "complete CodeMode crate/named module census");
    else if (!applicationRootBoundary(item.file) && marker.test(item.name)) mark(item, item.file, "explicit CodeMode declaration/member");
    // A caller is not an entrypoint of the API it consumes. In particular Session::new and the sampling loop
    // mention CodeMode while also configuring unrelated tools, analytics and model requests. Their declarations
    // remain in the census; only an actual call/import from one of the roots above can make them required.
    // The closure below follows those calls, including generic helper names, and retains unresolved imports.
  }
  for (let index = 0; index < queue.length; index++) {
    const item = queue[index]!;
    serdeHelpers(item);
    if (item.kind === "enum_variant") for (const child of byFile.get(item.file) ?? []) if (child.owner === item) mark(child, item.id, "schema member of an explicitly used enum variant");
    // Reaching a foreign aggregate's type name does not select all of its independent members.
    if (aggregates.has(item.kind)) continue;
    if (["impl_item", "mod_item"].includes(item.kind)) continue;
    const refs = (astByFile.get(item.file) ?? []).filter(node => node.range.byteOffset.start >= item.start && node.range.byteOffset.end <= item.end &&
      ["identifier", "type_identifier", "scoped_identifier", "scoped_type_identifier", "field_expression"].includes(node.ruleId));
    const ownerType = item.implType ?? (item.owner && aggregates.has(item.owner.kind) ? item.owner.name : undefined);
    const types = new Set<Declaration>();
    for (const ref of refs.filter(ref => ref.ruleId !== "field_expression")) {
      const name = ref.text.replace(/::<[^>]*>/g, "");
      if (!/^[A-Za-z_]\w*(?:::[A-Za-z_]\w*)*$/.test(name)) continue;
      const targets = resolveName(name, item.file, ownerType);
      const paths = targets.length ? [] : projectPaths(name, item.file, ownerType);
      if (paths.length && !refs.some(parent => parent !== ref && parent.ruleId.startsWith("scoped_") && parent.range.byteOffset.start <= ref.range.byteOffset.start && parent.range.byteOffset.end >= ref.range.byteOffset.end)) {
        const unknown: string[] = [];
        for (const path of paths) {
          const proof = generatedSymbols.get(path);
          if (proof) generated.set(`${item.id}:${path}`, { from: item.id, expression: name, path, kind: "tonic-prost-schema", ...proof });
          else unknown.push(path);
        }
        if (unknown.length) unresolved.set(`${item.id}:${name}`, { from: item.id, file: item.file, expression: name, paths: unknown });
      }
      for (const target of targets) {
        if (target.kind === "function_item" && !name.includes("::") && target.implType) continue;
        mark(target, item.id, `AST ${ref.ruleId}: ${name}`); if (aggregates.has(target.kind)) types.add(target);
        if (target.kind === "enum_variant") for (const member of (astByFile.get(target.file) ?? []).filter(node => node.ruleId === "type_identifier" && node.range.byteOffset.start >= target.start && node.range.byteOffset.end <= target.end)) for (const type of resolveName(member.text, target.file)) if (aggregates.has(type.kind)) types.add(type);
      }
    }
    // Imported types in the selected signature/receiver scope are the conservative candidate set.
    for (const name of identifiers(item.text.slice(0, item.text.indexOf("{")))) for (const type of resolveName(name, item.file, ownerType)) if (aggregates.has(type.kind)) types.add(type);
    if (ownerType) for (const type of resolveName(ownerType, item.file)) if (aggregates.has(type.kind)) types.add(type);
    const signatureTypes = (source: string, file: string) => [...new Set(identifiers(source).flatMap(name => resolveName(name, file, ownerType)).filter(type => aggregates.has(type.kind)))];
    const bindings = new Map<string, Declaration[]>();
    if (ownerType) bindings.set("self", signatureTypes(ownerType, item.file));
    for (const parameter of (astByFile.get(item.file) ?? []).filter(node => node.ruleId === "parameter" && node.range.byteOffset.start >= item.start && node.range.byteOffset.end <= item.end)) {
      const match = parameter.text.match(/^\s*(?:mut\s+)?([A-Za-z_]\w*)\s*:\s*([\s\S]*)/);
      if (match) bindings.set(match[1]!, [...new Set([...(bindings.get(match[1]!) ?? []), ...signatureTypes(match[2]!, item.file)])]);
    }
    const receiverTypes = (expression: string): Declaration[] | undefined => {
      const alias = expression.trim().match(/^(?:Arc|Rc|Box)::(?:clone|new)\s*\(\s*&?\s*([A-Za-z_]\w*)\s*\)$/);
      if (alias) return bindings.get(alias[1]!);
      const head = expression.match(/^\s*&?\s*([A-Za-z_]\w*)/); if (!head || !bindings.has(head[1]!)) return undefined;
      let found = bindings.get(head[1]!)!, offset = head[0].length;
      while (offset < expression.length) {
        if (expression[offset] !== ".") { offset++; continue; }
        const match = expression.slice(offset).match(/^\.\s*([A-Za-z_]\w*)/); if (!match) { offset++; continue; }
        const name = match[1]!; offset += match[0].length;
        while (/\s/.test(expression[offset] ?? "") && offset < expression.length) offset++;
        const method = expression[offset] === "(";
        if (method) { let depth = 0; do { if (expression[offset] === "(") depth++; if (expression[offset] === ")") depth--; offset++; } while (offset < expression.length && depth); }
        if (name === "await") continue;
        const members = found.flatMap(type => memberTargets(type, name)).filter(member => method ? ["function_item", "function_signature_item"].includes(member.kind) && /\([^)]*\bself\b/.test(member.text.slice(0, member.text.indexOf("{") < 0 ? undefined : member.text.indexOf("{"))) : member.kind === "field_declaration");
        if (method && !members.length) continue; // Mutex/Arc/Future wrappers retain their known generic payload.
        found = [...new Set(members.flatMap(member => signatureTypes(method ? member.text.slice(0, member.text.indexOf("{")).split("->")[1] ?? "" : member.text.slice(member.text.indexOf(":") + 1), member.file)))];
      }
      return found;
    };
    for (let pass = 0; pass < 3; pass++) for (const local of (astByFile.get(item.file) ?? []).filter(node => node.ruleId === "let_declaration" && node.range.byteOffset.start >= item.start && node.range.byteOffset.end <= item.end)) {
      const match = local.text.match(/^\s*let\s+(?:mut\s+)?([A-Za-z_]\w*)\s*(?::\s*([^=]+))?=\s*([\s\S]*);\s*$/); if (!match) continue;
      const inferred = match[2] ? signatureTypes(match[2], item.file) : receiverTypes(match[3]!);
      if (inferred) bindings.set(match[1]!, [...new Set([...(bindings.get(match[1]!) ?? []), ...inferred])]);
    }
    let previousSize = -1;
    while (previousSize !== types.size) {
      previousSize = types.size;
      for (const ref of refs.filter(ref => ref.ruleId === "field_expression")) {
        const member = ref.text.match(/\.\s*([A-Za-z_]\w*)\s*$/)?.[1]; if (!member) continue;
        const receiver = ref.text.slice(0, ref.text.lastIndexOf(".")), candidates = receiverTypes(receiver) ?? [...types];
        if (["sort", "sort_unstable", "binary_search"].includes(member)) for (const type of candidates)
          implicitMethods(type, ["Ord"], item.id, `implicit Ord dispatch of the actual ${member} receiver payload`);
        if (["into", "try_into"].includes(member)) for (const type of types)
          implicitMethods(type, ["From", "Into", "TryFrom", "TryInto"], item.id, `implicit conversion dispatch for a referenced destination of ${member}`);
        for (const type of candidates) for (const target of memberTargets(type, member)) {
          if (["function_item", "function_signature_item"].includes(target.kind) && !/\([^)]*\bself\b/.test(target.text.slice(0, target.text.indexOf("{") < 0 ? undefined : target.text.indexOf("{")))) continue;
          mark(target, item.id, `AST receiver member ${type.name}.${member}`);
          if (target.kind === "field_declaration") for (const nested of (astByFile.get(target.file) ?? []).filter(node => node.ruleId === "type_identifier" && node.range.byteOffset.start >= target.start && node.range.byteOffset.end <= target.end)) for (const type of resolveName(nested.text, target.file)) if (aggregates.has(type.kind)) types.add(type);
        }
      }
    }
    for (const type of types) for (const method of byFile.get(type.file) ?? []) {
      if (!implementsType(method, type) || method.owner?.kind !== "impl_item") continue;
      const header = method.owner.text.slice(0, method.owner.text.indexOf("{"));
      if (method.name === "fmt" && /(?:Display|Debug)\s+for\b/.test(header) && /\b(?:format|format_args|write|writeln|println|debug|trace|warn|error)\s*!|\.to_string\s*\(/.test(item.text)) mark(method, item.id, "formatting dispatch of referenced receiver type");
      if (["clone", "cmp", "partial_cmp", "hash", "eq"].includes(method.name) && new RegExp(`\\.${method.name}\\s*\\(`).test(item.text)) mark(method, item.id, "explicit trait method of referenced receiver type");
    }
    for (const literal of (astByFile.get(item.file) ?? []).filter(node => node.ruleId === "struct_expression" && node.range.byteOffset.start >= item.start && node.range.byteOffset.end <= item.end)) {
      const name = literal.text.slice(0,literal.text.indexOf("{")).trim().replace(/::<[^>]*>/g, "");
      for (const target of resolveName(name,item.file,ownerType)) for (const field of (astByFile.get(item.file) ?? []).filter(node => ["field_initializer", "shorthand_field_initializer"].includes(node.ruleId) && node.range.byteOffset.start >= literal.range.byteOffset.start && node.range.byteOffset.end <= literal.range.byteOffset.end)) {
        const member = field.text.match(/^\s*(\w+)/)?.[1]; if (!member) continue;
        for (const declaration of memberTargets(target,member)) if (declaration.kind === "field_declaration") mark(declaration,item.id, `AST constructed field ${name}.${member}`);
      }
    }
    for (const call of (astByFile.get(item.file) ?? []).filter(node => node.ruleId === "call_expression" && node.range.byteOffset.start >= item.start && node.range.byteOffset.end <= item.end)) {
      if (/\.\s*(?:into|try_into)\s*\(/.test(call.text)) {
        const name = call.text.match(/^\s*((?:[A-Za-z_]\w*::)*[A-Za-z_]\w*)\s*\(/)?.[1];
        for (const target of name ? resolveName(name, item.file, ownerType) : []) if (target.kind === "function_item") {
          for (const type of signatureTypes(target.text.slice(0,target.text.indexOf("{")), target.file)) implicitMethods(type, ["From", "Into", "TryFrom", "TryInto"], item.id, "implicit conversion of an actual called function's typed argument");
        }
      }
      const serde = call.text.match(/^serde_json\s*::\s*(?:to_value|to_string|from_value|from_str)(?:\s*::\s*<([\s\S]+)>)?\s*\(([\s\S]*)\)$/); if (!serde) continue;
      if (serde[1]) for (const name of identifiers(serde[1])) for (const type of resolveName(name, item.file, ownerType)) serializeType(type, item.id);
      const argument = serde[2]!.trim().replace(/^&\s*(?:mut\s+)?/, ""), field = argument.match(/\.\s*([A-Za-z_]\w*)$/)?.[1];
      if (field) for (const type of types) for (const member of byFile.get(type.file) ?? []) if (member.owner === type && member.kind === "field_declaration" && member.name === field) {
        for (const ref of (astByFile.get(member.file) ?? []).filter(node => ["type_identifier", "scoped_type_identifier"].includes(node.ruleId) && node.range.byteOffset.start >= member.start && node.range.byteOffset.end <= member.end)) for (const target of resolveName(ref.text, member.file)) serializeType(target, item.id);
      }
      if (/^[A-Za-z_]\w*$/.test(argument)) {
        if (argument === "self" && ownerType) for (const type of resolveName(ownerType, item.file)) serializeType(type, item.id);
        const signature = item.text.slice(0, item.text.indexOf("{")), parameterType = signature.match(new RegExp(`\\b${argument}\\s*:\\s*([^,)]*)`))?.[1];
        if (parameterType) for (const name of identifiers(parameterType)) for (const type of resolveName(name, item.file, ownerType)) serializeType(type, item.id);
      }
    }
  }
  const selectorsSha256 = sha256(JSON.stringify({ rootPolicy: "codemode-api-entrypoints-v3", applicationNonRoots: files.filter(file => applicationRootBoundary(file.path)).map(file => ({ path: file.path, sha256: sha256(file.source) })).sort((a,b)=>a.path.localeCompare(b.path)), roots: [...required].filter(id => incoming.get(id)?.some(link => link.relation.includes("census") || link.relation.includes("explicit CodeMode"))).sort(), files: files.map(file => ({ path: file.path, sha256: sha256(file.source) })).sort((a, b) => a.path.localeCompare(b.path)) }));
  const result = new Map<string, ForeignScope>();
  for (const item of declarations) result.set(item.id, { state: required.has(item.id) ? "required" : "foreign-context", algorithm: "ast-import-member-closure-v1", selectorsSha256,
    incoming: (incoming.get(item.id) ?? []).sort((a, b) => a.from.localeCompare(b.from) || a.relation.localeCompare(b.relation)), route: routes.get(item.id) ?? [],
    ...(applicationRootBoundary(item.file) ? { rootBoundary: { kind: "application-entrypoint-not-codemode-root" as const, fileSha256: sha256(files.find(file=>file.path===item.file)!.source), predicate: "Codex application startup, presentation and analytics consumers do not seed the deployed local CodeMode API closure. A direct active CodeMode call/import into this file is still required; this predicate cannot exclude such a helper or grant coverage PASS." } } : {}) });
  const representations = new Map<string, RepresentationScope>();
  for (const owner of declarations.filter(item => ["struct_item", "enum_item", "type_item"].includes(item.kind))) {
    if (/^\s*pub\s+(?:struct|enum|type)\b/.test(owner.text)) continue;
    const source = Buffer.from(files.find(file => file.path === owner.file)!.source);
    const previous = declarations.filter(item => item.file === owner.file && item.end <= owner.start).sort((a,b)=>b.end-a.end)[0];
    const attributes = source.subarray(Math.max(previous?.end ?? 0, owner.start-8000), owner.start).toString();
    const impls = declarations.filter(item => item.kind === "impl_item" && item.implType === owner.name);
    if (/\b(?:Serialize|Deserialize|JsonSchema|ToV8|FromV8|IntoV8)\b|#\[serde\b|#\[repr\s*\(/.test(attributes) || impls.some(item => /\b(?:Serialize|Deserialize|ToV8|FromV8|IntoV8)\b/.test(item.text.slice(0,item.text.indexOf("{")))) || /extern\s+"C"/.test(source.toString())) continue;
    const members = declarations.filter(item => item.file === owner.file && owner.start <= item.start && item.end <= owner.end &&
      ["struct_item", "enum_item", "type_item", "field_declaration", "enum_variant", "ordered_field_declaration_list"].includes(item.kind));
    const methods = declarations.filter(item => item.kind === "function_item" && required.has(item.id) &&
      (item.implType === owner.name || item.file === owner.file && (new RegExp(`\\b${owner.name}\\b`).test(item.text) || members.some(member => member.name && new RegExp(`\\.\\s*${member.name}\\b`).test(item.text)))));
    if (!methods.length) continue;
    const proof: RepresentationScope = { algorithm: "private-nonwire-layout-v1", owner: { id: owner.id, sha256: sha256(owner.text) },
      methods: methods.map(method => ({ id: method.id, sha256: sha256(method.text) })).sort((a,b)=>a.id.localeCompare(b.id)),
      predicate: "The containing Rust declaration has private/crate-restricted visibility, no serde/JSON/V8 conversion derive or implementation, no FFI layout attribute, and no C ABI. Its layout is implementation representation. Every selected method/accessor remains a separate required behavioral declaration; source fields receive no coverage PASS." };
    for (const member of members) representations.set(member.id, proof);
  }
  return { selections: result, unresolved: [...unresolved.values()].sort((a, b) => a.from.localeCompare(b.from) || a.expression.localeCompare(b.expression)),
    generated: [...generated.values()].sort((a,b)=>a.from.localeCompare(b.from)||a.path.localeCompare(b.path)), representations };
}
