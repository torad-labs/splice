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
// ONE SET OF BYTES. The walls read the index and gradle reads the worktree. `git commit -- <paths>` writes the
// worktree's bytes, so the two agree; a path whose index blob is not what `git add` would store from the
// worktree is refused, because the gate would judge bytes the commit does not hold.
//
// THE THREAT MODEL OF THE CONTRACT CHECKS. The hook reads each touched path's worktree entry before gradle runs and again
// after it returns (driftedSince). That catches a seat editing a touched path while the gate runs, the realistic case on a
// shared checkout. It is not a security boundary: a change that lands between the two reads of one path is not seen, since
// the hook cannot observe a file between two syscalls. The guarantee is the contract as of the two reads. Later reviews
// judge this file against that line, not against a stronger one.
//
// EVERY KOTLIN FILE HAS A CHECK. A Kotlin path maps to the gradle tasks that compile it: its module (compile,
// test compile, detekt), build-logic (its compile), or a root script (the configuration pass, `help`, which
// compiles every script of the build). A Kotlin path that maps to no check refuses the commit.
//
// PRE-COMMIT judges the COMMIT. Its paths come from the index git is writing (`git diff --cached` honours
// GIT_INDEX_FILE, which git sets for `git commit -- <paths>`). Gradle runs only the checks those paths map to, so a
// commit's cost follows its own modules. A break a commit makes in a clean caller in another module is not seen
// here: the pre-push tier compiles every module.
//
// PRE-PUSH judges the WORKTREE, and the verdict line says so. The worktree must be the pushed tip's HEAD, or the
// push is refused. It lints the tip's subject, then scopes the push to its own diff (prepush-scope.ts): the ladder
// rows whose inputs the diff touches run, and gradle runs only what the diff needs, through the slot. The full
// ladder and every module's tests are CI's, on the pushed sha.
//
// NOTHING HERE IS SKIPPABLE. There is no environment switch and no flag. `--no-verify` is the only bypass, and
// it is forbidden to seats.
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readlinkSync, rmSync, type Stats, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative, resolve } from "node:path";
import { astGrepBin } from "../lib/astgrep.ts";
import { resolveJdk21 } from "../lib/jdk.ts";
import { type Layout, layout } from "../lib/repo.ts";
import { acquireRunSentinel, describeOpenRun } from "../lib/sentinel.ts";
import { commitLegs, LAW_SUITES_TASK, type Leg, legsWithoutInputs, prePushScope } from "../lib/prepush-scope.ts";
import { cancelledBySignal, RUN_ALREADY_OPEN_EXIT } from "./run.ts";
import { title } from "./title.ts";

export const usage =
  "hook <pre-commit|pre-push|install>  the git hooks: pre-commit runs the census and judges the commit's Kotlin, pre-push the worktree; install writes the shims";

export const SHIM_BEGIN = "# >>> splice gate hook >>> written by `bun tools/gate hook install`; reinstall replaces it";
export const SHIM_END = "# <<< splice gate hook <<<";
export const HOOK_VERBS = ["pre-commit", "pre-push"] as const;
const ZERO_SHA = /^0+$/;
const LADDER = "tools/gate/config/ladder.json";
const KOTLIN = /\.kts?$/;
/** The plugin whose presence means the checkout registers `lawSuites`; a checkout without it has no law task to request. */
const LAW_SUITE_PLUGIN = "build-logic/src/main/kotlin/splice.law-suite.gradle.kts";
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
export type GateRunner = (tasks: readonly string[]) => Promise<GateRun>;

export interface HookDeps {
  readonly gate?: GateRunner;
  readonly openRun?: (head: string) => ReturnType<typeof acquireRunSentinel>;
  /** The ladder rows pre-push scopes by. Read from the checkout when absent. */
  readonly legs?: readonly Leg[];
  /** Whether another gradle process is live: the evidence a collision needs. The default reads the process list. */
  readonly rivalLive?: () => boolean;
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

const FILE_MODE = "100644";
const EXECUTABLE_MODE = "100755";
const SYMLINK_MODE = "120000";
/** What a worktree path holds when it is a directory or another kind: never an entry a commit can hold. */
const OTHER_KIND = "other";

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

/** The blob `git add` would store for [bytes]: hashed as given, with no filter and no text decoding. */
function hashBytes(root: string, bytes: Buffer): string {
  const hashed = spawnSync("git", ["hash-object", "--no-filters", "--stdin"], { cwd: root, input: bytes });
  if (hashed.status !== 0) throw new Error(`git hash-object failed: ${hashed.stderr.toString().trim()}`);
  return hashed.stdout.toString("utf8").trim();
}

/** The stat of a worktree path, or undefined when it is absent. A parent that is now a regular file (ENOTDIR) means the
 *  path is absent, which is what a commit that deletes it requires; any other error is thrown, and the caller refuses. */
function lstatAt(abs: string): Stats | undefined {
  try {
    return lstatSync(abs, { throwIfNoEntry: false });
  } catch (error) {
    if ((error as { code?: string }).code === "ENOTDIR") return undefined;
    throw error;
  }
}

/** Each path's worktree signature: inode, size, mode and change times, or "absent". Two signatures that differ mean the
 *  path changed in between, even when it changed back, so the bytes the gate read are not known to be the commit's. */
function worktreeSignature(root: string, paths: readonly string[]): Map<string, string> {
  return new Map(
    paths.map((path) => {
      const st = lstatAt(join(root, path));
      return [path, st === undefined ? "absent" : `${st.ino}:${st.size}:${st.mode}:${st.mtimeMs}:${st.ctimeMs}`];
    }),
  );
}

/** What each worktree path holds as an index entry, the way `git add` would store it. A symlink is its raw link bytes,
 *  never the file it names. A file is its raw bytes, and only the owner execute bit makes it 100755, as git does. A
 *  missing path has no entry; a directory or another kind is OTHER_KIND. */
function worktreeEntries(root: string, paths: readonly string[]): Map<string, string> {
  const entries = new Map<string, string>();
  const files = new Map<string, string>();
  for (const path of paths) {
    const abs = join(root, path);
    const stat = lstatAt(abs);
    if (stat === undefined) continue;
    if (stat.isSymbolicLink()) {
      entries.set(path, `${SYMLINK_MODE} ${hashBytes(root, readlinkSync(abs, { encoding: "buffer" }))}`);
    } else if (stat.isFile()) {
      files.set(path, (stat.mode & 0o100) !== 0 ? EXECUTABLE_MODE : FILE_MODE);
    } else {
      entries.set(path, OTHER_KIND);
    }
  }
  if (files.size > 0) {
    const names = [...files.keys()];
    const hashed = spawnSync("git", ["hash-object", "--no-filters", "--stdin-paths"], { cwd: root, encoding: "utf8", input: `${names.join("\n")}\n` });
    if (hashed.status !== 0) throw new Error(`git hash-object failed: ${hashed.stderr.trim()}`);
    const hashes = hashed.stdout.trim().split("\n");
    names.forEach((path, i) => entries.set(path, `${files.get(path)} ${hashes[i] ?? ""}`));
  }
  return entries;
}

/** A path a commit changes that the worktree does not match, with the reason, named for the path. */
export interface Breach {
  readonly path: string;
  readonly reason: string;
}

/** What changed while the gate judged the commit's paths: the contract again, and each path whose signature moved. */
function driftedSince(root: string, changed: readonly string[], judgedAt: ReadonlyMap<string, string>): Breach[] {
  const refused = breaches(root, changed);
  const named = new Set(refused.map((breach) => breach.path));
  const now = worktreeSignature(root, changed);
  const moved = changed
    .filter((path) => !named.has(path) && now.get(path) !== judgedAt.get(path))
    .map((path) => ({ path, reason: `${path}: changed while the gate judged it; the bytes it judged are not known to be the commit's` }));
  return [...refused, ...moved];
}

/** THE CONTRACT. For every path a commit changes, the worktree holds the entry the commit holds, in existence, type,
 *  git-normalized mode and raw bytes; a path the commit deletes is absent from the worktree. Every other case is refused,
 *  named: a mode that is neither a file nor a symlink, a Kotlin symlink (a link holds no bytes of its own to judge), a
 *  directory where a file is staged, a type change, and two sets of bytes. The gate judges what the commit holds, so it
 *  never reads a file the commit does not hold. */
export function breaches(root: string, changed: readonly string[]): Breach[] {
  if (changed.length === 0) return [];
  const indexed = indexEntries(root);
  const worktree = worktreeEntries(root, changed);
  const out: Breach[] = [];
  for (const path of changed) {
    const onDisk = worktree.get(path);
    const staged = indexed.get(path);
    if (staged === undefined) {
      if (onDisk !== undefined) out.push({ path, reason: `commit deletes ${path} but the worktree still holds it` });
      continue;
    }
    const mode = staged.split(" ")[0] ?? "";
    if (mode !== FILE_MODE && mode !== EXECUTABLE_MODE && mode !== SYMLINK_MODE) {
      out.push({ path, reason: `${path}: index mode ${mode} is not a file or a symlink, and the gate judges neither` });
    } else if (mode === SYMLINK_MODE && KOTLIN.test(path)) {
      out.push({ path, reason: `${path}: a Kotlin symlink; the gate judges the bytes a commit holds, and a link holds none` });
    } else if (onDisk === undefined) {
      out.push({ path, reason: `${path}: the index holds it but the worktree does not` });
    } else if (onDisk === OTHER_KIND) {
      out.push({ path, reason: `${path}: the worktree holds a directory or another kind, not a file` });
    } else if ((mode === SYMLINK_MODE) !== onDisk.startsWith(`${SYMLINK_MODE} `)) {
      out.push({ path, reason: `${path}: the type changed between a symlink and a file` });
    } else if (onDisk !== staged) {
      out.push({ path, reason: `${path}: the index and the worktree hold different content or mode` });
    }
  }
  return out;
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
  if (module !== undefined) return [`${module}:compileKotlin`, `${module}:compileTestKotlin`, `${module}:detekt`];
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
 *  same way). */
export async function judgedRun(
  run: GateRunner,
  root: string,
  modules: readonly GradleModule[],
  tasks: readonly string[],
  rivalLive: () => boolean = liveGradleElsewhere,
): Promise<Judged> {
  const first = await run(tasks);
  if (first.status === 0 || cancelledBySignal(first.status) || !isCollision(first.output, root, modules, rivalLive)) {
    return { ...first, reran: false, collidedAgain: false };
  }
  const again = rerunTasks(tasks, first.output);
  // Every requested task printed its result, so the collision kept none of them from judging: there is nothing to rerun.
  if (again.length === 0) return { ...first, reran: false, collidedAgain: false };
  console.error(`  ! collision: another gradle run in this checkout held a shared file; rerunning ${again.length} task(s) once`);
  const second = await run(again);
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

/** Gradle tasks through the gradle slot (one gradle per tree). The slot is the gate's own verb, so the lock is the
 *  gate's lock. Output is echoed to stderr as it arrives when [echo] is set. */
export function slotRunner(lay: Layout, label: string, echo: boolean): GateRunner {
  return async (tasks) => {
    const jdk = resolveJdk21();
    if ("error" in jdk) throw new Error(jdk.error);
    const proc = Bun.spawn(
      [process.execPath, join(lay.repoRoot, "tools", "gate", "index.ts"), "slot", label, "--", ...tasks],
      { cwd: lay.repoRoot, env: spawnEnv({ JAVA_HOME: jdk.javaHome }), stdout: "pipe", stderr: "pipe" },
    );
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
    return { status: await proc.exited, output };
  };
}

function seconds(started: number): string {
  return `${((performance.now() - started) / 1000).toFixed(1)} s`;
}

function tailOf(output: string): string {
  return output.split("\n").slice(-LINES_SHOWN_ON_FAILURE).join("\n");
}

// The census leg of pre-commit. Like the walls, it judges the commit's bytes: the script and the rows are read from the
// index, and the tracked set is the index's, since git hands this hook the commit's index. The census checks the whole
// tree, so this leg judges what the commit can change: a finding fails the commit when it names a path the commit
// changes, or a path a changed row names. A commit adds no finding elsewhere except by changing a row. A finding about
// a path the commit leaves alone is printed and left to the census leg of the gate (CLAUDE.md §18).
const CENSUS_SCRIPT = ".dev/restructure/census.ts";
const CENSUS_ROWS = ".dev/restructure/capabilities.tsv";

/** The bytes the index holds for [path]. Throws when the index holds none. */
function indexBytes(root: string, path: string): Buffer {
  const r = git(root, ["show", `:${path}`]);
  if (r.status !== 0) throw new Error(`the commit holds no ${path}: ${r.stderr.trim()}`);
  return r.stdout;
}

/** The paths a finding may be about for this commit: every path it changes, and the paths its changed rows name. */
function namedByCommit(root: string): Set<string> {
  const named = new Set(changedPaths(root));
  const rowChanges = git(root, ["diff", "--cached", "--no-renames", "--no-color", "-U0", "--", CENSUS_ROWS]).stdout.toString("utf8");
  for (const line of rowChanges.split("\n")) {
    const changed = (line.startsWith("+") && !line.startsWith("+++")) || (line.startsWith("-") && !line.startsWith("---"));
    if (!changed) continue;
    const [source = "", , , destination = ""] = line.slice(1).split("\t");
    for (const path of [source, destination]) if (path !== "") named.add(path);
  }
  return named;
}

/** One finding as the census prints it under --json: its sentence for people, and every path it is about. */
interface CensusFinding {
  readonly message: string;
  readonly paths: readonly string[];
}

function isCensusFinding(value: unknown): value is CensusFinding {
  if (typeof value !== "object" || value === null) return false;
  const { message, paths } = value as { message?: unknown; paths?: unknown };
  return typeof message === "string" && Array.isArray(paths) && paths.every((path) => typeof path === "string");
}

/** The census script's verdict, read from its one JSON document: its findings, or the reason it could not judge. Exit 0 with
 *  no findings and exit 1 with the listed findings are verdicts. Any other exit, a document that does not parse or does not
 *  have this shape, or an exit that disagrees with the list, is a failure. The sentences are never parsed. */
function censusVerdict(status: number, stdout: string, stderr: string): { findings: CensusFinding[] } | { failure: string } {
  const failure = (why: string) => ({ failure: `${why}: ${tailOf(`${stdout}${stderr}`).trim() || "no output"}` });
  if (status !== 0 && status !== 1) return failure(`exit ${status}`);
  let document: unknown;
  try {
    document = JSON.parse(stdout);
  } catch {
    return failure(`exit ${status} with no verdict document`);
  }
  const listed = (document as { findings?: unknown } | null)?.findings;
  if (!Array.isArray(listed) || !listed.every(isCensusFinding)) return failure(`exit ${status} with a malformed verdict`);
  if ((status === 0) !== (listed.length === 0)) return failure(`exit ${status} with ${listed.length} finding(s)`);
  return { findings: listed };
}

/** The census leg of pre-commit. Returns the exit code. */
export async function censusLeg(lay: Layout): Promise<number> {
  const started = performance.now();
  const root = lay.repoRoot;
  const scratch = mkdtempSync(join(tmpdir(), "splice-census-"));
  try {
    const script = join(scratch, "census.ts");
    const rows = join(scratch, "capabilities.tsv");
    writeFileSync(script, indexBytes(root, CENSUS_SCRIPT));
    writeFileSync(rows, indexBytes(root, CENSUS_ROWS));
    const run = spawnSync(process.execPath, [script, "--root", root, "--rows", rows, "--json"], { cwd: root, maxBuffer: 1 << 26 });
    const verdict = censusVerdict(run.status ?? 1, run.stdout?.toString("utf8") ?? "", run.stderr?.toString("utf8") ?? "");
    if ("failure" in verdict) {
      console.error(`pre-commit: ✗ census could not judge: ${verdict.failure} — ${seconds(started)}`);
      return 1;
    }
    const named = namedByCommit(root);
    // A finding is this commit's when a path it carries is one the commit names. A finding that carries no path, or an empty
    // one (a row whose source column is blank), names no file the commit could be leaving alone, so no path places it: it
    // counts as this commit's until shown otherwise.
    const hits = verdict.findings.filter((finding) => finding.paths.length === 0 || finding.paths.some((path) => path === "" || named.has(path)));
    const elsewhere = verdict.findings.filter((finding) => !hits.includes(finding));
    for (const finding of hits) console.error(`  ✗ census: ${finding.message}`);
    for (const finding of elsewhere) console.error(`  · census, not this commit's path: ${finding.message}`);
    if (hits.length > 0) {
      console.error(`pre-commit: ✗ census (${hits.length} finding(s) on paths this commit changes) — ${seconds(started)}`);
      return 1;
    }
    console.error(`  ✓ census${elsewhere.length > 0 ? ` (${elsewhere.length} finding(s) on other paths, reported above)` : ""}`);
    return 0;
  } catch (exc) {
    console.error(`pre-commit: ✗ census could not judge: ${exc instanceof Error ? exc.message : String(exc)} — ${seconds(started)}`);
    return 1;
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
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
  const failed = await runDirectLegs(lay.repoRoot, due);
  if (failed.length > 0) {
    console.error(`pre-commit: ✗ ${failed.map((leg) => leg.task).join(", ")} — ${seconds(started)}`);
    return 1;
  }
  return 0;
}

/** The pre-commit verb: the census leg, the ladder legs the commit's paths trigger, then the commit's Kotlin judgement. A
 *  refusal stops it before gradle. */
export async function commitGate(lay: Layout, deps: HookDeps = {}): Promise<number> {
  const census = await censusLeg(lay);
  if (census !== 0) return census;
  const legs = await commitLegsLeg(lay, deps);
  if (legs !== 0) return legs;
  return preCommit(lay, deps);
}

/** The pre-commit judgement of this commit. Returns the exit code. */
export async function preCommit(lay: Layout, deps: HookDeps = {}): Promise<number> {
  const started = performance.now();
  const root = lay.repoRoot;
  const changed = changedPaths(root);
  const refused = breaches(root, changed);
  if (refused.length > 0) {
    for (const breach of refused) console.error(`  ✗ ${breach.reason}`);
    console.error(`pre-commit: ✗ ${refused.length} path(s) the worktree does not match the commit on — ${seconds(started)}`);
    return 1;
  }
  const touched = changed.filter((p) => KOTLIN.test(p));
  if (touched.length === 0) {
    console.error("pre-commit: no Kotlin in this commit; nothing to judge");
    return 0;
  }
  console.error(`══ pre-commit ══  ${touched.length} Kotlin file(s) in this commit`);
  // The bytes the gate judges are the bytes the commit holds at this point; gradle runs after, so the check below re-reads.
  const judgedAt = worktreeSignature(root, changed);

  // The walls scan the bytes a commit holds: a deletion has none to scan. Module selection covers every change.
  const indexed = indexEntries(root);
  const kotlin = touched.filter((p) => indexed.has(p));
  if (kotlin.length > 0) {
    const dir = mirror(root, kotlin);
    let findings: Finding[];
    try {
      findings = scanMirror(root, dir, kotlin);
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
  }

  const modules = gradleModules(root);
  const tasks = new Set<string>();
  const unmapped: string[] = [];
  for (const path of touched) {
    const checks = checksFor(modules, path);
    if (checks === undefined) unmapped.push(path);
    else for (const task of checks) tasks.add(task);
  }
  if (unmapped.length > 0) {
    for (const path of unmapped) console.error(`  ✗ ${path}: no gradle check covers this file`);
    console.error(`pre-commit: ✗ ${unmapped.length} Kotlin file(s) with no check — ${seconds(started)}`);
    return 1;
  }

  // The cross-module laws ride in the same request as the push makes: gradle fingerprints each law task's declared read set, so a
  // commit that touches a file a law reads reruns that law, and one that touches nothing a law reads runs none. The selector is
  // gradle's own up-to-date check over the inputs each module declares (splice.law-suite), shared with prePushScope, never a list.
  if (existsSync(join(root, LAW_SUITE_PLUGIN))) tasks.add(LAW_SUITES_TASK);

  const judged = await judgedRun(deps.gate ?? slotRunner(lay, "pre-commit", false), root, modules, [...tasks], deps.rivalLive);
  if (judged.status === 0) {
    const late = driftedSince(root, changed, judgedAt);
    if (late.length > 0) {
      for (const breach of late) console.error(`  ✗ ${breach.reason}`);
      console.error(`pre-commit: ✗ ${late.length} path(s) changed while the gate judged them — ${seconds(started)}`);
      return 1;
    }
    console.error(`  ✓ ${[...tasks].join(" ")}`);
    console.error(`pre-commit: PASS — ${seconds(started)}${judged.reran ? " (after one collision rerun)" : ""}`);
    return 0;
  }
  console.error(tailOf(judged.output));
  for (const line of failureLines(root, judged.output)) console.error(line);
  console.error(`pre-commit: ✗ gradle red${redNote(judged)} — ${seconds(started)}`);
  return 1;
}

/** The pre-push judgement of the pushed tip against the worktree. [stdin] is git's ref list:
 *  `<local ref> <local sha> <remote ref> <remote sha>`. */
export async function prePush(lay: Layout, stdin: string, deps: HookDeps = {}): Promise<number> {
  const started = performance.now();
  const tips = stdin
    .split("\n")
    .map((line) => line.trim().split(/\s+/))
    .filter((fields) => fields.length === 4)
    .map((fields) => fields[1] ?? "")
    .filter((sha) => !ZERO_SHA.test(sha));
  if (tips.length === 0) {
    console.error("pre-push: only deletions; nothing to judge");
    return 0;
  }
  const head = gitText(lay.repoRoot, ["rev-parse", "HEAD"]);
  const stray = tips.find((tip) => tip !== head);
  if (stray !== undefined) {
    console.error(`pre-push: ✗ pushed tip ${stray} is not the worktree's HEAD ${head}. The gate judges the worktree, so push from HEAD.`);
    return 1;
  }

  const subject = gitText(lay.repoRoot, ["log", "-1", "--format=%s", head]);
  console.error(`── pr title (${subject}) ──`);
  if ((await title([subject])) !== 0) {
    console.error("pre-push: ✗ pr title");
    return 1;
  }

  let changed: string[];
  let legs: readonly Leg[];
  try {
    changed = pushedPaths(lay.repoRoot, pushedRefs(stdin));
    legs = deps.legs ?? readLadder(lay.repoRoot);
  } catch (error) {
    console.error(`pre-push: ✗ cannot scope the push: ${errorText(error)}`);
    return 1;
  }
  const missing = legsWithoutInputs(legs);
  if (missing.length > 0) {
    console.error(`pre-push: ✗ ladder rows declare no inputs, so the push cannot be scoped: ${missing.join(", ")}`);
    return 1;
  }
  const modules = gradleModules(lay.repoRoot);
  const scope = prePushScope({
    legs,
    modules: modules.map((module) => module.path),
    moduleOf: (file) => moduleOf(modules, file),
    changed,
  });
  const scopeClause = `; scope: ${scope.summary}`;
  console.error(`── scope: ${scope.summary} ──`);

  const dirty = dirtyPaths(lay.repoRoot);
  const sha = head.slice(0, 7);
  const judgedWhat =
    dirty.length === 0
      ? `the worktree, which matches the pushed sha ${sha}`
      : `the worktree (${dirty.length} uncommitted path(s)), not the pushed sha ${sha}`;
  if (dirty.length > 0) {
    console.error(`  ! the worktree has ${dirty.length} uncommitted path(s); the gate tier judges them along with the tip:`);
    for (const path of dirty) console.error(`      ${path}`);
  }

  const open = (deps.openRun ?? acquireRunSentinel)(head);
  if (open !== null) {
    console.error(`pre-push: refusing — a gate of record is already open over this tree (${describeOpenRun(open)})`);
    return RUN_ALREADY_OPEN_EXIT;
  }

  // The direct legs run alongside gradle, so a leg never waits for the slot. The verdict reads both.
  if (scope.direct.length > 0) console.error("── direct legs (no gradle) ──");
  const direct = runDirectLegs(lay.repoRoot, scope.direct);
  let judged: Judged | undefined;
  if (scope.gradle.length > 0) {
    console.error("── gate tier (gradle, scoped to the push) ──");
    judged = await judgedRun(deps.gate ?? slotRunner(lay, "pre-push", true), lay.repoRoot, modules, scope.gradle, deps.rivalLive);
  }
  const failedLegs = await direct;
  const elapsed = seconds(started);
  const gradleRed = judged !== undefined && judged.status !== 0;
  if (!gradleRed && failedLegs.length === 0) {
    const rerun = judged?.reran ? "; passed on the rerun after a collision" : "";
    console.log(`PRE-PUSH: PASS — judged ${judgedWhat}${rerun}${scopeClause}`);
    console.error(`pre-push: ✓ ${scope.direct.length} direct leg(s)${judged ? " and gradle" : ", no gradle"} — ${elapsed}`);
    return 0;
  }
  console.log(`PRE-PUSH: FAIL — judged ${judgedWhat}${scopeClause}`);
  if (judged !== undefined && gradleRed) for (const line of failureLines(lay.repoRoot, judged.output)) console.error(line);
  const red = [...failedLegs.map((leg) => leg.task), ...(gradleRed ? ["gradle"] : [])];
  console.error(`pre-push: ✗ ${red.join(", ")}${judged && gradleRed ? redNote(judged) : ""} — ${elapsed}`);
  return 1;
}

/** The refs a push moves, from git's stdin: the tip each one points at and the remote sha it moves from (zero for a new
 *  branch). A deletion has no tip and is not here. */
function pushedRefs(stdin: string): { tip: string; remote: string }[] {
  return stdin
    .split("\n")
    .map((line) => line.trim().split(/\s+/))
    .filter((fields) => fields.length === 4)
    .map((fields) => ({ tip: fields[1] ?? "", remote: fields[3] ?? "" }))
    .filter((ref) => !ZERO_SHA.test(ref.tip));
}

/** The paths the pushed refs change, each ref against the sha it moves from. A new branch has no such sha, so it is
 *  compared with its merge base with origin/main. Throws when a base or a diff cannot be read: pre-push refuses rather
 *  than judge a scope it cannot name. Renames list both paths, so a moved file's old module is judged too. */
export function pushedPaths(root: string, refs: readonly { tip: string; remote: string }[]): string[] {
  const paths = new Set<string>();
  for (const ref of refs) {
    const base = ZERO_SHA.test(ref.remote) ? gitText(root, ["merge-base", "origin/main", ref.tip]) : ref.remote;
    const diff = git(root, ["diff", "--name-only", "--no-renames", "-z", base, ref.tip]);
    if (diff.status !== 0) throw new Error(`git diff ${base} ${ref.tip} failed: ${diff.stderr.trim()}`);
    for (const path of diff.stdout.toString("utf8").split("\0")) if (path !== "") paths.add(path);
  }
  return [...paths].sort();
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

/** Runs each direct leg from the repository root, all at once. A leg that exits nonzero, or cannot start, fails; its
 *  output tail is shown only then. Returns the failed legs. */
async function runDirectLegs(root: string, legs: readonly Leg[]): Promise<Leg[]> {
  const runs = await Promise.all(
    legs.map(async (leg) => {
      const started = performance.now();
      try {
        const proc = Bun.spawn([...leg.command], { cwd: root, env: spawnEnv({}), stdout: "pipe", stderr: "pipe" });
        const [stdout, stderr, status] = await Promise.all([
          new Response(proc.stdout).text(),
          new Response(proc.stderr).text(),
          proc.exited,
        ]);
        return { leg, status, output: stdout + stderr, started };
      } catch (error) {
        return { leg, status: 127, output: errorText(error), started };
      }
    }),
  );
  const failed: Leg[] = [];
  for (const run of runs) {
    if (run.status === 0) {
      console.error(`  ✓ ${run.leg.task} — ${seconds(run.started)}`);
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
    if (verb === "pre-push") return await prePush(lay, await Bun.stdin.text());
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
