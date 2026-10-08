// The pre-push scope: what one pushed diff makes the gate run. Pure: the caller reads the ladder, the changed paths and
// the module list, and this file decides. Nothing here starts a process.
//
// THE SCOPE IS THE PUSHED DIFF, NOT THE TREE. A ladder row runs when a changed path matches one of its `inputs`, the
// path globs the leg actually reads. A Kotlin or gradle change compiles every module (a caller in another module still
// refuses the push) and runs `check` for each module the diff changes. A module the diff does not touch gets no tests.
// Every push requests the law suites (`lawSuites`) and the jar legs: gradle's up-to-date check decides what runs, because
// the suites and the jar declare their inputs in gradle, the one place those reads are known. `lawSuites` runs each module's
// lawTest, the laws that read outside their module, so PublicSourceNamesNoHostToolTest runs on every push too. The unit
// suites are not requested here: a module's check runs them, and each reruns only when its own classpath changes. The full suite stays in CI, which runs
// gateOfRecord on the pushed sha.

/** A ladder row as pre-push reads it. `inputs` is required: the path globs the leg reads. */
export interface Leg {
  readonly task: string;
  readonly command: readonly string[];
  readonly inputs?: readonly string[];
  /** The path globs that make pre-commit run this leg: the paths a single commit can turn this leg red with. Only a leg that
   *  needs no gradle graph and runs in seconds declares it. */
  readonly commit?: readonly string[];
  readonly dependsOn?: readonly string[];
  readonly creates?: string;
  readonly owns?: string;
}

/** A change a gradle run must judge: Kotlin, Java, a gradle script, the catalog, or the wrapper. */
const GRADLE_INPUT = /\.(kts?|java)$|^gradle\/|^gradle\.properties$|^gradlew(\.bat)?$|^build-logic\//;
/** A change every module's build depends on: the shared build logic, the settings, or the root build script. */
const WHOLE_BUILD = /^(settings\.gradle\.kts$|build\.gradle\.kts$|gradle\.properties$|gradle\/|build-logic\/)/;

/** The gradle task that runs every law suite. Gradle holds the list of suites and the inputs each one declares, so pre-push
 *  requests this one task and gradle's up-to-date check skips each suite whose declared inputs did not change. */
export const LAW_SUITES_TASK = "lawSuites";
/** The fat jar's task. A leg that depends on it runs when the jar was rebuilt or the leg's own inputs changed. Pre-push
 *  requests every such leg on every push and gradle decides: the jar's inputs are shadowJar's declaration, not a copy. */
export const JAR_TASK = ":app:shadowJar";

/** The test every push runs, whatever it changed, inside lawSuites' :app:lawTest: a host-tool name must not reach a public source
 *  from any module. */
export const PUBLIC_SOURCE_TEST = "PublicSourceNamesNoHostToolTest";

const compiled = new Map<string, Bun.Glob>();
function matchesAny(patterns: readonly string[], path: string): boolean {
  return patterns.some((pattern) => {
    let glob = compiled.get(pattern);
    if (glob === undefined) {
      glob = new Bun.Glob(pattern);
      compiled.set(pattern, glob);
    }
    return glob.match(path);
  });
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

/** The ladder rows that declare no inputs. Pre-push cannot scope them, so it refuses the push by name. */
export function legsWithoutInputs(legs: readonly Leg[]): string[] {
  return legs.filter((leg) => !Array.isArray(leg.inputs) || leg.inputs.length === 0).map((leg) => leg.task);
}

export interface PrePushScope {
  /** The ladder rows this push runs, in ladder order. */
  readonly legs: readonly Leg[];
  /** The gradle tasks to run, the legs among them. Empty when this push needs no gradle. */
  readonly gradle: readonly string[];
  /** The rows that need no gradle graph, run directly whether or not gradle runs. */
  readonly direct: readonly Leg[];
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
}

/** A leg that depends on the fat jar. Pre-push requests it on every push, and gradle decides whether it runs. */
const runsOnJar = (leg: Leg): boolean => leg.dependsOn?.includes(JAR_TASK) === true;

export function prePushScope(input: ScopeInput): PrePushScope {
  const { changed } = input;
  const inScope = input.legs.filter((leg) => runsOnJar(leg) || changed.some((path) => matchesAny(leg.inputs ?? [], path)));
  const legList = inScope.length === 0 ? "no legs" : `legs ${inScope.map((leg) => leg.task).join(", ")}`;

  // A module is checked when the diff changes it, or when a shared input changed (every module's build depends on it, and
  // every module's detekt reads the detekt config).
  const checked = new Set<string>();
  for (const path of changed) {
    const module = input.moduleOf(path);
    if (module !== undefined) checked.add(module);
  }
  if (changed.some((path) => WHOLE_BUILD.test(path))) for (const module of input.modules) checked.add(module);
  const modules = [...checked].sort();

  // A leg runs as a gradle task when it needs gradle's graph (a jar it depends on, a directory it creates, a receipt it
  // owns). Every other in-scope leg runs directly, in parallel with gradle, and never waits for the slot.
  const gradleLegs = inScope.filter(needsGradle);
  const directLegs = inScope.filter((leg) => !gradleLegs.includes(leg));
  const gradleInput = changed.some((path) => GRADLE_INPUT.test(path));
  const gradle: string[] = [];
  if (gradleInput) for (const module of input.modules) gradle.push(`${module}:compileKotlin`, `${module}:compileTestKotlin`);
  for (const module of modules) gradle.push(`${module}:check`);
  // lawSuites runs :app:lawTest, not :app:test. A --tests filter on :app:test would narrow the module's unit run too: gradle
  // keeps one instance of a task however many tasks depend on it, so the public-source test rides in lawSuites instead.
  gradle.push(LAW_SUITES_TASK);
  if (changed.some((path) => path.startsWith("build-logic/"))) gradle.push("build-logic:test");
  for (const leg of gradleLegs) gradle.push(`:${leg.task}`);

  const compile = gradleInput ? `compile of ${input.modules.length} module(s)` : "no compile";
  const check = `check of ${modules.length === 0 ? "no module" : modules.join(", ")}`;
  const publicSource = `${PUBLIC_SOURCE_TEST} in ${LAW_SUITES_TASK}`;
  return {
    legs: inScope,
    gradle,
    direct: directLegs,
    summary: `${legList}; gradle: ${compile}, ${check}, ${LAW_SUITES_TASK}; ${publicSource}`,
  };
}
