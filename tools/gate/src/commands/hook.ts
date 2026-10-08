// `gate hook` — the git hooks. `hook pre-commit` and `hook pre-push` are what the two shims in .git/hooks
// exec; `hook install` writes those shims. The logic lives here, versioned and bun-tested, so each shim is
// one line.
//
// A RED CHECK BLOCKS. A gradle run that exits nonzero stops the commit or the push. Nothing is waived by the
// file a failure names or by who holds that file: the output names each failing file and the seat that holds it
// (.git/seat-locks), so the blocked seat knows whom to message. The one exception is a COLLISION, a failure
// another gradle run in this checkout leaves behind (see isCollision). A collision reruns the same tasks once;
// a red after the rerun, or a second collision, fails.
//
// ONE SET OF BYTES. The walls read the index and gradle reads the worktree. `git commit -- <paths>` writes the
// worktree's bytes, so the two agree; a path whose index blob is not what `git add` would store from the
// worktree is refused, because the gate would judge bytes the commit does not hold.
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
// push is refused. It lints the tip's subject, then runs gradle gateOfRecord (every module, every ladder row,
// up-to-date checks, no clean) through the slot.
//
// NOTHING HERE IS SKIPPABLE. There is no environment switch and no flag. `--no-verify` is the only bypass, and
// it is forbidden to seats.
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readlinkSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative, resolve } from "node:path";
import { astGrepBin } from "../lib/astgrep.ts";
import { resolveJdk21 } from "../lib/jdk.ts";
import { type Layout, layout } from "../lib/repo.ts";
import { acquireRunSentinel, describeOpenRun } from "../lib/sentinel.ts";
import { cancelledBySignal, GATE_OF_RECORD_TASKS, RUN_ALREADY_OPEN_EXIT } from "./run.ts";
import { title } from "./title.ts";

export const usage =
  "hook <pre-commit|pre-push|install>  the git hooks: pre-commit judges the commit's Kotlin, pre-push the worktree; install writes the shims";

export const SHIM_BEGIN = "# >>> splice gate hook >>> written by `bun tools/gate hook install`; reinstall replaces it";
export const SHIM_END = "# <<< splice gate hook <<<";
export const HOOK_VERBS = ["pre-commit", "pre-push"] as const;
/** The gate of record's tasks without `clean`: gradle's up-to-date checks decide what runs, and the build cache stays off. */
export const PRE_PUSH_GATE_TASKS = GATE_OF_RECORD_TASKS.filter((task) => task !== "clean" && task !== "--profile");

const ZERO_SHA = /^0+$/;
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

/** What each worktree path holds as an index entry, the way `git add` would store it. A symlink is its raw link bytes,
 *  never the file it names. A file is its raw bytes, and only the owner execute bit makes it 100755, as git does. A
 *  missing path has no entry; a directory or another kind is OTHER_KIND. */
function worktreeEntries(root: string, paths: readonly string[]): Map<string, string> {
  const entries = new Map<string, string>();
  const files = new Map<string, string>();
  for (const path of paths) {
    const abs = join(root, path);
    const stat = lstatSync(abs, { throwIfNoEntry: false });
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

/** Runs the tasks; a collision reruns them once. A red after the rerun is the answer. A run ended by a signal is
 *  cancelled, not a collision, and is not rerun (run.ts ends a cancelled gate the same way). */
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
  console.error("  ! collision: another gradle run in this checkout held a shared file; rerunning the tasks once");
  const second = await run(tasks);
  const collidedAgain = second.status !== 0 && !cancelledBySignal(second.status) && isCollision(second.output, root, modules, rivalLive);
  return { ...second, reran: true, collidedAgain };
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

  const judged = await judgedRun(deps.gate ?? slotRunner(lay, "pre-commit", false), root, modules, [...tasks], deps.rivalLive);
  if (judged.status === 0) {
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

  console.error("── gate tier (gradle gateOfRecord, up-to-date checks) ──");
  const judged = await judgedRun(
    deps.gate ?? slotRunner(lay, "pre-push", true),
    lay.repoRoot,
    gradleModules(lay.repoRoot),
    [...PRE_PUSH_GATE_TASKS],
    deps.rivalLive,
  );
  const elapsed = seconds(started);
  if (judged.status === 0) {
    console.log(`PRE-PUSH: PASS — judged ${judgedWhat}${judged.reran ? "; passed on the rerun after a collision" : ""}`);
    console.error(`pre-push: ✓ gate tier — ${elapsed}`);
    return 0;
  }
  console.log(`PRE-PUSH: FAIL — judged ${judgedWhat}`);
  for (const line of failureLines(lay.repoRoot, judged.output)) console.error(line);
  console.error(`pre-push: ✗ gate tier${redNote(judged)} — ${elapsed}`);
  return 1;
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
    if (verb === "pre-commit") return await preCommit(lay);
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
