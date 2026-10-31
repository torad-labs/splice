// The unit test classes a pushed diff can affect, so the pre-push runs those and not the whole suite. Simon asked for it on
// Oct 26 2026, with Marcos's approval, while GitHub's billing lock stops CI and this pre-push is the only check: a full gradle check
// took about 14 minutes a push. A wrongly skipped test reaches main, so wherever the selection is unsure it selects more.
//
// THE REACH. A test class is selected when its file reaches a changed file through references, in any module: a file refers to
// another when it mentions, in code or in a string literal, a name the other declares at top level. Three references name nothing,
// and are followed by rule: a sealed type depends on the files of its package and module that name it (its subclasses, which
// decide every `when` over it); a top-level operator is used unnamed by the files of its package and by the files that import that
// package whole; and a module's generated sources depend on its sources. A changed file counts with the names it declared before
// the push and after it, so a test that still names a deleted or renamed class is selected.
//
// WHAT THE REFERENCES CANNOT SEE runs the whole unit suite of the module that owns it, plus the tests in other modules that reach
// that module (splice-lead, Oct 26 2026): a resource, a golden or any other file that is not a source, a source the scan cannot
// read, code that loads classes by a computed name, and a changed class that a build script or a resource names. When the thing is
// test-only, no other module can see it, so only its own module runs whole. Markdown a module ships is prose for a person, from
// which nothing resolves a class, so it is neither (PROSE).
//
// A FILTER MUST MATCH A TEST. Gradle fails a test task whose `--tests` filter found nothing, and the scan cannot always tell that a
// selected class runs one (a test inherited from a parent, an expression body whose type it cannot resolve). So when no selected
// class is sure, one sure test class of the module joins the filter, and the module runs whole only when it has none.
//
// THE FULL SUITE runs when a changed path is one tools/gate/config/scope.json marks `full`: the version catalog, the root
// build, and the other inputs every module's build reads. A module's own build script, a shared fixture and a build-logic
// file that is not on that list are that module's (or build-logic's) concern, and the reference walk selects their tests.
// The quality/architecture laws run when the path is on the scope file's `laws` list, which is the scope's call.
//
// WHAT THE SELECTION NEVER DOES is widen the run: it names classes inside the module checks the scope already requested
// (prepush-scope.ts), and `confineTo` drops everything else. Only the full suite reaches past the scope.

import { scanSource, type SourceScan, type TestClassDecl } from "./kotlin-source.ts";
import { docsRule, fullRule, repoScope, type ScopeRules } from "./scope.ts";

/** A gradle module: its project path, as settings.gradle.kts names it, and its directory. */
export interface ImpactModule {
  readonly path: string;
  readonly dir: string;
}

/** The pushed commit, and the commit it is judged against. */
export interface PushedTree {
  /** Every path the pushed commit tracks. */
  readonly paths: readonly string[];
  /** A tracked path's text in the pushed commit. */
  readonly read: (path: string) => string;
  /** A path's text in the commit the push is judged against, or undefined when that commit does not hold it. */
  readonly before: (path: string) => string | undefined;
}

export interface ImpactInput {
  readonly modules: readonly ImpactModule[];
  /** The paths the push changes since its base commit. */
  readonly changed: readonly string[];
  readonly tree: PushedTree;
  /** The scope rules. The committed file when omitted. */
  readonly scope?: ScopeRules;
}

/** What one module's unit `test` task runs: the top-level test classes selected, by full name, the whole task, or nothing. */
export type ModuleTests =
  | {
      readonly kind: "classes";
      readonly classes: readonly string[];
      /** One of [classes] the change did not reach, kept so the filter surely matches a test; absent when a selected one does. */
      readonly keeper?: string;
    }
  | { readonly kind: "all"; readonly why: string }
  | { readonly kind: "none" };

export type TestImpact =
  | { readonly mode: "full"; readonly why: string }
  | { readonly mode: "selected"; readonly modules: ReadonlyMap<string, ModuleTests>; readonly changed: number };

/** Why a change to [path] runs the full suite, or undefined when it does not. Only a scope.json `full` path does. */
export function fullSuiteReason(path: string, scope: ScopeRules = repoScope()): string | undefined {
  const rule = fullRule(path, scope);
  return rule === undefined ? undefined : `${path} ${rule.why}`;
}

/** A source file of a module, in the pushed commit or, deleted by the push, in its base. */
interface Node {
  readonly path: string;
  readonly module: ImpactModule;
  /** In the module's test source set, which no other module sees. */
  readonly test: boolean;
  /** In the pushed commit. */
  readonly tip: boolean;
  /** The names it declares; for a changed file, before the push and after it. */
  readonly names: readonly string[];
  /** Those of [names] that are functions or properties, which only code can use. */
  readonly values: ReadonlySet<string>;
  readonly packages: readonly string[];
  readonly tokens: ReadonlySet<string>;
  readonly scan: SourceScan;
  readonly operators: boolean;
  /** Its text, in the pushed commit or, deleted, in the base. */
  readonly text: string;
}

interface Placed {
  readonly module: ImpactModule;
  readonly kind: "source" | "resource" | "other";
  /** The source set it sits in, if any. */
  readonly set: string | undefined;
}

/** A gradle script or a build-logic source: what the build itself names. [module] is unset for the root build and build logic. */
interface BuildText {
  readonly path: string;
  readonly module: ImpactModule | undefined;
  readonly text: string;
  readonly scan: SourceScan;
}

const BINARY = /\.(jar|class|so|dylib|dll|a|o|png|jpe?g|gif|ico|webp|bmp|woff2?|ttf|otf|eot|zip|gz|tgz|bz2|xz|7z|bin|wasm|pdf|node|exe|keystore|jks|p12)$/i;
/** Markdown a module ships: prose for a person, from which nothing resolves a class. A class named there is documented, never
 *  loaded, so it does not make its module whole; the laws that read docs as text are tagged and run in lawTest on every push.
 *  Measured on Oct 26 2026: without this, a change to a class the shipped skill docs mention ran the whole suites of :app and
 *  :features-turns, the two largest modules, on 2 of 10 recent commits. */
const PROSE = /\.(md|markdown)$/i;

const placeIn = (module: ImpactModule, path: string): Placed => {
  const rel = path.slice(module.dir.length + 1);
  const set = /^src\/([^/]+)\//.exec(rel)?.[1];
  if (set !== undefined && /^src\/[^/]+\/(kotlin|java)\/.+\.(kt|java)$/.test(rel)) return { module, kind: "source", set };
  if (set !== undefined && /^src\/[^/]+\/resources\//.test(rel)) return { module, kind: "resource", set };
  return { module, kind: "other", set };
};

/** Whether [text] names [fqn], dotted or slashed, as a whole name: `a.b.C` or `a.b.C$Inner`, never `a.b.Cx` or `za.b.C`. */
function names(text: string, fqn: string): boolean {
  for (const form of [fqn, fqn.replaceAll(".", "/")]) {
    for (let at = text.indexOf(form); at >= 0; at = text.indexOf(form, at + 1)) {
      const before = text.charCodeAt(at - 1);
      const after = text.charCodeAt(at + form.length);
      const wordBefore = at > 0 && (/[\p{L}\p{N}_]/u.test(String.fromCharCode(before)) || before === 46);
      const wordAfter = /[\p{L}\p{N}_]/u.test(String.fromCharCode(after));
      if (!wordBefore && !wordAfter) return true;
    }
  }
  return false;
}

/** Whether one of [strings], a build script's literals, names [path] or a directory above it. */
function namesPath(strings: readonly string[], path: string): boolean {
  for (const literal of strings) {
    const named = literal.replace(/^(\.{0,2}\/)+/, "").replace(/\/+$/, "");
    if (named.length < 2 || !/[\p{L}]/u.test(named)) continue;
    if (path === named || path.startsWith(`${named}/`)) return true;
  }
  return false;
}

export function testImpact(input: ImpactInput): TestImpact {
  const scope = input.scope ?? repoScope();
  const triggers = input.changed.flatMap((path) => fullSuiteReason(path, scope) ?? []);
  const [first] = triggers;
  if (first !== undefined) return { mode: "full", why: triggers.length === 1 ? first : `${first}, and ${triggers.length - 1} more` };
  return select(input, scope);
}

function select(input: ImpactInput, scope: ScopeRules): TestImpact {
  const { tree } = input;
  const modules = input.modules.filter((m) => m.dir !== "").sort((a, b) => b.dir.length - a.dir.length);
  const owner = (path: string): ImpactModule | undefined => modules.find((m) => path.startsWith(`${m.dir}/`));
  const changed = new Set(input.changed);
  const inTip = new Set(tree.paths);

  // The build's own texts: every gradle script outside a source set's resources, and the build logic.
  const buildTexts: BuildText[] = [];
  for (const path of tree.paths) {
    const script = /\.gradle(\.kts)?$/.test(path) && !/(^|\/)src\/[^/]+\/resources\//.test(path);
    const logic = /^(build-logic|buildSrc)\//.test(path) && /\.(kt|kts|java)$/.test(path);
    if (!script && !logic) continue;
    const text = tree.read(path);
    const module = logic ? undefined : owner(path);
    buildTexts.push({ path, module, text, scan: scanSource(text, path.endsWith(".java") ? path : `${path}.kt`) });
  }
  // A module whose test task another task depends on by name (lawSuites depends on :quality-architecture:test) is never narrowed:
  // a filter on that task would narrow the other task's run too.
  const named = new Set<string>();
  for (const b of buildTexts) for (const m of b.text.matchAll(/["'](:[\w.-]+(?::[\w.-]+)*):test["']/g)) if (m[1]) named.add(m[1]);

  // Every source of every module, scanned once.
  const nodes = new Map<string, Node>();
  const others: { path: string; placed: Placed }[] = [];
  const addNode = (path: string, placed: Placed): void => {
    const tip = inTip.has(path);
    const current = tip ? tree.read(path) : undefined;
    const now = current === undefined ? undefined : scanSource(current, path);
    const was = changed.has(path) ? tree.before(path) : undefined;
    const then = was === undefined ? undefined : scanSource(was, path);
    const scan = now ?? then;
    if (!scan) return;
    const union = (pick: (s: SourceScan) => readonly string[]): string[] => [...new Set([...(now ? pick(now) : []), ...(then ? pick(then) : [])])];
    const tokens = now && then ? new Set([...now.tokens, ...then.tokens]) : scan.tokens;
    const names = union((s) => s.declared);
    // A name either version declares as a type stays a type: a type is named in strings too.
    const isType = (name: string): boolean => [now, then].some((s) => s !== undefined && s.declared.includes(name) && !s.values.includes(name));
    nodes.set(path, {
      path,
      module: placed.module,
      test: placed.set === "test",
      tip,
      names,
      values: new Set(names.filter((name) => !isType(name))),
      packages: union((s) => [s.packageName]),
      tokens,
      scan: { ...scan, parsed: (now?.parsed ?? true) && (then?.parsed ?? true) },
      operators: (now?.operators ?? false) || (then?.operators ?? false),
      text: current ?? was ?? "",
    });
  };
  for (const path of tree.paths) {
    const module = owner(path);
    if (!module) continue;
    const placed = placeIn(module, path);
    if (placed.kind === "source") addNode(path, placed);
    else others.push({ path, placed });
  }
  for (const path of changed) {
    if (inTip.has(path)) continue;
    const module = owner(path);
    if (module && placeIn(module, path).kind === "source") addNode(path, placeIn(module, path));
  }
  const all = [...nodes.values()];

  // What the references cannot see.
  const whole = new Map<string, string>();
  const reachModules = new Set<string>();
  const unsure = (module: ImpactModule, why: string, testOnly: boolean): void => {
    if (!whole.has(module.path)) whole.set(module.path, why);
    if (!testOnly) reachModules.add(module.path);
  };
  const extraSeeds = new Set<string>();
  let fullWhy: string | undefined;

  const changedInModules = [...changed].filter((path) => owner(path) !== undefined);
  for (const path of changedInModules) {
    const module = owner(path);
    if (!module) continue;
    const placed = placeIn(module, path);
    if (placed.kind === "source") continue;
    // Prose is read by a person: a changed doc runs the tests that name the file, never the module whole (see PROSE).
    if (!PROSE.test(path)) unsure(module, `${path} is a ${placed.kind === "resource" ? "resource" : "file"} the references cannot follow`, placed.set === "test");
    extraSeeds.add(path.slice(path.lastIndexOf("/") + 1));
  }
  for (const path of changed) {
    if (owner(path) || docsRule(path, scope) !== undefined) continue;
    for (const b of buildTexts) {
      if (b.module && namesPath(b.scan.strings, path)) {
        unsure(b.module, `${path} is read by ${b.path}`, false);
        extraSeeds.add(path.slice(path.lastIndexOf("/") + 1));
      }
    }
  }
  const touched = changedInModules.length > 0 || whole.size > 0;
  const sourceChanged = changedInModules.some((path) => nodes.has(path));
  for (const node of all) {
    if (!node.scan.parsed && touched) unsure(node.module, `${node.path} is a source the scan cannot read`, node.test);
    if (node.tip && node.scan.loadsByName && sourceChanged) unsure(node.module, `${node.path} loads classes by a name the references cannot see`, node.test);
  }
  // A changed class a build script or a resource names: the build or the runtime uses it in a way no reference shows, so the module
  // that OWNS that text runs whole (splice-lead, Oct 26 2026: "every test of the module that owns it"). The changed class's own
  // module is not made whole by someone else's text: nothing in it reads that file, and its own tests are reached by reference.
  // Measured: a core class named in the architecture laws' baseline ran every suite of 30 modules; the laws that read the baseline
  // live in a module the selection never narrows, so they run on every push either way.
  const changedNodes = [...changed].flatMap((path) => nodes.get(path) ?? []);
  const fqns = changedNodes.flatMap((node) => node.packages.flatMap((pkg) => node.names.map((name) => ({ node, fqn: pkg === "" ? name : `${pkg}.${name}` }))));
  if (fqns.length > 0) {
    for (const b of buildTexts) {
      for (const { node, fqn } of fqns) {
        if (!names(b.text, fqn)) continue;
        if (!b.module) fullWhy ??= `${fqn} changed and ${b.path} names it`;
        else unsure(b.module, `${fqn} changed and ${b.path} names it`, node.test);
      }
    }
    for (const { path, placed } of others) {
      if (BINARY.test(path) || PROSE.test(path) || !inTip.has(path)) continue;
      const text = tree.read(path);
      if (text.slice(0, 8000).includes("\0")) continue;
      for (const { node, fqn } of fqns) {
        if (!names(text, fqn)) continue;
        unsure(placed.module, `${fqn} changed and ${path} names it`, node.test || placed.set === "test");
      }
    }
  }
  if (fullWhy !== undefined) return { mode: "full", why: fullWhy };

  // Indexes for the walk, over the pushed commit's sources: only they can depend on anything.
  const mentions = new Map<string, Node[]>();
  const inCode = new Map<string, Node[]>();
  const inPackage = new Map<string, Node[]>();
  const wildcardOf = new Map<string, Node[]>();
  const sealedIn = new Map<string, Map<string, Node[]>>();
  const add = <K, V>(map: Map<K, V[]>, key: K, value: V): void => {
    const list = map.get(key);
    if (list) list.push(value);
    else map.set(key, [value]);
  };
  for (const node of all) {
    if (!node.tip) continue;
    for (const token of node.tokens) add(mentions, token, node);
    for (const token of node.scan.code) add(inCode, token, node);
    add(inPackage, node.scan.packageName, node);
    for (const pkg of node.scan.wildcards) add(wildcardOf, pkg, node);
    for (const name of node.scan.sealed) {
      const scope = `${node.module.path} ${node.scan.packageName}`;
      const byName = sealedIn.get(scope) ?? new Map<string, Node[]>();
      add(byName, name, node);
      sealedIn.set(scope, byName);
    }
  }
  // A module's generated sources: the classes its build script writes, which depend on its sources. A script that adds a source
  // directory without naming what it writes leaves the module unsure whenever one of its sources is reached.
  const generated = new Map<string, Node>();
  const blind = new Map<string, string>();
  for (const b of buildTexts) {
    if (!b.module || !/(?<!resources\s*\.\s*)\bsrcDirs?\s*\(/.test(b.text)) continue;
    const files = b.scan.strings
      .map((s) => /^(?:(.*)\/)?([\p{L}_][\p{L}\p{N}_]*)\.(?:java|kt)$/u.exec(s))
      .filter((m): m is RegExpExecArray => m !== null);
    if (files.length === 0) {
      blind.set(b.module.path, `${b.path} adds sources the references cannot see`);
      continue;
    }
    generated.set(b.module.path, {
      path: `${b.path} (generated)`,
      module: b.module,
      test: false,
      tip: true,
      names: files.map((m) => m[2] ?? ""),
      values: new Set(),
      // The directories of a generated source under its source root are its package; the build names no root, so every suffix counts.
      packages: [...new Set(files.flatMap((m) => (m[1] ?? "").split("/").map((_, at, parts) => parts.slice(at).join("."))))],
      tokens: new Set(),
      scan: scanSource("", `${b.path}.generated`),
      operators: false,
      text: "",
    });
  }

  // A top-level name of another package reaches a file only as Kotlin and Java resolve it: the file imports it, alone or with its
  // whole package, or writes it qualified (in code, or in a string that reflection reads). In its own package no import is needed.
  // A file the scan cannot read may do any of these where the scan cannot see it.
  const resolves = (dependent: Node, packages: readonly string[], name: string): boolean => {
    if (!dependent.scan.parsed) return true;
    for (const pkg of packages) {
      if (dependent.scan.packageName === pkg) return true;
      const fqn = pkg === "" ? name : `${pkg}.${name}`;
      if (dependent.scan.wildcards.includes(pkg)) return true;
      if (dependent.scan.imports.some((path) => path === fqn || path.startsWith(`${fqn}.`))) return true;
      if (pkg !== "" && names(dependent.text, fqn)) return true;
    }
    return false;
  };
  const reached = new Set<Node>();
  const queue: Node[] = [];
  const reach = (node: Node): void => {
    if (reached.has(node)) return;
    reached.add(node);
    queue.push(node);
  };
  for (const path of changed) {
    const node = nodes.get(path);
    if (node) reach(node);
  }
  for (const seed of extraSeeds) for (const node of all) if (node.tip && node.scan.strings.some((s) => s.includes(seed))) reach(node);
  const seeded = new Set<string>();
  const seedModules = (): void => {
    for (const module of reachModules) {
      if (seeded.has(module)) continue;
      seeded.add(module);
      for (const node of all) if (!node.test && node.module.path === module) reach(node);
    }
  };
  seedModules();
  while (queue.length > 0) {
    const node = queue.pop();
    if (!node) break;
    // A test source declares names only its own module's tests can see.
    const sees = (dependent: Node): boolean => !node.test || (dependent.test && dependent.module.path === node.module.path);
    for (const name of node.names) {
      const candidates = (node.values.has(name) ? inCode : mentions).get(name) ?? [];
      for (const dependent of candidates) if (sees(dependent) && resolves(dependent, node.packages, name)) reach(dependent);
    }
    for (const pkg of node.packages) {
      for (const [name, parents] of sealedIn.get(`${node.module.path} ${pkg}`) ?? []) if (node.tokens.has(name)) for (const parent of parents) reach(parent);
      if (node.operators) {
        for (const dependent of inPackage.get(pkg) ?? []) if (sees(dependent)) reach(dependent);
        for (const dependent of wildcardOf.get(pkg) ?? []) if (sees(dependent)) reach(dependent);
      }
    }
    if (!node.test) {
      const source = generated.get(node.module.path);
      if (source) reach(source);
      const why = blind.get(node.module.path);
      if (why !== undefined && !reachModules.has(node.module.path)) {
        unsure(node.module, why, false);
        seedModules();
      }
    }
  }

  // The plan of each module that has tests and may be narrowed.
  const plans = new Map<string, ModuleTests>();
  for (const module of input.modules) {
    const tests = all.filter((node) => node.tip && node.test && node.module.path === module.path);
    if (tests.length === 0 || named.has(module.path)) continue;
    const why = whole.get(module.path);
    if (why !== undefined) {
      plans.set(module.path, { kind: "all", why });
      continue;
    }
    const selected: { fqn: string; decl: TestClassDecl }[] = [];
    for (const node of tests) {
      if (!reached.has(node)) continue;
      const pkg = node.scan.packageName;
      for (const decl of node.scan.classes) if (!decl.law) selected.push({ fqn: pkg === "" ? decl.name : `${pkg}.${decl.name}`, decl });
    }
    if (selected.length === 0) {
      plans.set(module.path, { kind: "none" });
      continue;
    }
    // A FILTER THAT MATCHES NO TEST FAILS THE TASK ("No tests found for given includes"), and the scan cannot always tell that a
    // selected class runs one: a test inherited from a parent class, an expression body whose type it cannot resolve, a class the
    // module's build script may exclude by name. So one class that surely runs a test must be in the filter. When no selected class
    // is, the smallest sure test class of the module joins it, and the narrowing holds: one extra class, not the whole suite.
    const scripts = buildTexts.filter((b) => b.module?.path === module.path);
    const runs = (fqn: string, decl: TestClassDecl): boolean =>
      decl.runnable && !scripts.some((b) => names(b.text, fqn) || b.scan.tokens.has(decl.name));
    const classes = [...new Set(selected.map((s) => s.fqn))].sort();
    if (selected.some(({ fqn, decl }) => runs(fqn, decl))) {
      plans.set(module.path, { kind: "classes", classes });
      continue;
    }
    const keeper = tests
      .flatMap((node) =>
        node.scan.classes
          .filter((decl) => !decl.law && runs(node.scan.packageName === "" ? decl.name : `${node.scan.packageName}.${decl.name}`, decl))
          .map((decl) => ({ fqn: node.scan.packageName === "" ? decl.name : `${node.scan.packageName}.${decl.name}`, size: node.text.length })),
      )
      .sort((a, b) => a.size - b.size || a.fqn.localeCompare(b.fqn))[0]?.fqn;
    plans.set(
      module.path,
      keeper === undefined
        ? { kind: "all", why: "no test class of the module is sure to run a JUnit test, and a filter that runs none fails" }
        : { kind: "classes", classes: [...new Set([...classes, keeper])].sort(), keeper },
    );
  }
  return { mode: "selected", modules: plans, changed: input.changed.length };
}

/**
 * [impact] confined to the modules whose check the push's scope requests. The selection narrows the test run the gate already does
 * and never adds to it: a module the scope left out runs no test today, whatever the reach says, and the scope is the selector's
 * call (selector.ts), not this file's. A module reaches a changed file only by depending on its module, and the scope holds every
 * dependent of a changed module, so confining drops nothing a reference can reach. The full suite is not confined: widening past
 * the scope is exactly what it is for.
 */
export function confineTo(impact: TestImpact, checked: readonly string[]): TestImpact {
  if (impact.mode === "full") return impact;
  const inScope = new Set(checked);
  return { ...impact, modules: new Map([...impact.modules].filter(([module]) => inScope.has(module))) };
}

/**
 * The gradle tasks and options that carry [impact] into a pre-push run whose scope requests the check of each module in [checked].
 * Selected classes filter the module's test task, which its check runs. A whole module adds nothing: its check runs its tests as it
 * always did. A module with nothing selected drops its test task from the check and still compiles its tests. The full suite checks
 * every one of [modules] the scope left out. Pass [impact] through [confineTo] first: a module outside [checked] gets no task here.
 */
export function testArgs(impact: TestImpact, checked: readonly string[], modules: readonly string[]): { tasks: string[]; options: string[] } {
  const inScope = new Set(checked);
  if (impact.mode === "full") return { tasks: modules.filter((m) => !inScope.has(m)).map((m) => `${m}:check`), options: [] };
  const tasks: string[] = [];
  const options: string[] = [];
  for (const [module, plan] of impact.modules) {
    if (!inScope.has(module)) continue;
    if (plan.kind === "classes") tasks.push(`${module}:test`, ...plan.classes.map((fqn) => `--tests=${fqn}*`));
    else if (plan.kind === "none") {
      options.push(`--exclude-task=${module}:test`);
      tasks.push(`${module}:testClasses`);
    }
  }
  return { tasks, options };
}

const simple = (fqn: string): string => fqn.slice(fqn.lastIndexOf(".") + 1);
const plural = (count: number, one: string, many: string): string => `${count} ${count === 1 ? one : many}`;

function counts(impact: Extract<TestImpact, { mode: "selected" }>): { classes: number; narrowed: number; whole: number; none: string[] } {
  let classes = 0;
  let narrowed = 0;
  let whole = 0;
  const none: string[] = [];
  for (const [module, plan] of impact.modules) {
    if (plan.kind === "classes") {
      // The kept class is not one the change reaches: it is in the filter so the task has a test to run.
      classes += plan.classes.length - (plan.keeper === undefined ? 0 : 1);
      narrowed++;
    } else if (plan.kind === "all") whole++;
    else none.push(module);
  }
  return { classes, narrowed, whole, none };
}

/** The mode line the pre-push prints, then one line for each module that runs tests, and one for the modules that run none. */
export function impactLines(impact: TestImpact): string[] {
  if (impact.mode === "full") return [`tests: the full suite, because ${impact.why}`];
  if (impact.modules.size === 0) return ["tests: not narrowed, because no module this push checks has a unit suite of its own"];
  const c = counts(impact);
  const lines = [
    `tests: narrowed to what the ${plural(impact.changed, "changed file reaches", "changed files reach")}: ` +
      `${plural(c.classes, "class", "classes")} in ${plural(c.narrowed, "module", "modules")}, ` +
      `${plural(c.whole, "module", "modules")} whole, ${plural(c.none.length, "module", "modules")} with no test to run`,
  ];
  for (const [module, plan] of impact.modules) {
    if (plan.kind === "classes") {
      const reached = plan.classes.filter((fqn) => fqn !== plan.keeper);
      const shown = reached.slice(0, 12).map(simple).join(", ");
      const more = reached.length > 12 ? `, and ${reached.length - 12} more` : "";
      const kept = plan.keeper === undefined ? "" : `${reached.length === 0 ? "" : ", and "}${simple(plan.keeper)}, which the change does not reach, so the filter has a test to run`;
      lines.push(`  ${module}: ${shown}${more}${kept}`);
    } else if (plan.kind === "all") lines.push(`  ${module}: whole, because ${plan.why}`);
  }
  if (c.none.length > 0) lines.push(`  no test reaches the change in ${c.none.join(", ")}`);
  return lines;
}

/** The short clause the verdict line and the timing line carry. */
export function impactClause(impact: TestImpact): string {
  if (impact.mode === "full") return "tests: full suite";
  if (impact.modules.size === 0) return "tests: not narrowed";
  const c = counts(impact);
  return `tests: ${plural(c.classes, "class", "classes")} in ${plural(c.narrowed, "module", "modules")}, ${c.whole} whole, ${c.none.length} none`;
}
