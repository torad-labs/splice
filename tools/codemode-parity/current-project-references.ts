import { dirname } from "node:path";
import type { ForeignScopeIssue, ScopeFile } from "./current-foreign-scope";

export type ProjectReferenceTarget = { file: ScopeFile; symbols: string[]; requiredBy: string[] };

/** Discovery is conservative; only the subsequent AST/name resolver can bind a reference. */
export function projectReferenceTargets(files: ScopeFile[], issues: ForeignScopeIssue[]): ProjectReferenceTarget[] {
  const crates = files.filter(file => /\/Cargo\.toml$/.test(file.path)).flatMap(file => {
    const name = file.source.match(/\bname\s*=\s*"([^"]+)"/)?.[1]?.replaceAll("-", "_");
    return name ? [{ root: dirname(file.path), name }] : [];
  });
  const paths = new Map(files.map(file => [file.path, file]));
  const index = new Map<string, ScopeFile[]>();
  for (const file of files.filter(file => file.path.endsWith(".rs") && !/(?:^|\/)(?:tests?|benches|examples)(?:\/|\.)|(?:_tests|_test|_test_support|test_support)\.rs$/.test(file.path))) {
    const crate = crates.filter(crate => file.path.startsWith(crate.root + "/")).sort((left, right) => right.root.length - left.root.length)[0];
    if (!crate) continue;
    for (const match of file.source.matchAll(/\b(?:fn|struct|enum|type|trait|const|static|macro_rules!)\s+([A-Za-z_]\w*)/g)) {
      const key = `${crate.name}::${match[1]}`, found = index.get(key) ?? [];
      if (!found.includes(file)) found.push(file);
      index.set(key, found);
    }
  }
  const selected = new Map<string, { file: ScopeFile; symbols: Set<string>; requiredBy: Set<string> }>();
  const add = (file: ScopeFile, symbol: string, from: string) => {
    const target = selected.get(file.path) ?? { file, symbols: new Set<string>(), requiredBy: new Set<string>() };
    target.symbols.add(symbol); target.requiredBy.add(from); selected.set(file.path, target);
  };
  for (const issue of issues) for (const path of issue.paths) {
    const from = issue.file;
    const [crateName, ...segments] = path.split("::"), crate = crates.find(crate => crate.name === crateName);
    if (!crate) continue;
    let moduleDepth = 0;
    const root = paths.get(`${crate.root}/src/lib.rs`); if (root) add(root, segments[0] ?? "", from);
    for (let depth = 1; depth <= segments.length; depth++) {
      const base = `${crate.root}/src/${segments.slice(0, depth).join("/")}`;
      const module = paths.get(`${base}.rs`) ?? paths.get(`${base}/mod.rs`);
      if (module) { add(module, segments[depth] ?? segments[depth - 1]!, from); moduleDepth = depth; }
    }
    // A crate-root reexport may hide the defining file. Include matching source
    // declarations, but never treat a global same-name match as a resolution.
    // Inspect the owner before an associated method; indexing every `new` in a
    // crate would invent unrelated dependencies for generated/external owners.
    const symbol = segments[moduleDepth];
    if (symbol) for (const file of index.get(`${crate.name}::${symbol}`) ?? []) add(file, symbol, from);
  }
  return [...selected.values()].map(target => ({ file: target.file, symbols: [...target.symbols].sort(), requiredBy: [...target.requiredBy].sort() })).sort((left, right) => left.file.path.localeCompare(right.file.path));
}
