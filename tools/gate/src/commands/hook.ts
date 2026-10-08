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
import { GATE_OF_RECORD_TASKS, RUN_ALREADY_OPEN_EXIT } from "./run.ts";
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
const BUILD_LOGIC_TASKS = ["build-logic:compileKotlin"];
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

/** The paths this commit changes, read from the index git is writing (GIT_INDEX_FILE when git sets one). */
export function stagedPaths(root: string): string[] {
  return gitPaths(root, ["diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"]);
}

/** The worktree's paths that differ from HEAD, tracked or untracked: the uncommitted work of every seat. */
export function dirtyPaths(root: string): string[] {
  const tracked = gitPaths(root, ["diff", "--name-only", "-z", "HEAD"]);
  const untracked = gitPaths(root, ["ls-files", "--others", "--exclude-standard", "-z"]);
  return [...new Set([...tracked, ...untracked])].sort();
}

/** The index entry (`<mode> <blob>`) that `git add` would store for each worktree path now. Regular files are hashed raw
 *  (`--no-filters`): a clean filter would turn them into the filtered blob the index holds. A symlink is its link text,
 *  never the file it names. A path missing from the worktree has no entry. */
function worktreeEntries(root: string, paths: readonly string[]): Map<string, string> {
  const entries = new Map<string, string>();
  const regular = new Map<string, string>();
  for (const path of paths) {
    const abs = join(root, path);
    const stat = lstatSync(abs, { throwIfNoEntry: false });
    if (stat === undefined) continue;
    if (stat.isSymbolicLink()) {
      const link = spawnSync("git", ["hash-object", "--no-filters", "--stdin"], { cwd: root, encoding: "utf8", input: readlinkSync(abs) });
      if (link.status !== 0) throw new Error(`git hash-object failed: ${link.stderr.trim()}`);
      entries.set(path, `120000 ${link.stdout.trim()}`);
    } else {
      regular.set(path, (stat.mode & 0o111) !== 0 ? "100755" : "100644");
    }
  }
  if (regular.size > 0) {
    const names = [...regular.keys()];
    const hashed = spawnSync("git", ["hash-object", "--no-filters", "--stdin-paths"], { cwd: root, encoding: "utf8", input: `${names.join("\n")}\n` });
    if (hashed.status !== 0) throw new Error(`git hash-object failed: ${hashed.stderr.trim()}`);
    const hashes = hashed.stdout.trim().split("\n");
    names.forEach((path, i) => entries.set(path, `${regular.get(path)} ${hashes[i] ?? ""}`));
  }
  return entries;
}

/** The staged paths whose index entry (mode and blob) is not the entry `git add` would store from the worktree file.
 *  A staged path missing from the worktree differs too. */
export function unequalBytes(root: string, staged: readonly string[]): string[] {
  if (staged.length === 0) return [];
  const indexed = new Map<string, string>();
  for (const entry of gitPaths(root, ["ls-files", "-s", "-z"])) {
    const tab = entry.indexOf("\t");
    const [mode = "", blob = ""] = entry.slice(0, tab).split(" ");
    indexed.set(entry.slice(tab + 1), `${mode} ${blob}`);
  }
  const worktree = worktreeEntries(root, staged);
  return staged.filter((path) => indexed.get(path) === undefined || worktree.get(path) !== indexed.get(path));
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
  if (file.startsWith("build-logic/")) return [...BUILD_LOGIC_TASKS];
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

/** Whether a red run is a collision: a shared file another gradle run in this checkout held, not a red of the code.
 *  The signatures are a test task's EOF or missing results file, a lock timeout, and a NoClassDefFoundError for a
 *  class whose .class file is on disk. */
export function isCollision(output: string, root: string, modules: readonly GradleModule[]): boolean {
  if (TEST_RESULT_COLLISION.test(output) || LOCK_TIMEOUT.test(output)) return true;
  for (const m of output.matchAll(MISSING_CLASS)) {
    const classFile = `${(m[1] ?? "").replaceAll(".", "/")}.class`;
    if (modules.some((mod) => CLASS_DIRS.some((dir) => existsSync(join(root, mod.dir, "build", "classes", dir, classFile))))) {
      return true;
    }
  }
  return false;
}

/** Runs the tasks; a collision reruns them once. A red after the rerun is the answer. */
export async function judgedRun(
  run: GateRunner,
  root: string,
  modules: readonly GradleModule[],
  tasks: readonly string[],
): Promise<Judged> {
  const first = await run(tasks);
  if (first.status === 0 || !isCollision(first.output, root, modules)) {
    return { ...first, reran: false, collidedAgain: false };
  }
  console.error("  ! collision: another gradle run in this checkout held a shared file; rerunning the tasks once");
  const second = await run(tasks);
  return { ...second, reran: true, collidedAgain: second.status !== 0 && isCollision(second.output, root, modules) };
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
  const staged = stagedPaths(root);
  const split = unequalBytes(root, staged);
  if (split.length > 0) {
    for (const path of split) {
      console.error(`  ✗ ${path}: the index and the worktree hold different content or mode. Run git add ${path}, then commit again.`);
    }
    console.error(`pre-commit: ✗ ${split.length} path(s) with two sets of bytes — ${seconds(started)}`);
    return 1;
  }
  const kotlin = staged.filter((p) => KOTLIN.test(p));
  if (kotlin.length === 0) {
    console.error("pre-commit: no Kotlin in this commit; nothing to judge");
    return 0;
  }
  console.error(`══ pre-commit ══  ${kotlin.length} Kotlin file(s) in this commit`);

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

  const modules = gradleModules(root);
  const tasks = new Set<string>();
  const unmapped: string[] = [];
  for (const path of kotlin) {
    const checks = checksFor(modules, path);
    if (checks === undefined) unmapped.push(path);
    else for (const task of checks) tasks.add(task);
  }
  if (unmapped.length > 0) {
    for (const path of unmapped) console.error(`  ✗ ${path}: no gradle check covers this file`);
    console.error(`pre-commit: ✗ ${unmapped.length} Kotlin file(s) with no check — ${seconds(started)}`);
    return 1;
  }

  const judged = await judgedRun(deps.gate ?? slotRunner(lay, "pre-commit", false), root, modules, [...tasks]);
  if (judged.status === 0) {
    console.error(`  ✓ ${[...tasks].join(" ")}`);
    console.error(`pre-commit: PASS — ${seconds(started)}${judged.reran ? " (after one collision rerun)" : ""}`);
    return 0;
  }
  console.error(tailOf(judged.output));
  for (const line of failureLines(root, judged.output)) console.error(line);
  const again = judged.collidedAgain ? " (a collision again on the rerun)" : "";
  console.error(`pre-commit: ✗ gradle red${again} — ${seconds(started)}`);
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
  );
  const elapsed = seconds(started);
  if (judged.status === 0) {
    console.log(`PRE-PUSH: PASS — judged ${judgedWhat}${judged.reran ? "; passed on the rerun after a collision" : ""}`);
    console.error(`pre-push: ✓ gate tier — ${elapsed}`);
    return 0;
  }
  console.log(`PRE-PUSH: FAIL — judged ${judgedWhat}`);
  for (const line of failureLines(lay.repoRoot, judged.output)) console.error(line);
  const again = judged.collidedAgain ? " (a collision again on the rerun)" : "";
  console.error(`pre-push: ✗ gate tier${again} — ${elapsed}`);
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
