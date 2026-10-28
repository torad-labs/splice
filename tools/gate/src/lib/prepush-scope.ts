// The pre-push scope: what one pushed diff makes the gate run. Pure: the caller reads the ladder, the changed paths,
// the module list and the scope file, and this file decides. Nothing here starts a process.
//
// THE SCOPE IS THE PUSHED DIFF, NOT THE TREE. A ladder row runs when a changed path matches one of its `inputs`. A
// pattern that is only `**` matches nothing: it used to match every path, so those rows ran on every push. A row with
// `on: "push"` judges the push itself (the commit subject, the history) and runs on every push without selecting a
// module. The selector names the modules the diff affects: each changed module and every module that depends on one.
// `check` runs for those modules only. A module the diff cannot reach is not built. `lawSuites` runs when a changed
// path is one scope.json's `laws` list names. A jar leg runs when its own inputs match, and not because some product
// file changed. Which test classes those checks run is test-impact.ts's call.

import { type ModuleGraph, select } from "./selector.ts";
import { isMatchAll, lawRule, matchesAny, type ScopeRules, repoScope } from "./scope.ts";

/** A ladder row as pre-push reads it. `inputs` is required unless `on` is `push`: the path globs the leg reads. */
export interface Leg {
  readonly task: string;
  readonly command: readonly string[];
  readonly inputs?: readonly string[];
  /** `push`: the leg judges the push itself and runs on every push. Absent: the leg runs when an input matches. */
  readonly on?: "push";
  /** The path globs that make pre-commit run this leg: the paths a single commit can turn this leg red with. Only a leg that
   *  needs no gradle graph and runs in seconds declares it. */
  readonly commit?: readonly string[];
  /** What pre-commit runs for this leg when it differs from `command`: the staged-bytes form of a leg whose full form scans the tree. */
  readonly commitCommand?: readonly string[];
  readonly dependsOn?: readonly string[];
  readonly creates?: string;
  readonly owns?: string;
}

/** The gradle task that runs every law suite. Gradle holds the list of suites and the inputs each one declares, so pre-push
 *  requests this one task and gradle's up-to-date check skips each suite whose declared inputs did not change. */
export const LAW_SUITES_TASK = "lawSuites";
/** The fat jar's task. A leg that depends on it runs when that leg's inputs match. The jar is not built for a product
 *  change whose legs do not read it. */
export const JAR_TASK = ":app:shadowJar";

/** The test every law-suite request runs inside lawSuites' :app:lawTest: a host-tool name must not reach a public source
 *  from any module. */
export const PUBLIC_SOURCE_TEST = "PublicSourceNamesNoHostToolTest";

/** One module or check, and why this push selected or skipped it. */
export interface Decision {
  readonly kind: "module" | "check";
  readonly name: string;
  readonly selected: boolean;
  readonly why: string;
}

/** The line the pre-push prints for [decision]. */
export function decisionLine(decision: Decision): string {
  return `  ${decision.kind} ${decision.name}: ${decision.selected ? "selected" : "skipped"} — ${decision.why}`;
}

/** A leg needs gradle's graph when it depends on a task, creates a directory or owns a receipt. */
const needsGradle = (leg: Leg): boolean => (leg.dependsOn?.length ?? 0) > 0 || leg.creates !== undefined || leg.owns !== undefined;

/** The ladder rows pre-commit runs for a commit that changes [changed]: a row whose `commit` globs match a changed path. A
 *  row that needs gradle's graph never runs here, whatever it declares. */
export function commitLegs(legs: readonly Leg[], changed: readonly string[]): Leg[] {
  return legs.filter(
    (leg) =>
      leg.commit !== undefined &&
      !needsGradle(leg) &&
      changed.some((path) => matchesAny(leg.commit ?? [], path)),
  );
}

/** The ladder rows that declare no inputs and do not judge the push itself. Pre-push cannot scope them, so it refuses
 *  the push by name. A match-all pattern is an input that selects nothing; it is not a missing input. */
export function legsWithoutInputs(legs: readonly Leg[]): string[] {
  return legs.filter((leg) => leg.on !== "push" && (!Array.isArray(leg.inputs) || leg.inputs.length === 0)).map((leg) => leg.task);
}

export interface PrePushScope {
  /** The ladder rows this push runs, in ladder order. */
  readonly legs: readonly Leg[];
  /** The gradle tasks to run, the legs among them. Empty when this push needs no gradle. */
  readonly gradle: readonly string[];
  /** The rows that need no gradle graph, run directly whether or not gradle runs. */
  readonly direct: readonly Leg[];
  /** Why each module and each check was selected or skipped, modules first, then checks. */
  readonly decisions: readonly Decision[];
  /** The scope clause of the verdict line. */
  readonly summary: string;
}

export interface ScopeInput {
  readonly legs: readonly Leg[];
  /** Gradle project paths, as settings.gradle.kts names them, e.g. ":core". */
  readonly modules: readonly string[];
  /** The module a changed path belongs to, or undefined for a path outside every module. */
  readonly moduleOf: (path: string) => string | undefined;
  /** The paths the pushed diff changes. */
  readonly changed: readonly string[];
  /** The module law (gradle/module-law.txt), so a changed module's dependents are checked with it. Absent: no dependents. */
  readonly graph?: ModuleGraph;
  /** The scope rules. The committed file when omitted. */
  readonly scope?: ScopeRules;
}

/** A leg that depends on the fat jar. */
const runsOnJar = (leg: Leg): boolean => leg.dependsOn?.includes(JAR_TASK) === true;

/** Why [leg] is selected for [changed], or why it is not. */
function legDecision(leg: Leg, changed: readonly string[]): Decision {
  if (leg.on === "push") return { kind: "check", name: leg.task, selected: true, why: "it judges the push itself, not the changed files" };
  const inputs = leg.inputs ?? [];
  const usable = inputs.filter((pattern) => !isMatchAll(pattern));
  const matchAll = inputs.filter(isMatchAll);
  const hit = changed.find((path) => matchesAny(usable, path));
  if (hit !== undefined) return { kind: "check", name: leg.task, selected: true, why: `${hit} matches its inputs` };
  if (matchAll.length > 0 && usable.length === 0) {
    return { kind: "check", name: leg.task, selected: false, why: `its inputs are ${matchAll.join(", ")}, which match every path and select nothing` };
  }
  return { kind: "check", name: leg.task, selected: false, why: "no changed path matches its inputs" };
}

/** The changed module [module] depends on, named through the chain, or undefined when it depends on none. */
function reaches(module: string, touched: ReadonlySet<string>, graph: ModuleGraph, seen: Set<string>): string | undefined {
  if (seen.has(module)) return undefined;
  seen.add(module);
  const deps = graph.get(module);
  if (deps === undefined) return undefined;
  for (const dep of deps) if (touched.has(dep)) return dep;
  for (const dep of deps) {
    const nested = reaches(dep, touched, graph, seen);
    if (nested !== undefined) return `${dep}, which depends on ${nested}`;
  }
  return undefined;
}

function listed(paths: readonly string[]): string {
  const shown = paths.slice(0, 3).join(", ");
  return paths.length > 3 ? `${shown} and ${paths.length - 3} more` : shown;
}

/** Why :app:shadowJar is built for the legs this push selected. The packaged test is the other requester; the hook
 *  passes whether that test runs and why. */
export function shadowJarDecision(legs: readonly Leg[], packagedRuns: boolean, packagedWhy: string | undefined): Decision {
  const jars = legs.filter(runsOnJar);
  if (jars.length > 0) {
    return { kind: "check", name: JAR_TASK, selected: true, why: `${jars.map((leg) => leg.task).join(", ")} depend on it` };
  }
  if (packagedRuns) return { kind: "check", name: JAR_TASK, selected: true, why: packagedWhy ?? "the packaged code-mode test runs and builds the jar" };
  return { kind: "check", name: JAR_TASK, selected: false, why: "no selected leg depends on it, and the packaged code-mode test does not run" };
}

export function prePushScope(input: ScopeInput): PrePushScope {
  const scope = input.scope ?? repoScope();
  const { changed } = input;
  const graph = input.graph ?? new Map();
  const selection = select({ changed, modules: input.modules, moduleOf: input.moduleOf, graph, scope });
  const docsOnly = selection.docsOnly;
  const modules = docsOnly ? [] : selection.modules;
  const selected = new Set(modules);
  const touched = new Set<string>();
  for (const path of changed) {
    const owner = input.moduleOf(path);
    if (owner !== undefined) touched.add(owner);
  }

  const moduleDecisions: Decision[] = input.modules.map((module) => {
    if (docsOnly) return { kind: "module", name: module, selected: false, why: "every changed path is prose no law reads" };
    if (selection.full) return { kind: "module", name: module, selected: true, why: selection.why ?? "a changed path affects every module" };
    if (touched.has(module)) {
      const files = changed.filter((path) => input.moduleOf(path) === module);
      return { kind: "module", name: module, selected: true, why: `the push changes ${listed(files)}` };
    }
    if (selected.has(module)) {
      const via = reaches(module, touched, graph, new Set());
      return { kind: "module", name: module, selected: true, why: via === undefined ? "it depends on a changed module" : `it depends on ${via}` };
    }
    return { kind: "module", name: module, selected: false, why: "the push does not change it and it does not depend on a changed module" };
  });

  const checkDecisions = input.legs.map((leg) => legDecision(leg, changed));
  const inScope = input.legs.filter((_, index) => checkDecisions[index]?.selected === true);
  const lawHit = changed.map((path) => ({ path, rule: lawRule(path, scope) })).find((hit) => hit.rule !== undefined);
  const laws = !docsOnly && lawHit?.rule !== undefined;
  const buildLogicChanged = changed.some((path) => path.startsWith("build-logic/"));
  const extra: Decision[] = [
    {
      kind: "check",
      name: LAW_SUITES_TASK,
      selected: laws,
      why: laws ? `${lawHit?.path} ${lawHit?.rule?.why}` : docsOnly ? "every changed path is prose no law reads" : "no changed path is an input of a law",
    },
    {
      kind: "check",
      name: "build-logic:test, build-logic:detekt, build-logic:ktlintCheck",
      selected: buildLogicChanged,
      why: buildLogicChanged ? "the push changes build-logic/" : "the push does not change build-logic/",
    },
  ];

  const gradleLegs = inScope.filter(needsGradle);
  const directLegs = inScope.filter((leg) => !gradleLegs.includes(leg));
  const gradle: string[] = [];
  for (const module of modules) gradle.push(`${module}:check`);
  if (laws) gradle.push(LAW_SUITES_TASK);
  if (buildLogicChanged) gradle.push("build-logic:test", "build-logic:detekt", "build-logic:ktlintCheck");
  for (const leg of gradleLegs) gradle.push(`:${leg.task}`);

  const legList = inScope.length === 0 ? "no legs" : `legs ${inScope.map((leg) => leg.task).join(", ")}`;
  const check = `check of ${modules.length === 0 ? "no module" : modules.join(", ")}`;
  const publicSource = `${PUBLIC_SOURCE_TEST} in ${LAW_SUITES_TASK}`;
  const gradleClause = docsOnly
    ? "gradle: none (docs only)"
    : gradle.length === 0
      ? "gradle: none"
      : laws
        ? `gradle: ${check}, ${LAW_SUITES_TASK}; ${publicSource}`
        : `gradle: ${check}`;
  return {
    legs: inScope,
    gradle,
    direct: directLegs,
    decisions: [...moduleDecisions, ...checkDecisions, ...extra],
    summary: `${legList}; ${gradleClause}`,
  };
}
