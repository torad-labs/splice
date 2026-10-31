// The selector: one changed-file set in, the modules it affects out. Pre-commit, pre-push and the pull-request run all
// ask this file, so "what does this change touch" has one answer. Pure: callers read the diff, the module list, the
// module law and tools/gate/config/scope.json, and nothing here starts a process.
//
// A module is selected when a changed path is one of its files, or when it depends on a selected module. The edges are
// the module law, plus the main project dependencies an unlisted module's build script declares (project-deps.ts):
// :app is unlisted, and it is a dependent of the modules it names, not of every module. A path scope.json marks `full`
// selects every module, because every module's build reads it. A push whose every path is `docs` selects none.

import { docsRule, fullRule, type ScopeRules, repoScope } from "./scope.ts";

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

/** [changed] and every module that depends on one of them, directly or through others. A module the graph does not
 *  list depends on nothing: the caller adds the edges its build script declares before asking. */
export function withDependents(changed: ReadonlySet<string>, modules: readonly string[], graph: ModuleGraph): string[] {
  const selected = new Set(changed);
  let grew = changed.size > 0;
  while (grew) {
    grew = false;
    for (const module of modules) {
      if (selected.has(module)) continue;
      const deps = graph.get(module);
      if (deps !== undefined && deps.some((dep) => selected.has(dep))) {
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
  /** True when a scope.json `full` path changed, so every module is selected. */
  readonly full: boolean;
  /** True when every changed path is prose no law reads, so no gradle module runs. */
  readonly docsOnly: boolean;
  /** Why [full] is set: the path and the rule's reason. Absent otherwise. */
  readonly why?: string;
}

export interface SelectInput {
  readonly changed: readonly string[];
  readonly modules: readonly string[];
  readonly moduleOf: (path: string) => string | undefined;
  readonly graph: ModuleGraph;
  /** The scope rules. The committed file when omitted. */
  readonly scope?: ScopeRules;
}

/** The modules [changed] affects. */
export function select(input: SelectInput): Selection {
  const scope = input.scope ?? repoScope();
  const { changed } = input;
  const fullHit = changed.map((path) => ({ path, rule: fullRule(path, scope) })).find((hit) => hit.rule !== undefined);
  const docsOnly = changed.length > 0 && changed.every((path) => docsRule(path, scope) !== undefined);
  if (fullHit?.rule !== undefined) {
    return {
      modules: [...input.modules].sort(),
      full: true,
      docsOnly: false,
      why: `${fullHit.path} ${fullHit.rule.why}`,
    };
  }
  const touched = new Set<string>();
  for (const path of changed) {
    const module = input.moduleOf(path);
    if (module !== undefined) touched.add(module);
  }
  return { modules: withDependents(touched, input.modules, input.graph), full: false, docsOnly };
}

/** Whether [path] is prose no law reads, by the committed scope file. */
export function isDocsOnly(path: string): boolean {
  return docsRule(path) !== undefined;
}
