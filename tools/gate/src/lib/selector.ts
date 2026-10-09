// The selector: one changed-file set in, the checks it affects out. Pre-commit, pre-push and the pull-request run all ask
// this file, so "what does this change touch" has one answer. Pure: callers read the diff, the module list and the module
// law, and nothing here starts a process.
//
// A check runs when its inputs changed, and in full on main (the whole ladder, unchanged). Nothing is switched off:
//   - a Kotlin file selects its gradle module plus every module that depends on it, read from gradle/module-law.txt, the
//     same file the build enforces, so the dependents can never drift from the real graph;
//   - a build-wide input (settings, the root build, the gradle directory, build-logic, the version catalog, the detekt
//     and rule configuration, package.json and the lockfile) selects every module;
//   - a docs-only change selects no module and no gradle, only the fast text checks;
//   - ladder rows are selected by their own declared `inputs` (prepush-scope.ts), so a tools/gate file selects the gate's
//     tests and typecheck, and a rule file selects the rule tests and the ast-grep scan.

/** Paths every module's build or analysis reads, so a change to one selects the whole suite. */
const BUILD_WIDE = /^(settings\.gradle\.kts$|build\.gradle\.kts$|gradle\.properties$|gradle\/|build-logic\/|quality\/(detekt|rules|compiler-plugin|architecture)\/|package\.json$|bun\.lock$|bun\.lockb$)/;

/** Prose no law reads: a change confined to it leaves every gradle check and jar leg as it was. The files laws do read
 *  (the README, CHANGELOG, THIRD_PARTY_NOTICES, docs/PROVENANCE.md, the release runbook) are not in this set. */
const DOCS_ONLY = /^(docs\/(?!PROVENANCE\.md$)|\.dev\/research\/)/;

export const isBuildWide = (path: string): boolean => BUILD_WIDE.test(path);
export const isDocsOnly = (path: string): boolean => DOCS_ONLY.test(path);

/** The module law as a graph: each module to the project modules its main configurations may depend on. */
export type ModuleGraph = ReadonlyMap<string, readonly string[]>;

/** Parses gradle/module-law.txt, whose lines are `<module> -> <dep> <dep>`; `#` lines and blanks are skipped. */
export function parseModuleGraph(text: string): ModuleGraph {
  const graph = new Map<string, string[]>();
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (line === "" || line.startsWith("#")) continue;
    const arrow = line.indexOf("->");
    if (arrow < 0) continue;
    graph.set(line.slice(0, arrow).trim(), line.slice(arrow + 2).trim().split(/\s+/).filter((dep) => dep !== ""));
  }
  return graph;
}

/** [changed] and every module that depends on one of them, directly or through others. A module the law does not list is
 *  unrestricted (it may depend on anything), so it is a dependent of every module. */
export function withDependents(changed: ReadonlySet<string>, modules: readonly string[], graph: ModuleGraph): string[] {
  const selected = new Set(changed);
  let grew = changed.size > 0;
  while (grew) {
    grew = false;
    for (const module of modules) {
      if (selected.has(module)) continue;
      const deps = graph.get(module);
      if (deps === undefined || deps.some((dep) => selected.has(dep))) {
        selected.add(module);
        grew = true;
      }
    }
  }
  return [...selected].sort();
}

export interface Selection {
  /** The modules whose `check` the change affects, sorted. */
  readonly modules: readonly string[];
  /** True when a build-wide input changed, so every module is selected. */
  readonly full: boolean;
  /** True when every changed path is prose no law reads, so no gradle runs. */
  readonly docsOnly: boolean;
}

export interface SelectInput {
  readonly changed: readonly string[];
  readonly modules: readonly string[];
  readonly moduleOf: (path: string) => string | undefined;
  readonly graph: ModuleGraph;
}

export function select(input: SelectInput): Selection {
  const { changed } = input;
  const docsOnly = changed.length > 0 && changed.every(isDocsOnly);
  if (changed.some(isBuildWide)) return { modules: [...input.modules].sort(), full: true, docsOnly: false };
  const touched = new Set<string>();
  for (const path of changed) {
    const module = input.moduleOf(path);
    if (module !== undefined) touched.add(module);
  }
  return { modules: withDependents(touched, input.modules, input.graph), full: false, docsOnly };
}
