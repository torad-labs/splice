// The pre-push scope: what one pushed diff makes the gate run. Pure: the caller reads the ladder, the changed paths and
// the module list, and this file decides. Nothing here starts a process.
//
// THE SCOPE IS THE PUSHED DIFF, NOT THE TREE. A ladder row runs when a changed path matches one of its `inputs`, the
// path globs the leg actually reads. A Kotlin or gradle change compiles every module (a caller in another module still
// refuses the push), runs `check` for each module the diff changes, and for each law suite whose reads it touches.
// A module the diff does not touch gets no tests. Every push runs PublicSourceNamesNoHostToolTest, whatever it changed:
// it reads every tracked file, so no path can keep it from running. The full suite stays in CI, which runs gateOfRecord
// on the pushed sha.

/** A ladder row as pre-push reads it. `inputs` is required: the path globs the leg reads. */
export interface Leg {
  readonly task: string;
  readonly command: readonly string[];
  readonly inputs?: readonly string[];
  readonly dependsOn?: readonly string[];
  readonly creates?: string;
  readonly owns?: string;
}

/** A change a gradle run must judge: Kotlin, Java, a gradle script, the catalog, or the wrapper. */
const GRADLE_INPUT = /\.(kts?|java)$|^gradle\/|^gradle\.properties$|^gradlew(\.bat)?$|^build-logic\//;
/** A change every module's build depends on: the shared build logic, the settings, or the root build script. */
const WHOLE_BUILD = /^(settings\.gradle\.kts$|build\.gradle\.kts$|gradle\.properties$|gradle\/|build-logic\/)/;

/** The path globs each law suite reads from outside its own module, keyed by the suite's gradle project. A suite whose
 *  project is absent reads only its own module. Each entry names the read it stands for, so the list can be checked
 *  against the suite. `:app`'s repository-wide scan (cli/doctor/PublicSourceNamesNoHostToolTest.kt:56-113) is not
 *  scoped here: it reads every tracked file, so the push that runs it would be every push. CI's gate of record runs it. */
export const LAW_READS: Readonly<Record<string, readonly string[]>> = {
  ":quality-architecture": [
    "**/src/main/**/*.kt", // KotlinText.kt:37-45: every module's main sources
    "**/*.gradle.kts", // ProjectMapTest.kt:79-95, ModuleLawsTest.kt:884-924: every module's build scripts
    "quality/**", // quality/detekt/detekt.yml, and the suite's own sources under quality/architecture
    ".dev/campaigns/**", // the campaign ledgers, *.toml
    "README.md", // ReleaseReadinessLawTest.kt:51-90: the fixed root files
    "CHANGELOG.md",
    "AGENTS.md",
    "install.sh",
    "package.json",
    "tools/gate/src/lib/hook.ts",
    ".github/workflows/**",
    "app/src/main/resources/splice.example.toml", // a specific file another module's law reads
  ],
  ":app": ["features/turns/**"], // cli/daemon/DaemonStopOrderTest.kt:64-152 reads the turns module's sources
  ":features-diagnostics": ["app/src/main/dist/**", "tools/e2e/src/commands/heads.ts"], // its launch script and e2e heads verb
  ":features-lifecycle": ["app/src/main/dist/bin/splice-launch"],
  ":integrations-topology": ["README.md", "CHANGELOG.md", "app/src/main/resources/splice.example.toml"],
  ":integrations-upstream": ["AGENTS.md"],
};

/** The test every push runs, whatever it changed: a host-tool name must not reach a public source from any module. :app:check
 *  runs it too, so a push that checks :app already runs it. */
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
  /** For each law-suite module, the path globs its tests read from other modules or the repository. */
  readonly lawReads: Readonly<Record<string, readonly string[]>>;
  /** The paths the pushed diff changes. */
  readonly changed: readonly string[];
}

export function prePushScope(input: ScopeInput): PrePushScope {
  const { changed } = input;
  const inScope = input.legs.filter((leg) => changed.some((path) => matchesAny(leg.inputs ?? [], path)));
  const legList = inScope.length === 0 ? "no legs" : `legs ${inScope.map((leg) => leg.task).join(", ")}`;

  // A module is checked when the diff changes it, when a law suite reads a changed path, or when a shared input changed
  // (every module's build depends on it, and every module's detekt reads the detekt config).
  const checked = new Set<string>();
  for (const path of changed) {
    const module = input.moduleOf(path);
    if (module !== undefined) checked.add(module);
  }
  if (changed.some((path) => WHOLE_BUILD.test(path))) for (const module of input.modules) checked.add(module);
  for (const [module, reads] of Object.entries(input.lawReads)) {
    if (changed.some((path) => matchesAny(reads, path))) checked.add(module);
  }
  const modules = [...checked].sort();

  // A leg runs as a gradle task when it needs gradle's graph (a jar it depends on, a directory it creates, a receipt it
  // owns). Every other in-scope leg runs directly, in parallel with gradle, and never waits for the slot.
  const gradleLegs = inScope.filter((leg) => (leg.dependsOn?.length ?? 0) > 0 || leg.creates !== undefined || leg.owns !== undefined);
  const directLegs = inScope.filter((leg) => !gradleLegs.includes(leg));
  const gradleInput = changed.some((path) => GRADLE_INPUT.test(path));
  const gradle: string[] = [];
  if (gradleInput) for (const module of input.modules) gradle.push(`${module}:compileKotlin`, `${module}:compileTestKotlin`);
  for (const module of modules) gradle.push(`${module}:check`);
  // The option follows its task: gradle applies --tests to the task before it, so the pair stays together.
  const appChecked = modules.includes(":app");
  if (!appChecked) gradle.push(":app:test", `--tests=*${PUBLIC_SOURCE_TEST}`);
  if (changed.some((path) => path.startsWith("build-logic/"))) gradle.push("build-logic:test");
  for (const leg of gradleLegs) gradle.push(`:${leg.task}`);

  const compile = gradleInput ? `compile of ${input.modules.length} module(s)` : "no compile";
  const check = `check of ${modules.length === 0 ? "no module" : modules.join(", ")}`;
  const publicSource = appChecked ? `${PUBLIC_SOURCE_TEST} in :app:check` : `${PUBLIC_SOURCE_TEST} via :app:test`;
  return { legs: inScope, gradle, direct: directLegs, summary: `${legList}; gradle: ${compile}, ${check}; ${publicSource}` };
}
