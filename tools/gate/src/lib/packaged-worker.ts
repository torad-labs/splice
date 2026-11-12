// Whether a push can change what the code-mode worker loads from the fat jar, so pre-push runs :app:codeModePackagedTest
// only when it can (load cut 4 of the temple plan of 2026-10-19, approved by Marcos; the paths that decide were signed off
// by splice-lead on 2026-10-26).
//
// The packaged run reruns the code-mode suites with the worker started from the shipped fat jar instead of the classpath.
// The classpath runs of the same suites stay in every :app:check, so what the packaged run adds is the jar: which classes,
// resources and service files the worker finds there. Guest code reaches no host class (HostAccess.NONE, no host class
// lookup), and the worker's own classes load no class by name and no service of splice's, so the worker loads what its
// modules' code names. A push can change that only through:
//   - a module the worker runs: the module law's closure of :integrations-codemode and :integrations-providers-codex;
//   - the build's own definition: every *.gradle.kts, gradle/, every gradle.properties, build-logic/, the compiler plugin, and
//     the gate's JDK pick, since they decide what is compiled and packed, and the JVM the worker starts on;
//   - an app test the packaged run executes, named by its filter (every app test when the filter cannot be read);
//   - a main resource the fat jar carries, unless it sits in a splice/<x>/ folder of a package the worker's modules do not
//     ship, or is the app's console or manager home;
//   - a main or testFixtures source whose package the worker's modules ship (a class there can shadow the worker's), or
//     whose package is outside splice.<x> (where a class can shadow a library's).
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join } from "node:path";
import type { ModuleGraph } from "./selector.ts";

/** The task that reruns the code-mode suites against the shipped fat jar. */
export const PACKAGED_TEST = ":app:codeModePackagedTest";

/** The task whose graph holds the packaged test: app's check depends on it. */
export const APP_CHECK = ":app:check";

/** The modules whose code the packaged worker runs: code mode's worker pool, and the codex head whose tools it serves. */
const WORKER_ROOTS = [":integrations-codemode", ":integrations-providers-codex"] as const;

const APP = ":app";

/** What decides what is compiled and packed, and the JVM the worker runs on. */
const BUILD_DEFINITION =
  /(^|\/)[^/]+\.gradle\.kts$|(^|\/)gradle\.properties$|^gradle\/|^build-logic\/|^quality\/compiler-plugin\/|^tools\/gate\/src\/lib\/jdk\.ts$/;

/** The app's resources only the console serves: the worker never reads them. */
const CONSOLE_RESOURCES = /^(console|manager-home)\//;

/** A module-relative source the fat jar or the packaged run's classpath carries. */
const SHIPPED_SOURCE = /^src\/(main|testFixtures)\/(kotlin|java)\/.+\.(kt|java)$/;

const MAIN_RESOURCE = /^src\/main\/resources\/(.+)$/;

export interface PackagedInput {
  /** The judged tree: the pushed commit, checked out. */
  readonly root: string;
  /** The gradle modules, each with its directory, as settings.gradle.kts maps them. */
  readonly modules: readonly { readonly path: string; readonly dir: string }[];
  /** The module law. */
  readonly graph: ModuleGraph;
  /** The paths the push changes. */
  readonly changed: readonly string[];
  /** A path's text before the push, for a path the push deleted; undefined when it cannot be read. */
  readonly before: (path: string) => string | undefined;
}

/** Why the packaged test has to run for this push, or undefined when nothing the push changes can change what the worker
 *  loads. What the judgement cannot read keeps the test. */
export function packagedTestReason(input: PackagedInput): string | undefined {
  const { root, modules, graph, changed, before } = input;
  const closure = workerClosure(graph);
  if ("unlisted" in closure) {
    return `the module law does not list ${closure.unlisted}, a module the code-mode worker runs, so it may depend on anything`;
  }
  const dirOf = new Map(modules.map((module) => [module.path, module.dir]));
  const workerModules: { readonly path: string; readonly dir: string }[] = [];
  for (const path of closure.modules) {
    const dir = dirOf.get(path);
    if (dir === undefined) return `settings.gradle.kts maps no directory for ${path}, a module the code-mode worker runs`;
    workerModules.push({ path, dir });
  }
  const packages = shippedPackages(root, workerModules.map((module) => module.dir));
  const appDir = dirOf.get(APP);
  const testNames = appDir === undefined ? undefined : packagedTestNames(root, appDir);
  const text = (path: string): string | undefined => {
    const file = join(root, path);
    return existsSync(file) && statSync(file).isFile() ? readFileSync(file, "utf8") : before(path);
  };

  for (const path of changed) {
    const worker = workerModules.find((module) => path.startsWith(`${module.dir}/`));
    if (worker !== undefined) return `${path} is in ${worker.path}, a module the code-mode worker runs`;
    if (BUILD_DEFINITION.test(path)) return `${path} defines the build that packs the worker and the JVM it runs on`;
    const owner = ownerOf(modules, path);
    if (owner === undefined) continue;
    const inModule = path.slice(owner.dir.length + 1);
    if (owner.path === APP && inModule.startsWith("src/test/")) {
      if (testNames === undefined) return `${path} is an app test, and the packaged run's list of tests cannot be read`;
      if (/\.(kt|java)$/.test(path) && runsInPackagedRun(path, text(path), testNames)) return `${path} is a test the packaged run executes`;
      continue;
    }
    const resource = MAIN_RESOURCE.exec(inModule)?.[1];
    if (resource !== undefined) {
      if (owner.path === APP && CONSOLE_RESOURCES.test(resource)) continue;
      const folder = /^splice\/([^/]+)\//.exec(resource)?.[1];
      if (folder !== undefined && !packages.has(folder)) continue;
      return `${path} is a resource the fat jar carries where the worker can read it`;
    }
    if (SHIPPED_SOURCE.test(inModule)) {
      const source = text(path);
      if (source === undefined) return `${path} cannot be read, so its package cannot be told apart from the worker's`;
      const declared = packageOf(source);
      const top = /^splice\.([^.]+)/.exec(declared)?.[1];
      if (top === undefined) {
        return `${path} declares ${declared === "" ? "no package" : `package ${declared}`}, outside splice's own, where a class can shadow one the worker loads`;
      }
      if (packages.has(top)) return `${path} declares package ${declared}, which the code-mode worker's modules ship`;
    }
  }
  return undefined;
}

/** The modules the worker runs: its roots and every module they may depend on, by the module law. A module the law does not
 *  list may depend on anything, so the closure cannot be read past it. */
function workerClosure(graph: ModuleGraph): { readonly modules: readonly string[] } | { readonly unlisted: string } {
  const seen = new Set<string>();
  const queue: string[] = [...WORKER_ROOTS];
  for (let module = queue.shift(); module !== undefined; module = queue.shift()) {
    if (seen.has(module)) continue;
    const deps = graph.get(module);
    if (deps === undefined) return { unlisted: module };
    seen.add(module);
    queue.push(...deps);
  }
  return { modules: [...seen].sort() };
}

/** The module that owns [path]: the one with the longest directory prefix. */
function ownerOf<M extends { readonly dir: string }>(modules: readonly M[], path: string): M | undefined {
  let best: M | undefined;
  for (const module of modules) {
    if (path.startsWith(`${module.dir}/`) && (best === undefined || module.dir.length > best.dir.length)) best = module;
  }
  return best;
}

/** The x of every splice.x package the worker's modules ship, read from their splice/<x>/ source and resource folders. */
function shippedPackages(root: string, dirs: readonly string[]): ReadonlySet<string> {
  const names = new Set<string>();
  for (const dir of dirs) {
    for (const kind of ["kotlin", "java", "resources"]) {
      const folder = join(root, dir, "src", "main", kind, "splice");
      if (!existsSync(folder)) continue;
      for (const entry of readdirSync(folder, { withFileTypes: true })) if (entry.isDirectory()) names.add(entry.name);
    }
  }
  return names;
}

/** The class names the packaged run's filter includes, read from the app build, or undefined when it declares no packaged run
 *  or includes a pattern that is not one class name. */
function packagedTestNames(root: string, appDir: string): readonly string[] | undefined {
  const script = join(root, appDir, "build.gradle.kts");
  if (!existsSync(script)) return undefined;
  const text = readFileSync(script, "utf8");
  const at = text.indexOf('register<Test>("codeModePackagedTest")');
  if (at < 0) return undefined;
  const body = braced(text, at);
  const filter = body === undefined ? undefined : /\bfilter\s*\{([^}]*)\}/.exec(body)?.[1];
  if (filter === undefined) return undefined;
  const names = [...filter.matchAll(/includeTestsMatching\("([^"]*)"\)/g)].map((match) => match[1] ?? "");
  return names.length > 0 && names.every((name) => /^[A-Za-z_]\w*$/.test(name)) ? names : undefined;
}

/** The text inside the first brace block at or after [from], or undefined when it does not close. */
function braced(text: string, from: number): string | undefined {
  const open = text.indexOf("{", from);
  if (open < 0) return undefined;
  let depth = 0;
  for (let at = open; at < text.length; at++) {
    if (text[at] === "{") depth++;
    else if (text[at] === "}" && --depth === 0) return text.slice(open + 1, at);
  }
  return undefined;
}

/** Whether an app test file holds a class the packaged run executes: by its file name, or by a class it declares. */
function runsInPackagedRun(path: string, source: string | undefined, names: readonly string[]): boolean {
  if (names.includes(basename(path).replace(/\.(kt|java)$/, ""))) return true;
  return source !== undefined && new RegExp(`\\b(class|object)\\s+(${names.join("|")})\\b`).test(source);
}

/** The package a Kotlin or Java source declares, or "" for the default package. */
function packageOf(source: string): string {
  return /^[ \t]*package[ \t]+([\w.`]+)/m.exec(source)?.[1]?.replaceAll("`", "") ?? "";
}
