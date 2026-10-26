// `gate hook` — the git hooks. `hook pre-commit` and `hook pre-push` are what the two shims in .git/hooks
// exec; `hook install` writes those shims. The logic lives here, versioned and bun-tested, so each shim is
// one line.
//
// A RED CHECK BLOCKS. A gradle run that exits nonzero stops the commit or the push. Nothing is waived by the
// file a failure names or by who holds that file: the output names each failing file and the seat that holds it
// (.git/seat-locks), so the blocked seat knows whom to message. The one exception is a COLLISION, a failure
// another gradle run in this checkout leaves behind (see isCollision). A collision reruns the tasks that failed or
// were never reached, once; a red after the rerun, or a second collision, fails.
//
// STAGED BYTES ONLY. Pre-commit judges the index, never the worktree: the walls read the staged blobs, so a seat commits
// only its own lines of a shared file and another seat's half-finished edit never blocks a commit. The worktree is
// pre-push's to judge, and its verdict says so.
//
// EVERY KOTLIN FILE HAS A CHECK. A Kotlin path maps to the gradle tasks that compile it: its module (compile,
// test compile, detekt), build-logic (its compile), or a root script (the configuration pass, `help`, which
// compiles every script of the build). A Kotlin path that maps to no check refuses the commit.
//
// PRE-COMMIT judges the COMMIT. Its paths come from the index git is writing (`git diff --cached` honours
// GIT_INDEX_FILE, which git sets for `git commit -- <paths>`). It runs the walls on those staged blobs and no gradle;
// the compile, detekt and tests a commit's modules need belong to the pre-push tier.
//
// PRE-PUSH judges the PUSHED COMMIT, and the verdict line says so. It moves the persistent build tree under the git
// directory (prepush-tree.ts) to the commit, so a half-finished edit in the shared checkout never reddens a push and the
// build output of the last push is still there. It lints the tip's subject, then scopes the push to its own diff
// (prepush-scope.ts, selector.ts): the ladder rows whose inputs the diff touches run, and gradle runs the changed modules
// and their dependents, in parallel, through the slot. The full ladder is a push to main's. :app:codeModePackagedTest, which
// reruns the code-mode suites against the fat jar, runs only when the push can change what the worker loads (packaged-worker.ts).
// Pushes of one branch queue in the push lane (push-lane.ts): only the newest queued push is judged, an older one whose commit it
// carried is told so and sends nothing, and when the newest fails the next older one is judged.
//
// A PUSH THAT CANNOT PASS STOPS ITS GRADLE RUN, OR NEVER STARTS IT. The legs that read no code (title, attribution, no-Python,
// ast-grep walls) run first, and gradle starts only once they pass: stopping a run also ends the warm pre-push daemon. A direct
// leg that fails, or a branch that moved (looked at before gradle starts and every 20 s while it runs), fails the push however
// gradle ends, so the gate stops gradle at once, or never starts it, and reports. A gradle red is never stopped: --continue
// lists every failure at once. Each judged push appends one line to ~/.local/state/splice-gate/
// timings.jsonl (prepush-timing.ts): sha, scope, gradle seconds, configuration seconds, the ten slowest tasks, verdict.
//
// NOTHING HERE IS SKIPPABLE. There is no environment switch and no flag. `--no-verify` is the only bypass, and
// it is forbidden to seats.
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative, resolve } from "node:path";
import { astGrepBin } from "../lib/astgrep.ts";
import { resolveJdk21 } from "../lib/jdk.ts";
import { type Layout, layout } from "../lib/repo.ts";
import { acquireRunSentinel, describeOpenRun } from "../lib/sentinel.ts";
import { parseModuleGraph } from "../lib/selector.ts";
import { ktlintOver, type LintVerdict, lintable, type StagedLint, stagedLinters } from "../lib/lint-staged.ts";
import { preparePrePushTree, type PrePushTree, takePrePushTreeNow } from "../lib/prepush-tree.ts";
import { type Lane, type LaneOptions, type LaneOutcome, openLane } from "../lib/push-lane.ts";
import { APP_CHECK, PACKAGED_TEST, packagedTestReason } from "../lib/packaged-worker.ts";
import { appendPushTiming, type PushTiming, readProfile } from "../lib/prepush-timing.ts";
import { isoSeconds } from "../lib/slot.ts";
import { exitStatusOf } from "../lib/status.ts";
import { narrowBunTest } from "../lib/test-select.ts";
import { commitLegs, type Leg, legsWithoutInputs, prePushScope } from "../lib/prepush-scope.ts";
import { cancelledBySignal, RUN_ALREADY_OPEN_EXIT } from "./run.ts";
import { title } from "./title.ts";

export const usage =
  "hook <pre-commit|pre-push|install>  the git hooks: pre-commit runs the walls and judges the commit's Kotlin, pre-push the worktree; install writes the shims";

export const SHIM_BEGIN = "# >>> splice gate hook >>> written by `bun tools/gate hook install`; reinstall replaces it";
export const SHIM_END = "# <<< splice gate hook <<<";
export const HOOK_VERBS = ["pre-commit", "pre-push"] as const;
const ZERO_SHA = /^0+$/;
const LADDER = "tools/gate/config/ladder.json";
const KOTLIN = /\.kts?$/;
/** A root script: configuration evaluates it, so the `help` task checks it. */
const ROOT_SCRIPT = /^[^/]+\.gradle\.kts$/;
/** build-logic's own sources: main is what its compile checks, test is what its test compile checks, and its build
 *  scripts are checked by the configuration pass. Any other path under build-logic has no check. */
const BUILD_LOGIC_MAIN = /^build-logic\/src\/main\//;
const BUILD_LOGIC_TEST = /^build-logic\/src\/test\//;
const BUILD_LOGIC_SCRIPT = /^build-logic\/[^/]+\.gradle\.kts$/;
const ROOT_SCRIPT_TASKS = ["help"];
const FAILED_TASK = /^> Task (\S+) FAILED$/gm;
const TASK_LINE = /^> Task (\S+)/gm;
const LINES_SHOWN_ON_FAILURE = 60;
const CLASS_DIRS = ["kotlin/main", "kotlin/test", "kotlin/testFixtures", "java/main", "java/test", "java/testFixtures"];

/** A test task that died reading the results another run is writing (`java.io.EOFException`, or a results file a
 *  parallel run removed). Gradle prints no path for EOF without --stacktrace, so the task is what the rule names. */
const TEST_RESULT_COLLISION =
  /Execution failed for task '(:[^']*test[^']*)'[^\n]*\n> (?:java\.io\.EOFException|java\.nio\.file\.NoSuchFileException: [^\n]*test-results\/[^\n]*)/i;
/** Gradle's shared-file lock, held by another gradle process in the same checkout. */
const LOCK_TIMEOUT = /Timeout waiting to lock/;
const MISSING_CLASS = /NoClassDefFoundError: ([\w$./]+)/g;

export interface Finding {
  severity?: string;
  ruleId?: string;
  file?: string;
  message?: string;
}

export interface GradleModule {
  readonly path: string;
  readonly dir: string;
}

export interface GateRun {
  readonly status: number;
  readonly output: string;
}

/** Runs gradle tasks and returns their exit and output. The default runs them through the gate's slot. */
/** Runs gradle tasks. [root] is the tree they run in; a runner built for one tree may ignore it. [cancel] ends the run at
 *  once: the runner stops gradle and returns the exit of a run ended by a signal (128+signum). [options] are the gradle
 *  options the judgement adds to this run, such as a task it excludes. */
export type GateRunner = (tasks: readonly string[], root?: string, cancel?: AbortSignal, options?: readonly string[]) => Promise<GateRun>;

export interface HookDeps {
  readonly gate?: GateRunner;
  /** Where each judged push's timing line goes. The CLI passes the real file; a judgement given no sink records nothing. */
  readonly timings?: (entry: PushTiming) => void;
  /** How often the branch is looked at while gradle runs, to stop a run for a push that can no longer succeed. */
  readonly movedPollMs?: number;
  /** ktlint over the files a push changes, in one run. Given, the push lints those files here and gradle skips its per-module
   *  ktlintCheck; absent, gradle lints every module in scope, as it did before the batch. */
  readonly ktlint?: (root: string, files: readonly string[]) => Promise<LintVerdict>;
  readonly openRun?: (head: string, tree: string) => ReturnType<typeof acquireRunSentinel>;
  /** The ladder rows pre-push scopes by. Read from the checkout when absent. */
  readonly legs?: readonly Leg[];
  /** How the commit is checked out for judgement. The default is the persistent pre-push build tree under the git directory. */
  readonly tree?: (repoRoot: string, sha: string) => PrePushTree;
  /** Whether another gradle process is live: the evidence a collision needs. The default reads the process list. */
  readonly rivalLive?: () => boolean;
  /** The linters pre-commit runs over the staged Kotlin. The real ones when absent. */
  readonly lint?: StagedLint;
  /** Who a push in the push lane is, how the lane tells a queued push still runs, and how often it looks. */
  readonly lane?: LaneOptions;
}

export interface Judged extends GateRun {
  /** The first run collided and the tasks ran once more. */
  readonly reran: boolean;
  /** The rerun collided too. */
  readonly collidedAgain: boolean;
}

function git(root: string, args: readonly string[]): { status: number; stdout: Buffer; stderr: string } {
  const proc = spawnSync("git", [...args], { cwd: root, maxBuffer: 1 << 30 });
  return { status: proc.status ?? 1, stdout: proc.stdout ?? Buffer.alloc(0), stderr: proc.stderr?.toString() ?? "" };
}

function gitText(root: string, args: readonly string[]): string {
  const r = git(root, args);
  if (r.status !== 0) throw new Error(`git ${args.join(" ")} failed: ${r.stderr.trim()}`);
  return r.stdout.toString("utf8").trim();
}

function gitPaths(root: string, args: readonly string[]): string[] {
  const r = git(root, args);
  if (r.status !== 0) throw new Error(`git ${args.join(" ")} failed: ${r.stderr.trim()}`);
  return r.stdout.toString("utf8").split("\0").filter((p) => p.length > 0);
}

/** Every path this commit changes, read from the index git is writing (GIT_INDEX_FILE when git sets one): added, copied,
 *  modified, type-changed and deleted. A rename is read as the two paths it moves between, so the module it leaves is
 *  checked too. */
export function changedPaths(root: string): string[] {
  return gitPaths(root, ["diff", "--cached", "--name-only", "--no-renames", "--diff-filter=ACMRTD", "-z"]);
}

/** The worktree's paths that differ from HEAD, tracked or untracked: the uncommitted work of every seat. */
export function dirtyPaths(root: string): string[] {
  const tracked = gitPaths(root, ["diff", "--name-only", "-z", "HEAD"]);
  const untracked = gitPaths(root, ["ls-files", "--others", "--exclude-standard", "-z"]);
  return [...new Set([...tracked, ...untracked])].sort();
}

/** The entries the index holds: `path -> "<mode> <blob>"`. */
function indexEntries(root: string): Map<string, string> {
  const entries = new Map<string, string>();
  for (const entry of gitPaths(root, ["ls-files", "-s", "-z"])) {
    const tab = entry.indexOf("\t");
    const [mode = "", blob = ""] = entry.slice(0, tab).split(" ");
    entries.set(entry.slice(tab + 1), `${mode} ${blob}`);
  }
  return entries;
}

/** A staged blob, exactly as the commit holds it. The walls read the index, never the worktree. */
function indexBlob(root: string, path: string): Buffer {
  const r = git(root, ["show", `:${path}`]);
  if (r.status !== 0) throw new Error(`cannot read ${path} from the index: ${r.stderr.trim()}`);
  return r.stdout;
}

/** The staged blobs written under a temp dir at their repo-relative paths, so each rule's files:/ignores: bind. */
function mirror(root: string, paths: readonly string[]): string {
  const dir = mkdtempSync(join(tmpdir(), "splice-precommit-"));
  for (const path of paths) {
    const target = join(dir, path);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, indexBlob(root, path));
  }
  return dir;
}

/** ast-grep's verdict. It prints the match list and exits 1 when a match is an error, 0 otherwise. A list is a
 *  verdict only with exit 0, or with exit 1 and at least one match; any other exit, or no parsed list, did not judge. */
export function parseScan(status: number, stdout: string, stderr: string): Finding[] {
  const text = stdout.trim();
  let parsed: unknown;
  try {
    parsed = text === "" ? undefined : JSON.parse(text);
  } catch {
    parsed = undefined;
  }
  if (!Array.isArray(parsed)) throw new Error(`ast-grep exited ${status} without a match list: ${stderr.trim() || "no stderr"}`);
  if (status === 0 || (status === 1 && parsed.length > 0)) return parsed as Finding[];
  throw new Error(`ast-grep exited ${status} with ${parsed.length} match(es): not a verdict it prints`);
}

/** The walls over the mirrored files, with the repository's sgconfig. */
export function scanMirror(root: string, dir: string, targets: readonly string[]): Finding[] {
  const proc = spawnSync(astGrepBin(root), ["scan", "--config", join(root, "sgconfig.yml"), "--json=compact", ...targets], {
    cwd: dir,
    encoding: "utf8",
    maxBuffer: 1 << 28,
  });
  if (proc.error) throw proc.error;
  return parseScan(proc.status ?? -1, proc.stdout ?? "", proc.stderr ?? "");
}

/** The gradle projects of settings.gradle.kts, each with its directory. */
export function gradleModules(root: string): GradleModule[] {
  const settings = join(root, "settings.gradle.kts");
  if (!existsSync(settings)) return [];
  const re = /project\("([^"]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)/g;
  const modules: GradleModule[] = [];
  for (const m of readFileSync(settings, "utf8").matchAll(re)) {
    modules.push({ path: m[1] ?? "", dir: (m[2] ?? "").replace(/\/$/, "") });
  }
  return modules;
}

/** The gradle project a repo-relative file belongs to: the module with the longest directory prefix. */
export function moduleOf(modules: readonly GradleModule[], file: string): string | undefined {
  let best: GradleModule | undefined;
  for (const m of modules) {
    if (file.startsWith(`${m.dir}/`) && (best === undefined || m.dir.length > best.dir.length)) best = m;
  }
  return best?.path;
}

/** The gradle tasks that check one Kotlin file, or undefined when no check covers it. */
export function checksFor(modules: readonly GradleModule[], file: string): string[] | undefined {
  const module = moduleOf(modules, file);
  if (module !== undefined) return [`${module}:compileKotlin`, `${module}:compileTestKotlin`, `${module}:detekt`, `${module}:ktlintCheck`];
  if (BUILD_LOGIC_MAIN.test(file)) return ["build-logic:compileKotlin"];
  if (BUILD_LOGIC_TEST.test(file)) return ["build-logic:compileTestKotlin"];
  if (BUILD_LOGIC_SCRIPT.test(file)) return ["build-logic:help"];
  if (file.startsWith("build-logic/")) return undefined;
  if (ROOT_SCRIPT.test(file)) return [...ROOT_SCRIPT_TASKS];
  return undefined;
}

/** The repo-relative files a gradle or detekt output names, so a failure can be placed on a file. */
export function namedFiles(output: string, root: string): Set<string> {
  const named = new Set<string>();
  for (const m of output.matchAll(/(?:file:\/\/)?([^\s:]+\.kts?):\d+/g)) {
    const abs = resolve(root, m[1] ?? "");
    if (abs.startsWith(`${root}/`)) named.add(relative(root, abs));
  }
  return named;
}

/** The gradle tasks a run reports as FAILED, by path (`:core:compileKotlin`). */
export function failedTasks(output: string): string[] {
  return [...output.matchAll(FAILED_TASK)].map((m) => m[1] ?? "");
}

/** A gradle wrapper or launcher process: the one kind of process another gradle run in this checkout can be. */
const GRADLE_PROCESS = /GradleWrapperMain|gradle-wrapper\.jar|org\.gradle\.launcher\./;

/** Whether another gradle process is live on this machine, the evidence an EOF or a missing results file needs before
 *  it is taken for another run's collision. Read from the process list, every process but this one. A list that cannot
 *  be read is no evidence. */
export function liveGradleElsewhere(): boolean {
  const listed = spawnSync("ps", ["-eo", "pid=,args="], { encoding: "utf8" });
  if (listed.status !== 0) return false;
  return listed.stdout.split("\n").some((line) => {
    const fields = /^\s*(\d+)\s+(.*)$/.exec(line);
    return fields !== null && Number(fields[1]) !== process.pid && GRADLE_PROCESS.test(fields[2] ?? "");
  });
}

/** Whether a red run is a collision: a shared file another gradle run in this checkout held, not a red of the code. A
 *  lock timeout names its holder itself. A test task's EOF or missing results file, and a NoClassDefFoundError for a
 *  class whose .class file is on disk, are collisions only while [rivalLive] says another gradle process is live. */
export function isCollision(
  output: string,
  root: string,
  modules: readonly GradleModule[],
  rivalLive: () => boolean = liveGradleElsewhere,
): boolean {
  if (LOCK_TIMEOUT.test(output)) return true;
  if (TEST_RESULT_COLLISION.test(output)) return rivalLive();
  for (const m of output.matchAll(MISSING_CLASS)) {
    const classFile = `${(m[1] ?? "").replaceAll(".", "/")}.class`;
    if (modules.some((mod) => CLASS_DIRS.some((dir) => existsSync(join(root, mod.dir, "build", "classes", dir, classFile))))) {
      return rivalLive();
    }
  }
  return false;
}

/** Runs the tasks; a collision reruns the ones that failed or were never reached, once. A red after the rerun is the
 *  answer. A run ended by a signal is cancelled, not a collision, and is not rerun (run.ts ends a cancelled gate the
 *  same way); neither is a run whose [cancel] was raised, whatever it printed. */
export async function judgedRun(
  run: GateRunner,
  root: string,
  modules: readonly GradleModule[],
  tasks: readonly string[],
  rivalLive: () => boolean = liveGradleElsewhere,
  cancel?: AbortSignal,
  options: readonly string[] = [],
): Promise<Judged> {
  const first = await run(tasks, root, cancel, options);
  if (first.status === 0 || cancelledBySignal(first.status) || cancel?.aborted || !isCollision(first.output, root, modules, rivalLive)) {
    return { ...first, reran: false, collidedAgain: false };
  }
  const again = rerunTasks(tasks, first.output);
  // Every requested task printed its result, so the collision kept none of them from judging: there is nothing to rerun.
  if (again.length === 0) return { ...first, reran: false, collidedAgain: false };
  console.error(`  ! collision: another gradle run in this checkout held a shared file; rerunning ${again.length} task(s) once`);
  const second = await run(again, root, cancel, options);
  const collidedAgain = second.status !== 0 && !cancelledBySignal(second.status) && isCollision(second.output, root, modules, rivalLive);
  return { ...second, reran: true, collidedAgain };
}

/** The tasks a collision's rerun runs: the requested tasks that failed, and those with no line in the output, each with
 *  the options that follow it (an option belongs to the task before it). Gradle's --continue prints nothing for a task a
 *  failure kept from running, so a task with no line was never judged. A run that printed a result for every task names
 *  none, and the rerun is empty: nothing a collision could have kept from judging is left to judge. */
export function rerunTasks(requested: readonly string[], output: string): string[] {
  const failed = new Set(failedTasks(output).map(taskPath));
  const reached = new Set([...output.matchAll(TASK_LINE)].map((m) => taskPath(m[1] ?? "")));
  const again: string[] = [];
  let inUnit = false;
  for (const token of requested) {
    if (!token.startsWith("-")) inUnit = failed.has(taskPath(token)) || !reached.has(taskPath(token));
    if (inUnit) again.push(token);
  }
  return again;
}

/** Gradle prints a task's path with its leading colon; a requested name may omit it. */
function taskPath(task: string): string {
  return task.startsWith(":") ? task : `:${task}`;
}

/** What a red judgement adds to its message: a cancellation, or a collision that came again on the rerun. */
function redNote(judged: Judged): string {
  if (cancelledBySignal(judged.status)) return ` (cancelled: gradle ended by a signal, exit ${judged.status})`;
  return judged.collidedAgain ? " (a collision again on the rerun)" : "";
}

/** The seat that holds a repo-relative file's lock in .git/seat-locks, or "no seat lock". */
export function seatOwner(root: string, path: string): string {
  const commonDir = resolve(root, gitText(root, ["rev-parse", "--git-common-dir"]));
  const owner = join(commonDir, "seat-locks", path.replaceAll("/", "%"), "owner");
  return existsSync(owner) ? readFileSync(owner, "utf8").trim() : "no seat lock";
}

/** What a red run names: its failing tasks, and each file the output names with the seat that holds it. */
export function failureLines(root: string, output: string): string[] {
  const lines: string[] = [];
  const failed = failedTasks(output);
  if (failed.length > 0) lines.push(`  failing task(s): ${failed.join(", ")}`);
  const named = [...namedFiles(output, root)].sort();
  for (const file of named) lines.push(`  ✗ ${file} — seat lock: ${seatOwner(root, file)}`);
  if (named.length === 0) lines.push("  (the output names no file)");
  return lines;
}

/** A child gate process's environment. A hook's GIT_* variables stay out of it: GIT_INDEX_FILE names the commit's
 *  temporary index, and gradle's own git calls must read the worktree. */
function spawnEnv(extra: Record<string, string>): Record<string, string> {
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(Bun.env)) if (typeof value === "string" && !key.startsWith("GIT_")) env[key] = value;
  return { ...env, ...extra };
}

/** The pre-push run's own options. --continue runs every module's suites past a failure, so a red push names every
 *  failure at once instead of one per 15 to 45 minute run (splice-lead, Oct 12); the push still fails on any of them.
 *  --profile writes build/reports/profile/: configuration time and each task's time, which the timing line of the push reads. */
const PRE_PUSH_GRADLE_OPTIONS = ["--parallel", "--build-cache", "--continue", "--profile"];
/** How often the branch is looked at while gradle runs: a push whose branch moved is refused at the end, so its run stops. */
const MOVED_POLL_MS = 20_000;
/** The ladder legs that read no code: the commit's title, its attribution trailers, the no-Python wall and the ast-grep walls. They
 *  run before gradle starts, and gradle starts only once they pass. simon measured them at 6 to 22 s. Started beside gradle, a red one
 *  stopped the run, and the stop also ended the warm pre-push daemon: four ended between 9:00 and 9:43 PM CT on Oct 19, three logging
 *  "cancel requested but timed out", and a cold daemon costs a guessed 50 to 150 CPU-seconds (temple plan 2026-10-19, cut 3). */
const NO_CODE_LEGS: ReadonlySet<string> = new Set(["prTitle", "attribution", "noPython", "gateRules"]);

/** The ktlint tasks a module's `check` runs, by name. The plugin wires `check` to one task per source set and to the script task,
 *  never to the `ktlintCheck` aggregate (the dry run of the pre-push task list shows no aggregate in the graph), so excluding the
 *  aggregate alone excluded nothing. Gradle's `--exclude-task` also drops the tasks only an excluded one reaches
 *  (runKtlintCheckOver..., loadKtlintReporters) and keeps any another task needs. A source set missing from this list keeps its
 *  gradle lint, so the list can fall behind without losing coverage; it cannot lint less than it did. */
const KTLINT_TASKS = [
  "ktlintCheck",
  "ktlintMainSourceSetCheck",
  "ktlintTestSourceSetCheck",
  "ktlintTestFixturesSourceSetCheck",
  "ktlintLawFixturesSourceSetCheck",
  "ktlintKotlinScriptCheck",
];
/** The batched ktlint, as a failed leg is named in the verdict. */
const KTLINT_LEG: Leg = { task: "ktlint", command: ["ktlint"] };

/** Why a push cannot take the batched ktlint, or undefined when it can. ktlint's verdict on a file is a function of the file's
 *  bytes, the rule file and ktlint's version, so a file the push did not change keeps the verdict it had when it last passed,
 *  unless one of those moved: any .editorconfig, the catalog that names ktlint's version, or build-logic, which configures the
 *  plugin. Then gradle lints every module in scope, as it always did. */
export function ktlintFullRunReason(changed: readonly string[]): string | undefined {
  if (changed.some((path) => path === ".editorconfig" || path.endsWith("/.editorconfig"))) return ".editorconfig changed";
  if (changed.includes("gradle/libs.versions.toml")) return "the version catalog changed";
  if (changed.some((path) => path.startsWith("build-logic/"))) return "build-logic changed";
  return undefined;
}

/** Gradle tasks through the gradle slot (one gradle per tree). The slot is the gate's own verb, so the lock is the
 *  gate's lock. Output is echoed to stderr as it arrives when [echo] is set. [extraOptions] go beside the pre-push options on
 *  every run, a collision's rerun included, and so do the [options] a run is handed. A raised [cancel] sends the slot SIGTERM:
 *  it forwards the signal to gradle's process group and holds the slot until the group is gone (runUnderSlot). */
export function slotRunner(lay: Layout, label: string, echo: boolean, extraOptions: readonly string[] = []): GateRunner {
  return async (tasks, _root, cancel, options = []) => {
    const jdk = resolveJdk21();
    if ("error" in jdk) throw new Error(jdk.error);
    const proc = Bun.spawn(
      [
        process.execPath,
        join(lay.repoRoot, "tools", "gate", "index.ts"),
        "slot",
        label,
        "--",
        ...(label === "pre-push" ? [...PRE_PUSH_GRADLE_OPTIONS, ...extraOptions] : []),
        ...options,
        ...tasks,
      ],
      {
        cwd: lay.repoRoot,
        env: spawnEnv({
          JAVA_HOME: jdk.javaHome,
          // The pre-push tree takes its own lock beside its build root, whatever sha the tree holds: the slot code inside an
          // older tree would pick the checkout's shared lock, and a push would block every builder in the checkout.
          ...(label === "pre-push" ? { GRADLE_SLOT_LOCK: join(lay.buildRoot, ".gradle-slot.lock") } : {}),
        }),
        stdout: "pipe",
        stderr: "pipe",
      },
    );
    const stop = () => proc.kill("SIGTERM");
    if (cancel?.aborted) stop();
    else cancel?.addEventListener("abort", stop, { once: true });
    let output = "";
    const pump = async (stream: ReadableStream<Uint8Array>): Promise<void> => {
      const decoder = new TextDecoder();
      for await (const chunk of stream) {
        const text = decoder.decode(chunk, { stream: true });
        output += text;
        if (echo) process.stderr.write(text);
      }
    };
    await Promise.all([pump(proc.stdout), pump(proc.stderr)]);
    await proc.exited;
    cancel?.removeEventListener("abort", stop);
    // A slot killed before its handlers were on dies of the signal and has no exit code: read it as a shell does (143).
    return { status: exitStatusOf(proc), output };
  };
}

function seconds(started: number): string {
  return `${((performance.now() - started) / 1000).toFixed(1)} s`;
}

function tailOf(output: string): string {
  return output.split("\n").slice(-LINES_SHOWN_ON_FAILURE).join("\n");
}

/** The ladder legs a commit can turn red and that need no gradle: every row whose `commit` globs match a path this commit
 *  changes runs here, from the repository root. A touched path already equals the index (breaches, in preCommit), so the
 *  worktree these legs read holds the commit's bytes for it. An unreadable ladder fails the commit: no leg could be chosen. */
export async function commitLegsLeg(lay: Layout, deps: HookDeps = {}): Promise<number> {
  const started = performance.now();
  let legs: readonly Leg[];
  try {
    legs = deps.legs ?? readLadder(lay.repoRoot);
  } catch (error) {
    console.error(`pre-commit: ✗ cannot choose the ladder legs: ${errorText(error)}`);
    return 1;
  }
  const due = commitLegs(legs, changedPaths(lay.repoRoot));
  if (due.length === 0) return 0;
  console.error(`── ladder legs this commit can turn red: ${due.map((leg) => leg.task).join(", ")} ──`);
  const failed = await runDirectLegs(lay.repoRoot, due.map((leg) => ({ ...leg, command: leg.commitCommand ?? leg.command })));
  if (failed.length > 0) {
    console.error(`pre-commit: ✗ ${failed.map((leg) => leg.task).join(", ")} — ${seconds(started)}`);
    return 1;
  }
  return 0;
}

/** The pre-commit verb: the ladder legs the commit's paths trigger, then the commit's Kotlin judgement. A
 *  refusal stops it before gradle. */
export async function commitGate(lay: Layout, deps: HookDeps = {}): Promise<number> {
  const legs = await commitLegsLeg(lay, deps);
  if (legs !== 0) return legs;
  return preCommit(lay, deps.lint ?? stagedLinters(lay.repoRoot));
}

/** The pre-commit judgement of this commit. Returns the exit code. */
export async function preCommit(lay: Layout, lint?: StagedLint): Promise<number> {
  const started = performance.now();
  const root = lay.repoRoot;
  const changed = changedPaths(root);
  const touched = changed.filter((p) => KOTLIN.test(p));
  if (touched.length === 0) {
    console.error("pre-commit: no Kotlin in this commit; nothing to judge");
    return 0;
  }
  console.error(`══ pre-commit ══  ${touched.length} Kotlin file(s) in this commit`);
  // The walls scan the bytes a commit holds: a deletion has none to scan. Module selection covers every change.
  const indexed = indexEntries(root);
  const kotlin = touched.filter((p) => indexed.has(p));
  if (kotlin.length > 0) {
    const dir = mirror(root, kotlin);
    let findings: Finding[];
    let linted: LintVerdict | undefined;
    try {
      findings = scanMirror(root, dir, kotlin);
      const sources = kotlin.filter(lintable);
      if (lint !== undefined && sources.length > 0) linted = await lint(dir, sources);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
    const errors = findings.filter((f) => f.severity === "error");
    for (const f of errors) console.error(`  ✗ ${f.ruleId}  ${f.file}: ${f.message ?? ""}`);
    if (errors.length > 0) {
      console.error(`pre-commit: ✗ walls (${errors.length} error finding(s)) — ${seconds(started)}`);
      return 1;
    }
    console.error("  ✓ walls");
    if (linted !== undefined) {
      if ("error" in linted) {
        console.error(`  ✗ ktlint/detekt could not judge: ${linted.error}`);
        console.error(`pre-commit: ✗ lint — ${seconds(started)}`);
        return 1;
      }
      for (const f of linted.findings) console.error(`  ✗ ${f.tool}  ${f.line}`);
      if (linted.findings.length > 0) {
        console.error(`pre-commit: ✗ ktlint/detekt (${linted.findings.length} finding(s) in the staged files) — ${seconds(started)}`);
        return 1;
      }
      console.error("  ✓ ktlint, detekt");
    }
  }

  const modules = gradleModules(root);
  // A deleted file needs no check, and a deleted module maps none: only a file the commit still holds must be covered.
  const unmapped = kotlin.filter((path) => checksFor(modules, path) === undefined);
  if (unmapped.length > 0) {
    for (const path of unmapped) console.error(`  ✗ ${path}: no gradle check covers this file`);
    console.error(`pre-commit: ✗ ${unmapped.length} Kotlin file(s) with no check — ${seconds(started)}`);
    return 1;
  }

  // No gradle here: the compile, detekt and law legs this hook once ran are pre-push's, which compiles every module, runs `check`
  // for each module the push changes and requests lawSuites. This hook is the fast tier and finishes within a minute.
  console.error(`pre-commit: PASS — ${seconds(started)}`);
  return 0;
}

/** The pre-push judgement of the pushed tip against the worktree. [stdin] is git's ref list:
 *  `<local ref> <local sha> <remote ref> <remote sha>`. */
export async function prePush(lay: Layout, stdin: string, deps: HookDeps = {}, remoteName: string = "origin"): Promise<number> {
  // The release train (after 0.4.0; `bun tools/release train`): checks run in the train's rolling check, so pre-push
  // judges nothing once this checkout's `git config splice.train` is "on". Unset it and pre-push judges again.
  if (git(lay.repoRoot, ["config", "--get", "splice.train"]).stdout.toString().trim() === "on") {
    console.error("pre-push: the release train checks this branch (bun tools/release train); nothing to judge here");
    return 0;
  }
  const started = performance.now();
  const refs = pushedRefs(stdin);
  if (refs.length === 0) {
    console.error("pre-push: only deletions; nothing to judge");
    return 0;
  }
  // One update of a branch the remote has queues in the push lane (push-lane.ts), which judges only the newest queued push.
  const only = refs.length === 1 ? refs[0] : undefined;
  const take = (sha: string): PrePushTree | undefined => (deps.tree === undefined ? takePrePushTreeNow(lay.repoRoot, sha) : deps.tree(lay.repoRoot, sha));
  const lane =
    only === undefined ? undefined : openLane(lay.repoRoot, { remoteName, remoteRef: only.remoteRef, tip: only.tip, remote: only.remote }, take, deps.lane);
  let verdict = 1;
  try {
    verdict = await judgeTips(lay, refs, deps, started, remoteName, lane);
    return verdict;
  } finally {
    if (verdict === 0) lane?.passed();
    else lane?.leave();
  }
}

async function judgeTips(lay: Layout, refs: readonly PushedRef[], deps: HookDeps, started: number, remoteName: string, lane: Lane | undefined): Promise<number> {
  // Each distinct tip is judged on its own, as a clean checkout of that commit.
  for (const tip of [...new Set(refs.map((ref) => ref.tip))]) {
    const code = await judgeTip(lay, tip, refs.filter((ref) => ref.tip === tip), refs, deps, started, remoteName, lane);
    if (code !== 0) return code;
  }
  // The last look, right before the hook hands git its verdict: every ref git named, not only the tip just judged. A tip
  // judged first can be passed by a peer's commit while a later tip is still being judged.
  const moved = movedRefs(lay.repoRoot, refs);
  if (moved.length > 0) {
    console.log("PRE-PUSH: FAIL — the branch moved after the gate judged");
    console.error(`pre-push: ✗ the branch moved while the gate judged: ${moved.join("; ")}`);
    console.error(`pre-push: name the sha and git sends exactly what the gate judged: ${explicitPush(remoteName, refs)}`);
    return 1;
  }
  return 0;
}

/** Judges one pushed commit in the pre-push build tree. The shared checkout is never read: its uncommitted edits belong to
 *  other seats, and a seat's half-finished work must not redden another seat's push. */
async function judgeTip(
  lay: Layout,
  tip: string,
  refs: readonly PushedRef[],
  pushed: readonly PushedRef[],
  deps: HookDeps,
  started: number,
  remoteName: string,
  lane?: Lane,
): Promise<number> {
  const subject = gitText(lay.repoRoot, ["log", "-1", "--format=%s", tip]);
  console.error(`── pr title (${subject}) ──`);
  if ((await title([subject])) !== 0) {
    console.error("pre-push: ✗ pr title");
    return 1;
  }

  let tree: PrePushTree;
  const waitStarted = performance.now();
  try {
    if (lane === undefined) tree = (deps.tree ?? preparePrePushTree)(lay.repoRoot, tip);
    else {
      const turn = await lane.queue();
      if (turn.kind !== "judge") return laneEnd(lay.repoRoot, turn, tip, refs[0], remoteName);
      tree = turn.tree;
    }
  } catch (error) {
    console.error(`pre-push: ✗ cannot check the pushed commit out: ${errorText(error)}`);
    return 1;
  }
  console.error(`pre-push timing: build tree ${(tree.setupMs / 1000).toFixed(1)} s to move to the sha, ${((performance.now() - waitStarted - tree.setupMs) / 1000).toFixed(1)} s waiting for it`);
  try {
    const code = await judgeIn(lay, tree.path, tip, refs, pushed, deps, started, remoteName);
    // A push in the lane is marked passed before its tree frees, so a push queued beside it waits for git to send this one,
    // rather than take the tree and judge a push the remote would then refuse.
    if (code === 0) lane?.passed();
    return code;
  } finally {
    tree.release();
  }
}

/** The verdict of a push the lane ends before any judgement. Either its commit is on the remote branch already, carried there by a
 *  newer push, or the branch moved past the old value git will send, so the remote would refuse it. Git has nothing it can send
 *  either way, so both exit 1. */
function laneEnd(repoRoot: string, turn: Exclude<LaneOutcome, { kind: "judge" }>, tip: string, ref: PushedRef | undefined, remoteName: string): number {
  const branch = `${printableRemote(remoteName)}/${(ref?.remoteRef ?? "").replace(/^refs\/heads\//, "")}`;
  if (turn.kind === "carried") {
    console.log(`PRE-PUSH: CARRIED — ${tip.slice(0, 9)} is on ${branch} already, inside ${turn.by.slice(0, 9)}, which another queued push sent; nothing is left to send`);
    return 1;
  }
  console.log(`PRE-PUSH: FAIL — ${branch} moved to ${turn.now.slice(0, 9)} while this push waited, so the remote would refuse its old value ${(ref?.remote ?? "").slice(0, 9)}`);
  const onTop = git(repoRoot, ["merge-base", "--is-ancestor", turn.now, tip]).status === 0;
  console.error(
    onTop
      ? `pre-push: push again: ${tip.slice(0, 9)} already holds ${turn.now.slice(0, 9)}, and a new push starts from it`
      : `pre-push: fetch, put ${tip.slice(0, 9)} on top of ${turn.now.slice(0, 9)}, and push again`,
  );
  return 1;
}

async function judgeIn(
  lay: Layout,
  root: string,
  head: string,
  refs: readonly PushedRef[],
  pushed: readonly PushedRef[],
  deps: HookDeps,
  started: number,
  remoteName: string,
): Promise<number> {
  const judgeLay: Layout = { repoRoot: root, buildRoot: root };
  let changed: string[];
  let legs: readonly Leg[];
  try {
    changed = pushedPaths(lay.repoRoot, refs);
    legs = deps.legs ?? readLadder(root);
  } catch (error) {
    console.error(`pre-push: ✗ cannot scope the push: ${errorText(error)}`);
    return 1;
  }
  const missing = legsWithoutInputs(legs);
  if (missing.length > 0) {
    console.error(`pre-push: ✗ ladder rows declare no inputs, so the push cannot be scoped: ${missing.join(", ")}`);
    return 1;
  }
  const modules = gradleModules(root);
  const graph = readModuleGraph(root);
  const scope = prePushScope({
    legs,
    modules: modules.map((module) => module.path),
    moduleOf: (file) => moduleOf(modules, file),
    changed,
    graph,
  });
  // The packaged code-mode test reruns the worker's suites against the fat jar. A push that cannot change what the worker loads
  // from that jar skips it, and the verdict says so; one that can runs it, and the log names the path that decided.
  const packaged = scope.gradle.includes(APP_CHECK) ? packagedVerdict(lay.repoRoot, root, refs, modules, graph, changed) : undefined;
  const skipPackaged = packaged?.runs === false;
  const scopeClause = `; scope: ${scope.summary}${skipPackaged ? `; ${PACKAGED_TEST} skipped: ${PACKAGED_SKIPPED}` : ""}`;
  console.error(`── scope: ${scope.summary} ──`);
  if (packaged !== undefined) console.error(`── ${PACKAGED_TEST} ${packaged.runs ? `runs: ${packaged.why}` : `skipped: ${PACKAGED_SKIPPED}`} ──`);

  // ktlint is not run per module on every push (the plugin's lint step is never cached, so each of up to 31 modules in scope re-linted
  // every source it has, however little the push touched). It runs once over the Kotlin the push changes, in the modules gradle would
  // have linted, unless something that moves ktlint's verdict on an untouched file changed too.
  const fullKtlint = deps.ktlint === undefined ? undefined : ktlintFullRunReason(changed);
  const batchKtlint = deps.ktlint !== undefined && fullKtlint === undefined;
  const ktlintFiles = batchKtlint ? changed.filter((path) => KOTLIN.test(path) && moduleOf(modules, path) !== undefined && existsSync(join(root, path))) : [];
  if (batchKtlint) console.error(`── ktlint: ${ktlintFiles.length} changed Kotlin file(s) in one run; gradle skips its per-module ktlintCheck ──`);
  else if (fullKtlint !== undefined) console.error(`── ktlint: gradle lints every module in scope (${fullKtlint}) ──`);

  const sha = head.slice(0, 7);
  const judgedWhat = `the pushed sha ${sha}, checked out clean in the pre-push build tree`;

  const open = (deps.openRun ?? acquireRunSentinel)(head, root);
  if (open !== null) {
    console.error(`pre-push: refusing — a verdict is already open over the pre-push build tree (${describeOpenRun(open)})`);
    return RUN_ALREADY_OPEN_EXIT;
  }

  // A push that cannot pass never runs gradle to the end. A red direct leg fails the push whatever gradle says, and a branch that
  // moved fails it however gradle ends, so the run's remaining minutes would compute a verdict nobody reads. A gradle red is not
  // such a verdict: --continue lists every failure at once, so a red gradle runs to the end. Gradle first WAITS for the legs that
  // read no code (NO_CODE_LEGS), and a reason heard while it waits keeps it from starting at all: stopping a run that started also
  // ends the warm pre-push daemon.
  const stopper = new AbortController();
  let gradlePhase: "waiting" | "running" | "done" = scope.gradle.length > 0 ? "waiting" : "done";
  let stoppedFor: string | undefined;
  const stopGradle = (reason: string): void => {
    if (stoppedFor !== undefined || gradlePhase === "done") return;
    stoppedFor = reason;
    if (gradlePhase === "waiting") {
      console.error(`  ! ${reason}: gradle will not start, because this push cannot pass`);
      return;
    }
    console.error(`  ! ${reason}: stopping gradle, because this push cannot pass`);
    stopper.abort();
  };

  // A test leg that is one `bun test <dir>` run keeps only the test files the diff can affect.
  const directLegs = scope.direct.flatMap((leg) => narrowBunTest(root, leg, changed) ?? []);
  const noCodeLegs = directLegs.filter((leg) => NO_CODE_LEGS.has(leg.task));
  const codeLegs = directLegs.filter((leg) => !NO_CODE_LEGS.has(leg.task));
  // Every other direct leg runs alongside gradle, so a leg never waits for the slot. The verdict reads both.
  if (codeLegs.length > 0) console.error("── direct legs (no gradle) ──");
  const direct = runDirectLegs(root, codeLegs, (leg) => stopGradle(`${leg.task} failed`));
  const linted =
    deps.ktlint === undefined || ktlintFiles.length === 0 ? Promise.resolve(false) : ktlintLeg(deps.ktlint, root, ktlintFiles, () => stopGradle("ktlint failed"));
  let failedNoCode: Leg[] = [];
  if (noCodeLegs.length > 0) {
    console.error(`── ${noCodeLegs.map((leg) => leg.task).join(", ")}: they read no code, so they pass before gradle starts ──`);
    failedNoCode = await runDirectLegs(root, noCodeLegs);
    if (failedNoCode.length > 0) stopGradle(`${failedNoCode.map((leg) => leg.task).join(", ")} failed`);
  }
  let judged: Judged | undefined;
  let movedWhile: string[] = [];
  let gradleSeconds: number | null = null;
  // The branch is looked at once more before gradle starts: one that moved while the legs ran fails the push however gradle ends.
  if (gradlePhase === "waiting" && stoppedFor === undefined) {
    const movedBefore = movedRefs(lay.repoRoot, pushed);
    if (movedBefore.length > 0) {
      movedWhile = movedBefore;
      stopGradle("the branch moved");
    }
  }
  const notStarted = gradlePhase === "waiting" ? stoppedFor : undefined;
  const gradleStartedAt = Date.now();
  if (gradlePhase === "waiting" && notStarted === undefined) {
    gradlePhase = "running";
    console.error("── gate tier (gradle, scoped to the push) ──");
    const gradleStarted = performance.now();
    const watch = setInterval(() => {
      const moved = movedRefs(lay.repoRoot, pushed);
      if (moved.length === 0) return;
      movedWhile = moved;
      stopGradle("the branch moved");
    }, deps.movedPollMs ?? MOVED_POLL_MS);
    try {
      const runner = deps.gate ?? slotRunner(judgeLay, "pre-push", true, batchKtlint ? KTLINT_TASKS.map((task) => `--exclude-task=${task}`) : []);
      const options = skipPackaged ? [`--exclude-task=${PACKAGED_TEST}`] : [];
      judged = await judgedRun(runner, root, modules, scope.gradle, deps.rivalLive, stopper.signal, options);
    } finally {
      clearInterval(watch);
    }
    gradleSeconds = (performance.now() - gradleStarted) / 1000;
    const waited = /admitted after (\d+)s/.exec(judged.output)?.[1];
    console.error(`pre-push timing: gradle ${seconds(gradleStarted)}${waited === undefined ? "" : `, of which ${waited} s waiting for the build lock`}`);
  }
  gradlePhase = "done";
  const failedLegs = [...failedNoCode, ...(await direct), ...((await linted) ? [KTLINT_LEG] : [])];
  const elapsed = seconds(started);
  // Gradle ended by OUR stop is cancelled, not red: only a run that ended by itself, or by a signal that is not ours, is judged.
  const gradleCancelled = stoppedFor !== undefined && judged !== undefined && cancelledBySignal(judged.status);
  const gradleRed = judged !== undefined && judged.status !== 0 && !gradleCancelled;
  const red = [...failedLegs.map((leg) => leg.task), ...(gradleRed ? ["gradle"] : [])];
  const moved = movedWhile.length > 0 ? movedWhile : red.length > 0 ? [] : movedRefs(lay.repoRoot, pushed);
  const stoppedNote = gradleCancelled
    ? ` (gradle stopped after ${(gradleSeconds ?? 0).toFixed(1)} s: ${stoppedFor})`
    : notStarted !== undefined
      ? ` (gradle not started: ${notStarted})`
      : "";
  const record = (verdict: string): void => {
    if (deps.timings === undefined) return;
    const profile = judged === undefined ? { configS: null, tasks: [] } : readProfile(root, gradleStartedAt);
    deps.timings({
      at: isoSeconds(new Date()),
      sha: head,
      scope: scope.summary,
      gradleS: gradleSeconds === null ? null : Math.round(gradleSeconds * 10) / 10,
      ...profile,
      cancelled: gradleCancelled ? (stoppedFor ?? null) : null,
      verdict,
    });
  };
  if (red.length === 0 && moved.length > 0) {
    record("fail:moved");
    console.log(`PRE-PUSH: FAIL — judged ${judgedWhat}${scopeClause}`);
    console.error(`pre-push: ✗ the branch moved while the gate judged: ${moved.join("; ")}${stoppedNote}`);
    console.error(`pre-push: name the sha and git sends exactly what the gate judged: ${explicitPush(remoteName, pushed)}`);
    return 1;
  }
  if (red.length === 0) {
    record("pass");
    const rerun = judged?.reran ? "; passed on the rerun after a collision" : "";
    console.log(`PRE-PUSH: PASS — judged ${judgedWhat}${rerun}${scopeClause}`);
    console.error(`pre-push: ✓ ${directLegs.length} direct leg(s)${judged ? " and gradle" : ", no gradle"} — ${elapsed}`);
    return 0;
  }
  record(`fail:${[...red, ...(moved.length > 0 ? ["moved"] : [])].join(",")}`);
  console.log(`PRE-PUSH: FAIL — judged ${judgedWhat}${scopeClause}`);
  if (judged !== undefined && gradleRed) for (const line of failureLines(root, judged.output)) console.error(line);
  console.error(`pre-push: ✗ ${red.join(", ")}${judged && gradleRed ? redNote(judged) : ""}${stoppedNote} — ${elapsed}`);
  if (moved.length > 0) {
    console.error(`pre-push: the branch also moved while the gate judged: ${moved.join("; ")}`);
    console.error(`pre-push: name the sha and git sends exactly what the gate judged: ${explicitPush(remoteName, pushed)}`);
  }
  return 1;
}

const PACKAGED_SKIPPED = "nothing the push changes can change what the code-mode worker loads";

/** Whether the push runs the packaged code-mode test, and why it does. What the judgement cannot read keeps the test, a
 *  judgement that throws included: the push runs one more task rather than fail on a question of load. */
function packagedVerdict(
  repoRoot: string,
  root: string,
  refs: readonly PushedRef[],
  modules: readonly GradleModule[],
  graph: ReturnType<typeof readModuleGraph>,
  changed: readonly string[],
): { readonly runs: true; readonly why: string } | { readonly runs: false } {
  try {
    const why = packagedTestReason({ root, modules, graph, changed, before: (path) => textBefore(repoRoot, refs, path) });
    return why === undefined ? { runs: false } : { runs: true, why };
  } catch (error) {
    return { runs: true, why: `what the worker loads cannot be read: ${errorText(error)}` };
  }
}

/** The batched ktlint over [files], printed like a direct leg. True when it found something or could not judge; [onRed] hears that
 *  at once, while gradle still runs. */
async function ktlintLeg(lint: NonNullable<HookDeps["ktlint"]>, root: string, files: readonly string[], onRed: () => void): Promise<boolean> {
  const started = performance.now();
  const verdict = await lint(root, files);
  if ("error" in verdict) {
    onRed();
    console.error(`  ✗ ktlint could not judge: ${verdict.error}`);
    return true;
  }
  if (verdict.findings.length > 0) {
    onRed();
    console.error(`  ✗ ktlint (${verdict.findings.length} finding(s) in the ${files.length} changed Kotlin file(s))`);
    for (const finding of verdict.findings) console.error(`    ${finding.line}`);
    return true;
  }
  console.error(`  ✓ ktlint (${files.length} changed Kotlin file(s)) — ${seconds(started)}`);
  return false;
}

/** One ref a push moves: the LOCAL ref git read the tip from (a ref name, `HEAD`, or a raw sha when the refspec named
 *  one), the tip it points at, and the remote sha it moves from (zero for a new branch). */
export interface PushedRef {
  readonly local: string;
  readonly tip: string;
  /** The ref on the remote this one moves, which an explicit-sha refspec has to name. */
  readonly remoteRef: string;
  readonly remote: string;
}

/** The refs a push moves, from git's stdin. A deletion has no tip and is not here. */
function pushedRefs(stdin: string): PushedRef[] {
  return stdin
    .split("\n")
    .map((line) => line.trim().split(/\s+/))
    .filter((fields) => fields.length === 4)
    .map((fields) => ({ local: fields[0] ?? "", tip: fields[1] ?? "", remoteRef: fields[2] ?? "", remote: fields[3] ?? "" }))
    .filter((ref) => !ZERO_SHA.test(ref.tip));
}

/** The push that cannot carry an unjudged commit: the sha spelled out, so git has no ref left to re-read. One command
 *  per ref the push moves, each with the sha that ref was judged at. */
function explicitPush(remoteName: string, refs: readonly PushedRef[]): string {
  return refs.map((ref) => `git push ${printableRemote(remoteName)} ${ref.tip}:${ref.remoteRef || ref.local}`).join(" && ");
}

/** The destination as it may be PRINTED: scheme, host and path. With no remote named, git hands the hook the remote's URL,
 *  and a URL can carry a token in its userinfo; a refusal that quoted it would copy the secret into a terminal, a log and
 *  a chat. Userinfo and query never leave this function. A remote's NAME and a local path pass through as they are. */
export function printableRemote(remote: string): string {
  try {
    const url = new URL(remote);
    if (url.host !== "") return `${url.protocol}//${url.host}${url.pathname}`;
  } catch {
    /* not an absolute URL: a name, a path, or scp-like user@host:path */
  }
  const scp = /^(?:[^@/\s]+@)?([^:/\s]+):(?!\/\/)(.*)$/.exec(remote);
  return scp ? `${scp[1]}:${scp[2]}` : remote;
}

/** The commit a ref or sha resolves to now, peeled through an annotated tag, or undefined when git cannot say. */
function refTipNow(root: string, ref: string): string | undefined {
  const read = git(root, ["rev-parse", "--verify", "--quiet", `${ref}^{commit}`]);
  const text = read.stdout.toString("utf8").trim();
  return read.status === 0 && text.length > 0 ? text : undefined;
}

/** THE MOVED-TIP CHECK. A judgement takes minutes, and the seats share one checkout: a peer committing meanwhile moves
 *  the very ref this push is about. Measured on 2026-10-10 (three times: 05:53, 07:33 and 10:01 CT): the hook judged
 *  and printed one sha, a peer committed above it in the checkout during the gradle run, and origin took the peer's
 *  commit. The judged sha never existed on the remote, which gave it no push event and no workflow run, and GitHub
 *  reports the pair as ONE push (before from the first, head from the last), which is what hid it.
 *
 *  WHY. Measured: a plain `git push origin <branch>` to a local path and to a file:// URL sends the sha the hook was
 *  handed, so those transports are not the cause. Captured with GIT_TRACE on a real HTTPS push: the hook started at
 *  10:37:44 and `git send-pack --stateless-rpc --helper-status --thin --no-progress <url> --stdin` was spawned by
 *  git-remote-https at 10:45:39, eight minutes later, with the refs fed to it on stdin. So the send starts strictly
 *  after the hook and takes its refspecs as text; a refspec naming a branch is resolved then, and one naming a sha has
 *  nothing left to resolve. (The trace shows when send-pack runs, not the content of its stdin.)
 *
 *  So this guard re-reads EVERY local ref the push names, each against the tip it was judged at, once when a tip's verdict
 *  is ready and again right before the hook returns, and refuses when any moved. Both sides are peeled to their commit,
 *  so a push of an annotated tag by its own object sha compares commit with commit.
 *
 *  WHAT THIS CANNOT CLOSE. A gap of microseconds remains after the last check: git reads the ref again when the push
 *  ends, and a commit landing in that window is sent unjudged. Only the explicit-sha rule closes it, because a sha has
 *  no ref left to re-read: `git push origin $(git rev-parse HEAD):refs/heads/<branch>`, with the sha taken as the push
 *  starts. The refusal prints that command. A sha refspec resolves to itself and passes. */
function movedRefs(root: string, refs: readonly PushedRef[]): string[] {
  const moved: string[] = [];
  for (const ref of refs) {
    const judged = refTipNow(root, ref.tip);
    const now = refTipNow(root, ref.local);
    if (judged === undefined || now === undefined) {
      moved.push(`${ref.local} cannot be read, so the tip this push would send cannot be confirmed`);
    } else if (now !== judged) {
      moved.push(`${ref.local} is now ${now.slice(0, 7)}, not the judged ${judged.slice(0, 7)}`);
    }
  }
  return moved;
}

/** The paths the pushed refs change, each ref against the sha it moves from. A new branch has no such sha, so it is
 *  compared with its merge base with origin/main. Throws when a base or a diff cannot be read: pre-push refuses rather
 *  than judge a scope it cannot name. Renames list both paths, so a moved file's old module is judged too. */
export function pushedPaths(root: string, refs: readonly { tip: string; remote: string }[]): string[] {
  const paths = new Set<string>();
  for (const ref of refs) {
    const base = pushBase(root, ref);
    const diff = git(root, ["diff", "--name-only", "--no-renames", "-z", base, ref.tip]);
    if (diff.status !== 0) throw new Error(`git diff ${base} ${ref.tip} failed: ${diff.stderr.trim()}`);
    for (const path of diff.stdout.toString("utf8").split("\0")) if (path !== "") paths.add(path);
  }
  return [...paths].sort();
}

/** The commit a pushed ref is judged against: the sha it moves from, or a new branch's merge base with origin/main. Throws
 *  when git cannot say. */
function pushBase(root: string, ref: { readonly tip: string; readonly remote: string }): string {
  return ZERO_SHA.test(ref.remote) ? gitText(root, ["merge-base", "origin/main", ref.tip]) : ref.remote;
}

/** A path's text in the commit a pushed ref moves from, for a path the push deleted; undefined when no ref's base holds it. */
function textBefore(root: string, refs: readonly PushedRef[], path: string): string | undefined {
  for (const ref of refs) {
    const shown = git(root, ["show", `${pushBase(root, ref)}:${path}`]);
    if (shown.status === 0) return shown.stdout.toString("utf8");
  }
  return undefined;
}

/** The module law, read from the file the build enforces. */
export function readModuleGraph(root: string): ReturnType<typeof parseModuleGraph> {
  return parseModuleGraph(readFileSync(join(root, "gradle", "module-law.txt"), "utf8"));
}

/** The paths a branch changes against its merge base with [base]. Throws when git cannot say. */
export function pathsSince(root: string, base: string): string[] {
  const mergeBase = gitText(root, ["merge-base", base, "HEAD"]);
  const diff = git(root, ["diff", "--name-only", "--no-renames", "-z", mergeBase, "HEAD"]);
  if (diff.status !== 0) throw new Error(`git diff ${mergeBase} HEAD failed: ${diff.stderr.trim()}`);
  return diff.stdout.toString("utf8").split("\0").filter((path) => path !== "").sort();
}

/** The ladder's rows, read from the checkout. A missing or malformed ladder throws: pre-push refuses rather than judge a
 *  scope it cannot name. */
export function readLadder(root: string): Leg[] {
  const parsed = JSON.parse(readFileSync(join(root, LADDER), "utf8")) as { legs?: unknown };
  if (!Array.isArray(parsed.legs)) throw new Error(`${LADDER} has no legs array`);
  const legs = parsed.legs as Leg[];
  const malformed = legs.filter((leg) => typeof leg?.task !== "string" || !Array.isArray(leg.command));
  if (malformed.length > 0) throw new Error(`${LADDER} has ${malformed.length} row(s) without a task and a command`);
  return legs;
}

/** A `bun test` leg's per-test timeout. Bun's 5 s default fails a test that spawns git or the gate a few times whenever the
 *  pre-push runs it beside gradle on a loaded machine (promote.test.ts and hook.test.ts both timed out so, Oct 12), so every
 *  bun test leg, narrowed or whole, runs with this one bound and no test needs its own. */
const BUN_TEST_TIMEOUT_MS = "30000";

/** The leg's argv as spawned: a `bun test` leg gets [BUN_TEST_TIMEOUT_MS]. */
function legArgv(command: readonly string[]): string[] {
  const [bun, test, ...rest] = command;
  return bun === "bun" && test === "test" ? [bun, test, "--timeout", BUN_TEST_TIMEOUT_MS, ...rest] : [...command];
}

/** Runs each direct leg from the repository root, all at once. A leg that exits nonzero, or cannot start, fails; its
 *  output tail is shown only then. [onRed] hears each failing leg the moment it ends, while the others still run. Returns
 *  the failed legs. */
export async function runDirectLegs(root: string, legs: readonly Leg[], onRed?: (leg: Leg) => void): Promise<Leg[]> {
  const runs = await Promise.all(
    legs.map(async (leg) => {
      const started = performance.now();
      let status: number;
      let output: string;
      try {
        const proc = Bun.spawn(legArgv(leg.command), { cwd: root, env: spawnEnv({}), stdout: "pipe", stderr: "pipe" });
        const [stdout, stderr, code] = await Promise.all([
          new Response(proc.stdout).text(),
          new Response(proc.stderr).text(),
          proc.exited,
        ]);
        status = code;
        output = stdout + stderr;
      } catch (error) {
        status = 127;
        output = errorText(error);
      }
      if (status !== 0) onRed?.(leg);
      return { leg, status, output, took: seconds(started) };
    }),
  );
  const failed: Leg[] = [];
  for (const run of runs) {
    if (run.status === 0) {
      console.error(`  ✓ ${run.leg.task} — ${run.took}`);
      continue;
    }
    failed.push(run.leg);
    console.error(`  ✗ ${run.leg.task} (exit ${run.status})`);
    console.error(tailOf(run.output));
  }
  return failed;
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function shellQuote(text: string): string {
  return `'${text.replace(/'/g, `'\\''`)}'`;
}

/** One hook shim: an exec of this CLI, so the verdict and the exit code are the CLI's own. */
export function shimText(bun: string, gate: string, verb: string): string {
  return ["#!/bin/sh", SHIM_BEGIN, `exec ${shellQuote(bun)} ${shellQuote(gate)} hook ${verb} "$@"`, SHIM_END, ""].join("\n");
}

/** Writes both shims into [hooksDir]. Idempotent. Refuses to replace a hook that is not a splice shim, and leaves every
 *  other hook in the directory (fence's post-commit) untouched. */
export function installShims(hooksDir: string, bun: string, gate: string): string[] {
  for (const verb of HOOK_VERBS) {
    const path = join(hooksDir, verb);
    if (existsSync(path) && !readFileSync(path, "utf8").includes(SHIM_BEGIN)) {
      throw new Error(`${path} exists and is not a splice gate shim; refusing to replace it`);
    }
  }
  mkdirSync(hooksDir, { recursive: true });
  const written: string[] = [];
  for (const verb of HOOK_VERBS) {
    const path = join(hooksDir, verb);
    writeFileSync(path, shimText(bun, gate, verb));
    chmodSync(path, 0o755);
    written.push(path);
  }
  return written;
}

export async function hook(argv: readonly string[]): Promise<number> {
  const [verb, ...rest] = argv;
  const lay = layout();
  try {
    // git passes the hook its own arguments (pre-push gets the remote's name and URL); neither is read.
    if (verb === "pre-commit") return await commitGate(lay);
    if (verb === "pre-push") return await prePush(lay, await Bun.stdin.text(), { timings: appendPushTiming, ktlint: ktlintOver }, rest[0] ?? "origin");
    if (verb === "install" && rest.length === 0) {
      const hooksDir = resolve(lay.repoRoot, gitText(lay.repoRoot, ["rev-parse", "--git-path", "hooks"]));
      for (const path of installShims(hooksDir, process.execPath, join(lay.repoRoot, "tools", "gate", "index.ts"))) {
        console.log(`installed ${path}`);
      }
      return 0;
    }
  } catch (exc) {
    console.error(`gate hook ${verb}: ✗ could not judge — ${exc instanceof Error ? exc.message : String(exc)}`);
    return 1;
  }
  console.error(`gate hook: expected pre-commit, pre-push or install — got ${argv.join(" ") || "nothing"}`);
  return 2;
}
