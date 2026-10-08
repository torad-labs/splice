// `gate hook` — the git hooks. `hook pre-commit` and `hook pre-push` are what the two shims in .git/hooks
// exec; `hook install` writes those shims. The logic lives here, versioned and bun-tested, so each shim is
// one line.
//
// WHAT A FAILURE BLOCKS. Gradle builds the worktree, and a shared checkout always holds other seats' edits, so a
// compile or detekt failure decides by the file it names:
//   - a file this commit changes, or a file the worktree holds clean, blocks;
//   - a file another seat has uncommitted edits in is printed and does not block.
//
// PRE-COMMIT judges the COMMIT. Its paths come from the index git is writing: `git diff --cached` honours
// GIT_INDEX_FILE, which git sets for `git commit -- <paths>`. Every wall reads each staged blob from that index.
// Gradle compiles and detekts only the modules that own the commit's files, main and test, so a commit's cost
// follows its own modules. A break a commit makes in a clean caller in another module is not seen here: the
// pre-push tier compiles every module and judges it.
//
// PRE-PUSH judges the WORKTREE, and the verdict line says so. The worktree must be the pushed tip's HEAD, or the
// push is refused. It lints the tip's subject, then runs gradle gateOfRecord (every module compiled, every ladder
// row, with up-to-date checks and no clean), through the slot. A compile failure is judged by the file it names,
// as above; any other red task blocks.
//
// NOTHING HERE IS SKIPPABLE. There is no environment switch and no flag. `--no-verify` is the only bypass,
// and it is forbidden to seats.
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
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
const COMPILE_TASK = /:(compileKotlin|compileTestKotlin)$/;
const FAILED_TASK = /^> Task (\S+) FAILED$/gm;
const LINES_SHOWN_ON_FAILURE = 60;

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

/** A staged blob, exactly as the commit holds it. The worktree is never read for a wall. */
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

/** The walls over the mirrored files, with the repository's sgconfig. Anything but a clean JSON match list fails. */
export function scanMirror(root: string, dir: string, targets: readonly string[]): Finding[] {
  const proc = spawnSync(astGrepBin(root), ["scan", "--config", join(root, "sgconfig.yml"), "--json=compact", ...targets], {
    cwd: dir,
    encoding: "utf8",
    maxBuffer: 1 << 28,
  });
  if (proc.error) throw proc.error;
  if (proc.status !== 0 && proc.status !== 1) throw new Error(`ast-grep exited ${proc.status}: ${proc.stderr.trim()}`);
  const text = proc.stdout.trim();
  if (text === "") return [];
  const parsed: unknown = JSON.parse(text);
  if (!Array.isArray(parsed)) throw new Error("ast-grep printed no match list");
  return parsed as Finding[];
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

/** The discriminator of the header: a named file blocks when this commit changes it or the worktree holds it clean.
 *  A named file another seat has uncommitted edits in is advisory. [commitPaths] is empty for pre-push, whose
 *  judged tip is the worktree's HEAD, so the worktree's clean files are the pushed tree's. */
export function judge(
  named: ReadonlySet<string>,
  commitPaths: ReadonlySet<string>,
  dirty: ReadonlySet<string>,
): { blocking: string[]; advisory: string[] } {
  const blocking: string[] = [];
  const advisory: string[] = [];
  for (const file of [...named].sort()) {
    (commitPaths.has(file) || !dirty.has(file) ? blocking : advisory).push(file);
  }
  return { blocking, advisory };
}

/** A child gate process's environment. A hook's GIT_* variables stay out of it: GIT_INDEX_FILE names the commit's
 *  temporary index, and gradle's own git calls must read the worktree. */
function spawnEnv(extra: Record<string, string>): Record<string, string> {
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(Bun.env)) if (typeof value === "string" && !key.startsWith("GIT_")) env[key] = value;
  return { ...env, ...extra };
}

/** Runs gradle tasks through the gradle slot (one gradle per tree) and returns what they printed. The slot is the
 *  gate's own verb, so the lock is the gate's lock. Output is echoed to stderr as it arrives when [echo] is set. */
async function gradleUnderSlot(
  lay: Layout,
  label: string,
  tasks: readonly string[],
  javaHome: string,
  echo: boolean,
): Promise<{ status: number; output: string }> {
  const proc = Bun.spawn(
    [process.execPath, join(lay.repoRoot, "tools", "gate", "index.ts"), "slot", label, "--", ...tasks],
    { cwd: lay.repoRoot, env: spawnEnv({ JAVA_HOME: javaHome }), stdout: "pipe", stderr: "pipe" },
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
}

function seconds(started: number): string {
  return `${((performance.now() - started) / 1000).toFixed(1)} s`;
}

function tailOf(output: string): string {
  return output.split("\n").slice(-LINES_SHOWN_ON_FAILURE).join("\n");
}

/** The pre-commit judgement of this commit. Returns the exit code. */
export async function preCommit(lay: Layout): Promise<number> {
  const started = performance.now();
  const kotlin = stagedPaths(lay.repoRoot).filter((p) => KOTLIN.test(p));
  if (kotlin.length === 0) {
    console.error("pre-commit: no Kotlin in this commit; nothing to judge");
    return 0;
  }
  console.error(`══ pre-commit ══  ${kotlin.length} Kotlin file(s) in this commit`);

  const dir = mirror(lay.repoRoot, kotlin);
  let findings: Finding[];
  try {
    findings = scanMirror(lay.repoRoot, dir, kotlin);
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

  const modules = gradleModules(lay.repoRoot);
  const owning = [...new Set(kotlin.map((p) => moduleOf(modules, p)).filter((m): m is string => m !== undefined))];
  if (owning.length === 0) {
    console.error(`pre-commit: PASS — ${seconds(started)}`);
    return 0;
  }
  const jdk = resolveJdk21();
  if ("error" in jdk) {
    console.error(jdk.error);
    return 1;
  }
  const tasks = owning.flatMap((m) => [`${m}:compileKotlin`, `${m}:compileTestKotlin`, `${m}:detekt`]);
  const gradle = await gradleUnderSlot(lay, "pre-commit", tasks, jdk.javaHome, false);
  if (gradle.status === 0) {
    console.error(`  ✓ compile + detekt: ${owning.join(" ")}`);
    console.error(`pre-commit: PASS — ${seconds(started)}`);
    return 0;
  }

  const named = namedFiles(gradle.output, lay.repoRoot);
  const { blocking, advisory } = judge(named, new Set(kotlin), new Set(dirtyPaths(lay.repoRoot)));
  if (named.size === 0 || blocking.length > 0) {
    console.error(tailOf(gradle.output));
    const who = named.size === 0 ? "no file it could name" : blocking.join(", ");
    console.error(`pre-commit: ✗ compile + detekt, failing in: ${who} — ${seconds(started)}`);
    return 1;
  }
  console.error(
    `  ! not blocking: ${advisory.length} failing file(s) carry another seat's uncommitted edits: ${advisory.join(", ")}`,
  );
  console.error(`pre-commit: PASS — ${seconds(started)}`);
  return 0;
}

/** The pre-push judgement of the pushed tip against the worktree. [stdin] is git's ref list:
 *  `<local ref> <local sha> <remote ref> <remote sha>`. */
export async function prePush(lay: Layout, stdin: string): Promise<number> {
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

  const jdk = resolveJdk21();
  if ("error" in jdk) {
    console.error(jdk.error);
    return 1;
  }
  const open = acquireRunSentinel(head);
  if (open !== null) {
    console.error(`pre-push: refusing — a gate of record is already open over this tree (${describeOpenRun(open)})`);
    return RUN_ALREADY_OPEN_EXIT;
  }

  console.error("── gate tier (gradle gateOfRecord, up-to-date checks) ──");
  const gate = await gradleUnderSlot(lay, "pre-push", [...PRE_PUSH_GATE_TASKS], jdk.javaHome, true);
  const elapsed = seconds(started);
  if (gate.status === 0) {
    console.log(`PRE-PUSH: PASS — judged ${judgedWhat}`);
    console.error(`pre-push: ✓ gate tier — ${elapsed}`);
    return 0;
  }

  const failed = failedTasks(gate.output);
  const nonCompile = failed.filter((task) => !COMPILE_TASK.test(task));
  if (failed.length === 0 || nonCompile.length > 0) {
    const what = failed.length === 0 ? "no failing task was named" : nonCompile.join(", ");
    console.log(`PRE-PUSH: FAIL — judged ${judgedWhat}`);
    console.error(`pre-push: ✗ gate tier, failing: ${what} — ${elapsed}`);
    return gate.status || 1;
  }
  const named = namedFiles(gate.output, lay.repoRoot);
  const { blocking, advisory } = judge(named, new Set(), new Set(dirty));
  if (named.size === 0 || blocking.length > 0) {
    const who = named.size === 0 ? "no file it could name" : blocking.join(", ");
    console.log(`PRE-PUSH: FAIL — judged ${judgedWhat}`);
    console.error(`pre-push: ✗ compile, failing in: ${who} — ${elapsed}`);
    return gate.status || 1;
  }
  console.log(
    `PRE-PUSH: PASS — judged ${judgedWhat}; compile red only in ${advisory.length} file(s) another seat has in flight, so the tests of those modules did not run`,
  );
  console.error(`pre-push: ✓ gate tier (non-blocking compile red: ${advisory.join(", ")}) — ${elapsed}`);
  return 0;
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
