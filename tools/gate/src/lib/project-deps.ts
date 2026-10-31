// The project dependencies a module's build script declares. The module law leaves :app unlisted on purpose (it may
// depend on anything the architecture has not pinned). The selector used to treat "unlisted" as "depends on every
// module", so every push checked :app. The script is the dependency. Main configurations only, the same set the
// module law enforces: a testImplementation does not make this module a dependent of that project for a main change.

import type { ModuleGraph } from "./selector.ts";

/** The configurations the module law checks. A dependency in one of these is a real dependent edge. */
const MAIN = new Set(["api", "implementation", "compileOnly", "runtimeOnly"]);

/** Wrappers around a project() call that are not themselves the configuration. */
const WRAPPERS = new Set(["project", "testFixtures", "files", "fileTree"]);

const PROJECT_CALL = /project\s*\(/g;

/** The project paths [script] depends on through a main configuration, sorted and unique. */
export function declaredProjectDeps(script: string): string[] {
  const found = new Set<string>();
  for (const match of script.matchAll(PROJECT_CALL)) {
    const open = (match.index ?? 0) + match[0].length - 1;
    const config = configurationOf(script, open);
    if (config === undefined || !MAIN.has(config)) continue;
    const path = projectPath(argsOf(script, open));
    if (path !== undefined) found.add(path);
  }
  return [...found].sort();
}

/**
 * [graph] plus an edge for every [modules] entry the law does not list, taken from the build script [scriptOf]
 * returns. A module with no script depends on no project. An unlisted module is never given an edge to every module.
 */
export function withBuildDeps(
  modules: readonly { readonly path: string; readonly dir: string }[],
  graph: ModuleGraph,
  scriptOf: (module: { readonly path: string; readonly dir: string }) => string | undefined,
): ModuleGraph {
  const next = new Map<string, readonly string[]>(graph);
  for (const module of modules) {
    if (next.has(module.path)) continue;
    const text = scriptOf(module);
    next.set(module.path, text === undefined ? [] : declaredProjectDeps(text));
  }
  return next;
}

/** The configuration call wrapped around the `(` at [open], skipping project() and testFixtures(). */
function configurationOf(script: string, open: number): string | undefined {
  let at = open;
  const seen = new Set<number>();
  while (!seen.has(at)) {
    seen.add(at);
    let cursor = at - 1;
    while (cursor >= 0 && /\s/.test(script[cursor] ?? "")) cursor--;
    const name = /([A-Za-z_][\w]*)$/.exec(script.slice(0, cursor + 1));
    if (name === null) return undefined;
    const ident = name[1];
    if (ident === undefined) return undefined;
    if (!WRAPPERS.has(ident)) return ident;
    let before = name.index - 1;
    while (before >= 0 && /\s/.test(script[before] ?? "")) before--;
    if (before < 0 || script[before] !== "(") return undefined;
    at = before;
  }
  return undefined;
}

/** The text inside the paren that opens at [open]. */
function argsOf(script: string, open: number): string {
  let depth = 0;
  for (let at = open; at < script.length; at++) {
    const ch = script[at];
    if (ch === "(") depth++;
    else if (ch === ")" && --depth === 0) return script.slice(open + 1, at);
  }
  return "";
}

/** The project path a project() argument list names, or undefined when it names none. */
function projectPath(args: string): string | undefined {
  return /(?:^|path\s*=\s*)["'](:[^"']+)["']/.exec(args)?.[1];
}
