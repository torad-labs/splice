// File-level test selection for a ladder leg that is one `bun test <dir>` run: the test files a change can affect, found
// from the import graph, so a change to one gate source file runs the test files that import it (directly or through
// others) and not the whole suite. Anything the graph cannot place, such as a config file, a fixture or a non-TypeScript
// path under the directory, keeps the whole directory: the selection narrows only when it can prove the narrowing. So
// does a file outside the directory that the leg names as its own input and that is no TypeScript: a test reads such a
// file from the disk (the secret-scan allowlist), and no import says which test. Until Nov 21, 2026 such a change ran
// no test at all, so a push that changed the allowlist passed here and failed its test in CI.
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, normalize, relative } from "node:path";
import type { Leg } from "./prepush-scope.ts";
import { isMatchAll, matchesAny } from "./scope.ts";

const IMPORT = /(?:from|import)\s*\(?\s*["'](\.{1,2}\/[^"']+)["']/g;
const TEST_FILE = /\.test\.ts$/;

/** Every .ts file under [dir] (relative to [root]), as repo-relative paths. */
function tsFiles(root: string, dir: string): string[] {
  const out: string[] = [];
  const walk = (rel: string): void => {
    for (const name of readdirSync(join(root, rel))) {
      if (name === "node_modules" || name === "build" || name.startsWith(".")) continue;
      const next = join(rel, name);
      // A link whose target is gone (a run's `latest` in an ignored receipts folder) names no file to select.
      const stat = statSync(join(root, next), { throwIfNoEntry: false });
      if (stat === undefined) continue;
      if (stat.isDirectory()) walk(next);
      else if (name.endsWith(".ts")) out.push(next);
    }
  };
  if (existsSync(join(root, dir))) walk(dir);
  return out;
}

/** The repo-relative file each relative import in [file] names. */
function importsOf(root: string, file: string): string[] {
  const text = readFileSync(join(root, file), "utf8");
  const found: string[] = [];
  for (const match of text.matchAll(IMPORT)) {
    const target = normalize(join(dirname(file), match[1] ?? ""));
    if (existsSync(join(root, target)) && statSync(join(root, target)).isFile()) found.push(target);
  }
  return found;
}

/** The leg narrowed to the test files [changed] affects, or `undefined` when none is affected so the leg has nothing to run.
 *  A leg that is not a single `bun test <dir>` run is returned as it is. */
export function narrowBunTest(root: string, leg: Leg, changed: readonly string[]): Leg | undefined {
  const [bun, test, dir, ...rest] = leg.command;
  if (bun !== "bun" || test !== "test" || dir === undefined || rest.length > 0) return leg;
  const underDir = (path: string): boolean => path === dir || path.startsWith(`${dir}/`);
  const tracked = changed.filter((path) => (leg.inputs ?? []).length === 0 || underDir(path) || path.endsWith(".ts") || path.endsWith(".tsx"));
  // A path the import graph cannot speak for keeps the whole directory: one under it that is no TypeScript, and one
  // anywhere that the leg names as an input and that is no TypeScript, which a test can only have read from the disk.
  const named = (leg.inputs ?? []).filter((pattern) => !isMatchAll(pattern));
  const readFromDisk = (path: string): boolean => !/\.tsx?$/.test(path) && matchesAny(named, path);
  if (changed.some((path) => (underDir(path) && !path.endsWith(".ts")) || readFromDisk(path))) return leg;

  const files = tsFiles(root, "tools");
  const importedBy = new Map<string, string[]>();
  for (const file of files) {
    for (const target of importsOf(root, file)) importedBy.set(target, [...(importedBy.get(target) ?? []), file]);
  }
  const reached = new Set<string>();
  const queue = tracked.filter((path) => path.endsWith(".ts"));
  while (queue.length > 0) {
    const next = queue.pop() as string;
    if (reached.has(next)) continue;
    reached.add(next);
    queue.push(...(importedBy.get(next) ?? []));
  }
  const tests = [...reached].filter((file) => TEST_FILE.test(file) && underDir(file)).sort();
  if (tests.length === 0) return undefined;
  return { ...leg, command: ["bun", "test", ...tests.map((file) => relative(root, join(root, file)))] };
}
