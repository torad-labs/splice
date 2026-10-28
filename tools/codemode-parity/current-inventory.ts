import { mkdtemp, mkdir, readFile, writeFile, rm } from "node:fs/promises";
import { dirname, relative, resolve } from "node:path";
import { tmpdir } from "node:os";
import { sha256 } from "./qualification";
import { resolveCurrentSource } from "./current-source";
import { foreignMemberScope, type ForeignScope, type ForeignScopeIssue, type GeneratedScopeResolution, type RepresentationScope, type CompilerScopeTrace, type CompilerScopeFallback } from "./current-foreign-scope";
import { projectReferenceTargets } from "./current-project-references";
import { compilerScope, loadCompilerScopeIndex, unstagedCompilerFiles, type CompilerScope, type CompilerScopeIdentity } from "./current-compiler-scope";

export type SourceUnit = {
  id: string; file: string; kind: string; label: string; line: number;
  start: number; end: number; sha256: string; ownership: "native-host" | "ported";
  requiredBy?: string[];
  foreignScope?: ForeignScope;
  representation?: RepresentationScope;
  nativeScope?: ForeignScope;
};
export type SourceFile = { path: string; gitBlob: string; sha256: string; discovery: "crate" | "module" | "consumer" | "dependency"; units: number };
export type CurrentInventory = {
  schema: 2; generator: string; sourceRevision: string; sourceTree: string; sourceCargoLockSha256: string;
  astGrepVersion: string; discoveredCrates: string[]; files: SourceFile[]; units: SourceUnit[];
  grpcMethods: string[]; globalHelpers: string[]; denominatorSha256: string;
  foreignScopeDiagnostics?: ForeignScopeIssue[];
  generatedScopeResolutions?: GeneratedScopeResolution[];
  compilerBindings?: CompilerScopeIdentity;
  compilerScopeResolutions?: CompilerScopeTrace[];
  compilerScopeFallbacks?: CompilerScopeFallback[];
};
const marker = /code[_-]mode|CodeMode|codeMode/;
const testPath = /(?:^|\/)(?:tests?|benches|examples)(?:\/|\.)|(?:_tests|_test|_test_support|test_support)\.rs$/;
const kinds = ["function_item", "function_signature_item", "struct_item", "enum_item", "enum_variant", "field_declaration",
  "ordered_field_declaration_list", "type_item", "const_item", "static_item", "trait_item", "mod_item", "macro_definition", "impl_item"];
const jsKinds = ["function_declaration", "function_expression", "method_definition", "arrow_function", "class_declaration", "variable_declarator"];
const tsKinds = ["interface_declaration", "type_alias_declaration", "property_signature", "method_signature", "function_signature", "enum_declaration"];
type GitFile = { path: string; blob: string; source: string };
type Match = { ruleId: string; file: string; text: string; range: { byteOffset: { start: number; end: number }; start: { line: number } } };

/** Keep one immutable byte buffer per scanned file instead of repeated text for every nested AST node. */
class SourceMatch implements Match {
  readonly range: Match["range"];
  constructor(readonly ruleId: string, readonly file: string, private readonly source: Buffer, start: number, end: number, line: number) {
    this.range = { byteOffset: { start, end }, start: { line } };
  }
  get text(): string { return this.source.subarray(this.range.byteOffset.start, this.range.byteOffset.end).toString("utf8"); }
}

/** JSONL bounds parsing memory by one match; every row is checked against its exact immutable source bytes. */
async function extractMatches(repo: string, sources: Map<string, Buffer>): Promise<Match[]> {
  if (!sources.size) return [];
  const scan = Bun.spawn([resolve(repo, "node_modules/.bin/ast-grep"), "scan", "--inline-rules", astRules(), "--json=stream", "--no-ignore", "hidden", ...sources.keys()], { cwd: repo, stdout: "pipe", stderr: "pipe" });
  const matches: Match[] = [], ruleIds = new Map<string, string>();
  const accept = (line: string) => {
    if (!line.trim()) return;
    const raw: Match = JSON.parse(line), source = sources.get(raw.file);
    if (!source) throw new Error(`AST extraction returned an unstaged source: ${raw.file}`);
    const { start, end } = raw.range.byteOffset;
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end < start || end > source.length ||
      !Number.isSafeInteger(raw.range.start.line) || raw.range.start.line < 0 ||
      source.subarray(start, end).toString("utf8") !== raw.text) throw new Error(`AST extraction changed immutable bytes: ${raw.file}@${start}`);
    const ruleId = ruleIds.get(raw.ruleId) ?? raw.ruleId; ruleIds.set(ruleId, ruleId);
    matches.push(new SourceMatch(ruleId, raw.file, source, start, end, raw.range.start.line));
  };
  const consume = async () => {
    const reader = scan.stdout.getReader(), decoder = new TextDecoder(); let pending = "";
    try {
      for (;;) {
        const chunk = await reader.read(); if (chunk.done) break;
        const text = pending + decoder.decode(chunk.value, { stream: true }); let start = 0, newline: number;
        while ((newline = text.indexOf("\n", start)) >= 0) { accept(text.slice(start, newline)); start = newline + 1; }
        pending = text.slice(start);
      }
      pending += decoder.decode(); if (pending) accept(pending);
    } finally { reader.releaseLock(); }
  };
  try {
    const [, stderr, exit] = await Promise.all([consume(), new Response(scan.stderr).text(), scan.exited]);
    if (exit !== 0) throw new Error(`reference AST extraction failed: ${stderr}`);
    return matches;
  } finally { if (scan.exitCode === null) { scan.kill(); await scan.exited; } }
}
/** Imported source helpers can carry CodeMode behavior without mentioning CodeMode in their own names. */
function dependencyClosure(blobs: GitFile[], primary: GitFile[]): Map<string, { names: Set<string>; owners: Set<"native-host" | "ported">; roots: Set<string> }> {
  const manifests = blobs.filter(file => /\/Cargo\.toml$/.test(file.path)).map(file => ({ root: dirname(file.path), name: file.source.match(/\bname\s*=\s*"([^"]+)"/)?.[1]?.replaceAll("-", "_") }));
  const owner = (path: string) => manifests.filter(crate => path.startsWith(crate.root + "/")).sort((left, right) => right.root.length - left.root.length)[0];
  const index = new Map<string, Array<{ file: GitFile; kind: string; name: string }>>();
  for (const file of blobs.filter(file => file.path.endsWith(".rs") && !testPath.test(file.path))) {
    const crate = owner(file.path); if (!crate) continue;
    for (const declaration of file.source.matchAll(/\b(fn|struct|enum|type|trait|const)\s+([A-Za-z_]\w*)/g)) {
      const key = `${crate.root}:${declaration[2]}`; const entries = index.get(key) ?? [];
      entries.push({ file, kind: declaration[1]!, name: declaration[2]! }); index.set(key, entries);
    }
  }
  const found = new Map<string, { names: Set<string>; owners: Set<"native-host" | "ported">; roots: Set<string> }>();
  const queue: Array<{ file: GitFile; ownership: "native-host" | "ported"; root: string; onlyImport?: string }> = primary.map(file => ({ file, ownership: nativeOwnership(file.path) ? "native-host" as const : "ported" as const, root: file.path }));
  const seen = new Set<string>();
  while (queue.length) {
    const { file, ownership, root, onlyImport } = queue.shift()!; const visited = `${file.path}:${ownership}:${root}:${onlyImport ?? "all"}`; if (seen.has(visited)) continue; seen.add(visited);
    const imports = [...file.source.matchAll(/(?:^|\n)\s*(?:pub(?:\([^)]*\))?\s+)?use\s+([^;]+);/g)].map(statement => statement[1]!.trim());
    const useImports = new Set(imports);
    // A fully qualified helper call need not have a use declaration. Discover
    // its exact module before AST member selection; never search every same-name
    // constructor in the crate as an approximation of a qualified path.
    for (const match of file.source.matchAll(/\b(?:crate|codex_\w+)(?:::[A-Za-z_]\w*)+/g)) {
      const parts = match[0].split("::"), crate = parts[0] === "crate" ? owner(file.path) : manifests.find(crate => crate.name === parts[0]);
      if (!crate) continue;
      const base = `${crate.root}/src/${parts.slice(1,-1).join("/")}`;
      if (blobs.some(candidate => candidate.path === base + ".rs" || candidate.path === base + "/mod.rs" || parts.length === 2 && candidate.path === `${crate.root}/src/lib.rs`)) imports.push(match[0]);
    }
    for (const importPath of [...new Set(imports)]) {
      const prefix = importPath.split("::")[0]!;
      if (onlyImport && !new RegExp(`\\b${onlyImport}\\b`).test(importPath)) continue;
      const crate = prefix === "crate" ? owner(file.path) : manifests.find(crate => crate.name === prefix);
      if (!crate) continue;
      const entries = importPath.includes("{") ? importPath.slice(importPath.indexOf("{") + 1, importPath.lastIndexOf("}")).split(",") : [importPath];
      const moduleParts = importPath.replace(/::\{[\s\S]*/, "").split("::").slice(1, importPath.includes("{") ? undefined : -1);
      const moduleBase = `${crate.root}/src/${moduleParts.join("/")}`;
      const moduleFile = blobs.find(candidate => candidate.path === moduleBase + ".rs" || candidate.path === moduleBase + "/mod.rs" || !moduleParts.length && candidate.path === `${crate.root}/src/lib.rs`);
      for (const imported of entries) {
        const name = imported.trim().split(/\s+as\s+/)[0]!.split("::").at(-1)!;
        if (!/^[A-Za-z_]\w*$/.test(name)) continue;
        const candidates = index.get(`${crate.root}:${name}`) ?? [];
        const direct = moduleFile ? candidates.filter(candidate => candidate.file.path === moduleFile.path) : [];
        const declarations = !useImports.has(importPath) && direct.length ? direct : candidates;
        if (moduleFile && !primary.some(item => item.path === moduleFile.path) &&
          new RegExp(`(?:^|\\n)\\s*(?:pub(?:\\([^)]*\\))?\\s+)?use\\s+[^;]*\\b${name}\\b[^;]*;`).test(moduleFile.source)) {
          const item = found.get(moduleFile.path) ?? { names: new Set<string>(), owners: new Set<"native-host" | "ported">(), roots: new Set<string>() };
          item.names.add(name); item.owners.add(ownership); item.roots.add(root); found.set(moduleFile.path, item); queue.push({ file: moduleFile, ownership, root, onlyImport: name });
        }
        for (const declaration of declarations) {
          // Keep this initial file-wide lexical pass narrow. The AST fixed point
          // below discovers every actually referenced project type regardless
          // of crate; this heuristic cannot excuse an unresolved reference.
          if (declaration.kind !== "fn" && !/^codex_(?:code_mode|protocol|tools|utils_)/.test(crate.name ?? "")) continue;
          if (primary.some(item => item.path === declaration.file.path)) continue;
          const item = found.get(declaration.file.path) ?? { names: new Set<string>(), owners: new Set<"native-host" | "ported">(), roots: new Set<string>() };
          item.names.add(name); item.owners.add(ownership); item.roots.add(root); found.set(declaration.file.path, item);
          queue.push({ file: declaration.file, ownership, root });
        }
      }
    }
  }
  return found;
}

async function command(argv: string[], cwd: string, input?: string): Promise<Buffer> {
  const child = Bun.spawn(argv, { cwd, stdin: input === undefined ? "ignore" : "pipe", stdout: "pipe", stderr: "pipe" });
  if (input !== undefined && child.stdin && typeof child.stdin !== "number") { child.stdin.write(input); child.stdin.end(); }
  const [bytes, error, code] = await Promise.all([new Response(child.stdout).arrayBuffer(), new Response(child.stderr).text(), child.exited]);
  if (code !== 0) throw new Error(`${argv[0]} failed (${code}): ${error.trim()}`);
  return Buffer.from(bytes);
}

/** Read immutable Git objects, never the potentially old/different checked-out source. */
async function sourceFiles(sourceRepo: string, revision: string): Promise<GitFile[]> {
  const tree = (await command(["git", "ls-tree", "-rz", "-r", revision, "codex-rs"], sourceRepo)).toString("utf8");
  const entries = tree.split("\0").filter(Boolean).map(row => {
    const [metadata, path] = row.split("\t"); const [mode, type, blob] = metadata!.split(" ");
    return { path: path!, blob: blob!, type, mode };
  }).filter(item => item.type === "blob" && /(?:\.(?:rs|js|proto)|Cargo\.toml|Cargo\.lock)$/.test(item.path));
  const bytes = await command(["git", "cat-file", "--batch"], sourceRepo, entries.map(item => item.blob).join("\n") + "\n");
  let offset = 0;
  return entries.map(entry => {
    const newline = bytes.indexOf(10, offset); if (newline < 0) throw new Error("incomplete git blob header");
    const [blob, type, length] = bytes.subarray(offset, newline).toString().split(" ");
    const size = Number(length); if (blob !== entry.blob || type !== "blob" || !Number.isSafeInteger(size)) throw new Error(`invalid Git blob ${entry.path}`);
    offset = newline + 1; const source = bytes.subarray(offset, offset + size).toString("utf8"); offset += size + 1;
    return { ...entry, source };
  });
}

function unitLabel(kind: string, text: string): string {
  if (["function_item", "function_signature_item"].includes(kind)) return text.match(/\bfn\s+([\w$]+)/)?.[1] ?? "";
  if (kind === "field_declaration") return text.match(/^(?:pub(?:\([^)]*\))?\s+)?([\w$]+)\s*:/)?.[1] ?? text.trim().split("\n")[0]!;
  if (kind === "enum_variant") return text.match(/^\s*([\w$]+)/)?.[1] ?? "";
  const declared = text.match(/\b(?:fn|struct|enum|type|const|static|trait|mod|macro_rules!|function|class)\s+([\w$]+)/)?.[1];
  return declared ?? text.trim().split(/\n|\{|=>/)[0]!.trim().slice(0, 140);
}
export function astRules(): string {
  return [["Rust", [...kinds, "attribute_item", "identifier", "type_identifier", "scoped_identifier", "scoped_type_identifier", "field_expression", "call_expression", "use_declaration", "parameter", "let_declaration", "struct_expression", "field_initializer", "shorthand_field_initializer"]], ["JavaScript", jsKinds], ["TypeScript", tsKinds]].flatMap(([language, entries]) => (entries as string[]).map(kind =>
    JSON.stringify({ id: kind, language, severity: "hint", message: kind, rule: { kind } }))).join("\n---\n");
}
function nativeOwnership(file: string): boolean {
  // The client adapter crate is NOT in the host binary. Its remote/gRPC logic must be ported/probed.
  return /^codex-rs\/code-mode-(?:host|runtime)\//.test(file) ||
    /^codex-rs\/code-mode-protocol\//.test(file) && !/\/(?:description|json_schema_types)\.rs$/.test(file);
}
/** The public session/delegate facade is shared with the ported frontend, even when a native backend calls it. */
export function requiresPortedSessionContract(unit: Pick<SourceUnit,"file"|"kind"|"start"|"end">,
  declarations: readonly Pick<Match,"ruleId"|"text"|"range">[]): boolean {
  return unit.file==="codex-rs/code-mode-protocol/src/session.rs"&&unit.kind==="function_signature_item"&&declarations.some(owner=>
    owner.ruleId==="trait_item"&&/^\s*pub\s+(?:unsafe\s+)?trait\b/.test(owner.text)&&owner.range.byteOffset.start<=unit.start&&owner.range.byteOffset.end>=unit.end);
}
export function inventoryDigest(inventory: Pick<CurrentInventory, "sourceRevision" | "sourceTree" | "files" | "units" | "grpcMethods" | "globalHelpers" | "foreignScopeDiagnostics" | "generatedScopeResolutions" | "compilerBindings" | "compilerScopeResolutions" | "compilerScopeFallbacks">): string {
  return sha256(JSON.stringify({ sourceRevision: inventory.sourceRevision, sourceTree: inventory.sourceTree, files: inventory.files,
    units: inventory.units, grpcMethods: inventory.grpcMethods, globalHelpers: inventory.globalHelpers, foreignScopeDiagnostics: inventory.foreignScopeDiagnostics ?? [], generatedScopeResolutions: inventory.generatedScopeResolutions ?? [],
    ...(inventory.compilerBindings ? { compilerBindings: inventory.compilerBindings, compilerScopeResolutions: inventory.compilerScopeResolutions ?? [], compilerScopeFallbacks: inventory.compilerScopeFallbacks ?? [] } : {}) }));
}

export type InventoryOptions = { compilerBindings?: string; expectedCompilerProofSha256?: string };

/** Scope is discovered from the entire reference object tree, not a hand maintained module list. */
export async function generateCurrentInventory(sourceRepo: string, revision: string, repo = resolve(import.meta.dir, "../.."), progress?: (event: Record<string, number | string>) => void, options: InventoryOptions = {}): Promise<CurrentInventory> {
  if (!/^[a-f0-9]{40}$/.test(revision)) throw new Error("current inventory requires an exact source commit");
  if (options.expectedCompilerProofSha256 && !options.compilerBindings) throw new Error("inventory reproduction requires explicit --compiler-bindings <proof-manifest>");
  const [tree, blobs, astVersion] = await Promise.all([
    command(["git", "rev-parse", `${revision}^{tree}`], sourceRepo), sourceFiles(sourceRepo, revision),
    command([resolve(repo, "node_modules/.bin/ast-grep"), "--version"], repo),
  ]);
  const sourceIdentity = { sourceRevision: revision, sourceTree: tree.toString().trim(),
    sourceCargoLockSha256: sha256(blobs.find(file => file.path === "codex-rs/Cargo.lock")?.source ?? "") };
  const immutable = new Map(blobs.filter(file => file.path.endsWith(".rs")).map(file => [file.path, { source: file.source, sha256: sha256(file.source) }]));
  progress?.({ phase: "compiler-proof", enabled: options.compilerBindings ? 1 : 0 });
  const compilerIndex = options.compilerBindings ? await loadCompilerScopeIndex(options.compilerBindings, sourceIdentity, immutable, options.expectedCompilerProofSha256) : undefined;
  const crates = blobs.filter(file => /\/Cargo\.toml$/.test(file.path) && /name\s*=\s*"[^"]*code-mode[^"]*"/.test(file.source))
    .map(file => dirname(file.path)).sort();
  if (!crates.length) throw new Error("reference has no discovered CodeMode crates");
  const selected = blobs.filter(file => /\.(?:rs|js|proto)$/.test(file.path) && !testPath.test(file.path) &&
    (crates.some(crate => file.path.startsWith(crate + "/")) || marker.test(file.path) || marker.test(file.source)));
  const dependencies = dependencyClosure(blobs, selected.filter(file => crates.some(crate => file.path.startsWith(crate + "/")) || marker.test(file.path)));
  for (const file of blobs) if (dependencies.has(file.path) && !selected.some(item => item.path === file.path)) selected.push(file);
  const temporary = await mkdtemp(resolve(tmpdir(), "splice-reference-inventory-"));
  const embedded: Array<{ file: string; path: string; offset: number }> = [];
  const files: SourceFile[] = [], units: SourceUnit[] = [], grpcMethods: string[] = [], globalHelpers: string[] = [];
  try {
    const manifests = blobs.filter(file => /\/Cargo\.toml$/.test(file.path)), staged = new Set<string>();
    const matches: Match[] = [], matchesByFile = new Map<string, Match[]>();
    let semanticMatches: Match[] = [];
    let foreignSelection: ReturnType<typeof foreignMemberScope>;
    let compiler: CompilerScope | undefined, compilerPhase = false;
    for (;;) {
    Bun.gc(true);
    progress?.({phase:"extract",files:selected.length,temporary});
    const scanSources = new Map<string, Buffer>();
    for (const file of selected.filter(file => !staged.has(file.path))) {
      staged.add(file.path);
      const destination = resolve(temporary, file.path); await mkdir(dirname(destination), { recursive: true }); await writeFile(destination, file.source);
      if (/\.(?:rs|js)$/.test(file.path)) scanSources.set(destination, Buffer.from(file.source));
      if (file.path.endsWith(".rs")) for (const literal of file.source.matchAll(/r(#{0,16})"([\s\S]*?)"\1/g)) {
        if (!/\b(?:type|interface|declare)\s+\w/.test(literal[2]!)) continue;
        const offset = Buffer.byteLength(file.source.slice(0, literal.index! + 2 + literal[1]!.length));
        const path = resolve(temporary, `${file.path}.embedded-${offset}.ts`);
        await writeFile(path, literal[2]!); embedded.push({ file: file.path, path, offset }); scanSources.set(path, Buffer.from(literal[2]!));
      }
    }
    for (const item of await extractMatches(repo, scanSources)) {
      matches.push(item); const found = matchesByFile.get(item.file) ?? []; found.push(item); matchesByFile.set(item.file, found);
    }
    progress?.({phase:"resolve",files:selected.length,astNodes:matches.length,newFiles:scanSources.size,rss:process.memoryUsage().rss});
    const testRanges = matches.filter(item => item.ruleId === "mod_item" && (/^\s*(?:pub\s+)?mod\s+(?:tests|test)\b/.test(item.text) ||
      /#\[cfg\(test\)\]\s*$/.test(Buffer.from(selected.find(file => file.path === relative(temporary, item.file))?.source ?? "").subarray(Math.max(0, item.range.byteOffset.start - 100), item.range.byteOffset.start).toString())));
    semanticMatches = matches.filter(item => !testRanges.some(test => test.file === item.file && test.range.byteOffset.start <= item.range.byteOffset.start && test.range.byteOffset.end >= item.range.byteOffset.end));
    if (compilerPhase && compilerIndex) compiler = compilerScope(compilerIndex, sourceIdentity, immutable, [
      ...selected.filter(file => file.path.endsWith(".rs")).map(file => ({ id: `${file.path}#source-file@0`, file: file.path, kind: "source-file", start: 0, end: Buffer.byteLength(file.source), sha256: sha256(file.source) })),
      ...semanticMatches.filter(item => item.file.endsWith(".rs") && kinds.includes(item.ruleId)).map(item => ({ id: `${relative(temporary,item.file)}#${item.ruleId}@${item.range.byteOffset.start}`,
        file: relative(temporary,item.file), kind: item.ruleId, start: item.range.byteOffset.start, end: item.range.byteOffset.end, sha256: sha256(item.text) })),
    ]);
    foreignSelection = foreignMemberScope(selected, semanticMatches, temporary, crates, manifests, undefined, progress, compiler);
    const targets = projectReferenceTargets(blobs, foreignSelection.unresolved).filter(target => !staged.has(target.file.path));
    for (const target of unstagedCompilerFiles(foreignSelection.compiler, immutable)) {
      if (staged.has(target.file)) continue;
      const file = blobs.find(file => file.path === target.file)!;
      const existing = targets.find(target => target.file.path === file.path);
      if (existing) existing.requiredBy = [...new Set([...existing.requiredBy, ...target.requiredBy])].sort();
      else targets.push({ file, symbols: [], requiredBy: target.requiredBy });
    }
    progress?.({phase:"discover",files:selected.length,unresolved:foreignSelection.unresolved.length,additionalFiles:targets.length});
    // Complete and retain the original AST file census before more precise compiler choices replace guesses.
    if (!targets.length && compilerIndex && !compilerPhase) { compilerPhase = true; continue; }
    if (!targets.length) break;
    for (const target of targets) {
      const file = blobs.find(file => file.path === target.file.path)!;
      selected.push(file);
      dependencies.set(file.path, { names: new Set(target.symbols), owners: new Set(target.requiredBy.map(path => nativeOwnership(path) ? "native-host" as const : "ported" as const)), roots: new Set(target.requiredBy) });
    }
    semanticMatches = [];
    }
    const nativeSelection = foreignMemberScope(selected, semanticMatches, temporary, crates, manifests, crates.filter(crate => /code-mode-(?:host|runtime)$/.test(crate)), undefined, compiler);
    const foreignScopes = foreignSelection.selections;
    for (const file of selected.sort((left, right) => left.path.localeCompare(right.path))) {
      const sourceBytes = Buffer.from(file.source);
      const discovery = crates.some(crate => file.path.startsWith(crate + "/")) ? "crate" : marker.test(file.path) ? "module" : dependencies.has(file.path) ? "dependency" : "consumer";
      const before = units.length;
      const found = matchesByFile.get(resolve(temporary, file.path)) ?? [];
      const tests = found.filter(item => item.ruleId === "mod_item" && (/^\s*(?:pub\s+)?mod\s+(?:tests|test)\b/.test(item.text) ||
        /#\[cfg\(test\)\]\s*$/.test(sourceBytes.subarray(Math.max(0, item.range.byteOffset.start - 100), item.range.byteOffset.start).toString())));
      const scoped = found.filter(item => (kinds.includes(item.ruleId) || jsKinds.includes(item.ruleId)) && !tests.some(test => test.range.byteOffset.start <= item.range.byteOffset.start && test.range.byteOffset.end >= item.range.byteOffset.end));
      const roots = scoped.filter(item => (marker.test(item.text) || dependencies.get(file.path)?.names.has(unitLabel(item.ruleId, item.text))) && item.ruleId !== "impl_item" && item.ruleId !== "mod_item");
      // Follow helper calls within a dependency file as well as its imported public entrypoint.
      if (discovery === "dependency") for (let index = 0; index < roots.length; index++) {
        for (const candidate of scoped) if (["function_item", "const_item", "static_item", "type_item", "struct_item", "enum_item", "impl_item"].includes(candidate.ruleId) && !roots.includes(candidate)) {
          const name = unitLabel(candidate.ruleId, candidate.text);
          if (candidate.ruleId === "impl_item") {
            if (["struct_item", "enum_item", "type_item"].includes(roots[index]!.ruleId) && new RegExp(`\\b${unitLabel(roots[index]!.ruleId, roots[index]!.text)}\\b`).test(candidate.text.slice(0, candidate.text.indexOf("{")))) roots.push(candidate);
          } else if (/^[A-Za-z_]\w*$/.test(name) && new RegExp(candidate.ruleId === "function_item" ? `(?<![.\\w])${name}\\s*\\(` : `\\b${name}\\b`).test(roots[index]!.text)) roots.push(candidate);
        }
      }
      const covers = (root: Match, item: Match) => {
        if (root.range.byteOffset.start > item.range.byteOffset.start || root.range.byteOffset.end < item.range.byteOffset.end) return false;
        // A shared Config/Feature enum can mention CodeMode without making its unrelated fields CodeMode behavior.
        if (["struct_item", "enum_item", "trait_item"].includes(root.ruleId) && root !== item &&
          !marker.test(unitLabel(root.ruleId, root.text))) return marker.test(item.text);
        return true;
      };
      const emit = (kind: string, text: string, start: number, end: number, line: number) => units.push({
        id: `${file.path}#${kind}@${start}`, file: file.path, kind, label: unitLabel(kind, text), line, start, end,
        sha256: sha256(text), ownership: !requiresPortedSessionContract({file:file.path,kind,start,end},scoped)&&((nativeOwnership(file.path) && (!/^codex-rs\/code-mode-protocol\/src\/.*\.rs$/.test(file.path) || nativeSelection.selections.get(`${file.path}#${kind}@${start}`)?.state === "required")) || discovery === "dependency" && dependencies.get(file.path)?.owners.size === 1 && dependencies.get(file.path)?.owners.has("native-host") && nativeSelection.selections.get(`${file.path}#${kind}@${start}`)?.state === "required") ? "native-host" : "ported",
        ...(discovery === "dependency" ? { requiredBy: [...dependencies.get(file.path)!.roots].sort() } : {}),
        ...(["dependency", "consumer"].includes(discovery) && foreignScopes.has(`${file.path}#${kind}@${start}`) ? { foreignScope: foreignScopes.get(`${file.path}#${kind}@${start}`)! } : {}),
        ...(foreignSelection.representations.has(`${file.path}#${kind}@${start}`) ? { representation: foreignSelection.representations.get(`${file.path}#${kind}@${start}`)! } : {}),
        ...(nativeSelection.selections.get(`${file.path}#${kind}@${start}`)?.state === "required" ? { nativeScope: nativeSelection.selections.get(`${file.path}#${kind}@${start}`)! } : {}),
      });
      // The file unit binds anonymous closures, embedded programs and schema attributes too.
      emit("source-file", file.source, 0, Buffer.byteLength(file.source), 1);
      for (const item of scoped) {
        // Retain all foreign Rust declarations in the census. Their exact AST
        // member applicability remains separate; a selected sibling does not
        // turn every foreign declaration into a porting/coverage obligation.
        if (!file.path.endsWith(".rs") && (discovery === "consumer" || discovery === "dependency") && !roots.some(root => covers(root, item)) &&
          foreignScopes.get(`${file.path}#${item.ruleId}@${item.range.byteOffset.start}`)?.state !== "required") continue;
        emit(item.ruleId, item.text, item.range.byteOffset.start, item.range.byteOffset.end, item.range.start.line + 1);
      }
      for (const literal of embedded.filter(item => item.file === file.path)) for (const item of matchesByFile.get(literal.path) ?? []) {
        const start = literal.offset + item.range.byteOffset.start;
        emit(`typescript-${item.ruleId}`, item.text, start, literal.offset + item.range.byteOffset.end, sourceBytes.subarray(0, start).toString().split("\n").length);
      }
      if (file.path.endsWith(".proto")) {
        // Proto is not supported by ast-grep. Its grammar's named schema members are enumerated separately.
        const clean = file.source.replace(/\/\*[\s\S]*?\*\//g, value => value.replace(/[^\n]/g, " ")).replace(/\/\/[^\n]*/g, value => " ".repeat(value.length));
        const declaration = /\b(rpc|message|enum|service|oneof)\s+(\w+)|\b(?:repeated\s+|optional\s+)?[.\w]+\s+(\w+)\s*=\s*\d+|\b(\w+)\s*=\s*\d+/g;
        for (const item of clean.matchAll(declaration)) {
          const kind = item[1] ? `proto-${item[1]}` : item[3] ? "proto-field" : "proto-enum-value";
          const label = item[2] ?? item[3] ?? item[4]!; const start = item.index!;
          emit(kind, item[0], start, start + item[0].length, clean.slice(0, start).split("\n").length);
          units.at(-1)!.label = label; if (kind === "proto-rpc") grpcMethods.push(label);
        }
      }
      if (/\/runtime\/globals\.rs$/.test(file.path)) {
        for (const item of file.source.matchAll(/(?:set_global|register_global|register_function)\([^\n]*?"([A-Za-z_$][\w$]*)"/g)) globalHelpers.push(item[1]!);
      }
      files.push({ path: file.path, gitBlob: file.blob, sha256: sha256(file.source), discovery, units: units.length - before });
    }
    units.sort((left, right) => left.id.localeCompare(right.id));
    const base = { sourceRevision: revision, sourceTree: tree.toString().trim(), files, units, foreignScopeDiagnostics: foreignSelection.unresolved, generatedScopeResolutions: foreignSelection.generated,
      ...(compiler ? { compilerBindings: { algorithm: compiler.algorithm, proofSha256: compiler.proofSha256, semanticDigest: compiler.semanticDigest }, compilerScopeResolutions: foreignSelection.compiler, compilerScopeFallbacks: foreignSelection.compilerFallbacks } : {}),
      grpcMethods: [...new Set(grpcMethods)].sort(), globalHelpers: [...new Set(globalHelpers)].sort() };
    return { schema: 2, generator: "git-object-tree+ast-grep-declarations+import-member-closure-v11", sourceCargoLockSha256: sourceIdentity.sourceCargoLockSha256,
      astGrepVersion: astVersion.toString().trim(), discoveredCrates: crates, ...base, denominatorSha256: inventoryDigest(base) };
  } finally { await rm(temporary, { recursive: true, force: true }); }
}

export function inventoryFindings(generated: CurrentInventory, committed: CurrentInventory): string[] {
  const findings: string[] = [];
  if (committed?.schema !== 2 || generated.sourceRevision !== committed.sourceRevision || generated.sourceTree !== committed.sourceTree) findings.push("current reference source revision/tree changed; regenerate and disposition the new reference");
  if (!Array.isArray(committed?.units) || !Array.isArray(committed?.files)) return [...findings, "malformed current source inventory"];
  const expected = new Set(generated.units.map(item => item.id)), recorded = new Set(committed.units.map(item => item.id));
  for (const id of expected) if (!recorded.has(id)) findings.push(`omitted reference unit: ${id}`);
  for (const id of recorded) if (!expected.has(id)) findings.push(`unknown reference unit: ${id}`);
  if (recorded.size !== committed.units.length) findings.push("duplicate reference inventory unit");
  if (generated.denominatorSha256 !== committed.denominatorSha256 || inventoryDigest(committed) !== committed.denominatorSha256) findings.push("reference denominator or source hashes changed; hand edited/shrunken inventories are forbidden");
  if (generated.astGrepVersion !== committed.astGrepVersion || generated.generator !== committed.generator) findings.push("reference inventory generator identity changed");
  if (generated.sourceCargoLockSha256 !== committed.sourceCargoLockSha256 || JSON.stringify(generated.discoveredCrates) !== JSON.stringify(committed.discoveredCrates)) findings.push("reference dependency lock or discovered crate inventory changed");
  if (JSON.stringify(generated.compilerBindings) !== JSON.stringify(committed.compilerBindings)) findings.push("explicit compiler binding proof or semantic source decisions changed");
  return findings;
}

if (import.meta.main) {
  const args = process.argv.slice(2), positional: string[] = []; let compilerBindings: string | undefined;
  for (let at = 0; at < args.length; at++) {
    if (args[at] === "--compiler-bindings") { if (compilerBindings || !args[at + 1] || args[at + 1]!.startsWith("--")) throw new Error("--compiler-bindings requires one explicit proof manifest"); compilerBindings = args[++at]; }
    else { if (args[at]!.startsWith("--")) throw new Error(`unknown inventory option ${args[at]}`); positional.push(args[at]!); }
  }
  if (positional.length > 2) throw new Error("usage: current-inventory.ts [source-repo] [destination] [--compiler-bindings <proof-manifest>]");
  const [sourceRepo, destination = resolve(import.meta.dir, "current-inventory.json")] = positional;
  const lock = JSON.parse(await readFile(resolve(import.meta.dir, "current-reference.json"), "utf8"));
  const inventory = await generateCurrentInventory(await resolveCurrentSource(lock, sourceRepo), lock.sourceRevision, undefined, event => console.error(JSON.stringify(event)), { compilerBindings });
  await writeFile(destination, JSON.stringify(inventory, null, 2) + "\n");
  console.log(`Generated ${inventory.units.length} units in ${inventory.files.length} files from ${inventory.discoveredCrates.length} discovered CodeMode crates; ${inventory.grpcMethods.length} RPCs; ${inventory.denominatorSha256}`);
}
