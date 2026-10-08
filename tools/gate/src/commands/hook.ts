// `gate hook` — the git hooks. `hook pre-commit` and `hook pre-push` are what the two shims in .git/hooks
// exec; `hook install` writes those shims. The logic lives here, versioned and bun-tested, so each shim is
// one line.
//
// PRE-COMMIT judges the COMMIT, not the shared worktree. Its paths come from the index git is writing:
// `git diff --cached` honours GIT_INDEX_FILE, which git sets for `git commit -- <paths>`. Every wall reads
// each staged blob from that index. Compile and detekt have to run through gradle, and gradle builds the
// worktree, so for those legs a failure blocks the commit only when it names a file the commit changes.
// A failure in another file is the shared tree's and is printed, not blocking.
//
// PRE-PUSH judges the TIP. The gate reads the worktree, so a push is refused unless the pushed tip is the
// worktree's HEAD. It then lints the tip's subject and runs gradle gateOfRecord (every ladder row, with
// up-to-date checks and no clean), through the slot. A red leg exits non-zero and names itself.
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
import { runUnderSlot } from "../lib/slot.ts";
import { GATE_OF_RECORD_TASKS, RUN_ALREADY_OPEN_EXIT } from "./run.ts";
import { title } from "./title.ts";

export const usage =
  "hook <pre-commit|pre-push|install>  the git hooks: pre-commit judges the commit's Kotlin, pre-push the tip; install writes the shims";

export const SHIM_BEGIN = "# >>> splice gate hook >>> written by `bun tools/gate hook install`; reinstall replaces it";
export const SHIM_END = "# <<< splice gate hook <<<";
export const HOOK_VERBS = ["pre-commit", "pre-push"] as const;
/** The gate of record's tasks without `clean`: gradle's up-to-date checks decide what runs, and the build cache stays off. */
export const PRE_PUSH_GATE_TASKS = GATE_OF_RECORD_TASKS.filter((task) => task !== "clean" && task !== "--profile");

const ZERO_SHA = /^0+$/;
const KOTLIN = /\.kts?$/;

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

/** The paths this commit changes, read from the index git is writing (GIT_INDEX_FILE when git sets one). */
export function stagedPaths(root: string): string[] {
  const r = git(root, ["diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"]);
  if (r.status !== 0) throw new Error(`git diff --cached failed: ${r.stderr.trim()}`);
  return r.stdout.toString("utf8").split("\0").filter((p) => p.length > 0);
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

/** The repo-relative files a gradle or detekt output names, so a failure can be placed on the commit's files. */
export function namedFiles(output: string, root: string): Set<string> {
  const named = new Set<string>();
  for (const m of output.matchAll(/(?:file:\/\/)?([^\s:]+\.kts?):\d+/g)) {
    const abs = resolve(root, m[1] ?? "");
    if (abs.startsWith(`${root}/`)) named.add(relative(root, abs));
  }
  return named;
}

/** A child gate process's environment. A hook's GIT_* variables stay out of it: GIT_INDEX_FILE names the commit's
 *  temporary index, and gradle's own git calls must read the worktree. Undefined values are dropped by the child. */
function childEnv(extra: Record<string, string>): Record<string, string | undefined> {
  const env: Record<string, string | undefined> = {};
  for (const key of Object.keys(Bun.env)) if (key.startsWith("GIT_")) env[key] = undefined;
  return { ...env, ...extra };
}

/** [childEnv] as the string map spawnSync takes: spawnSync would print an undefined value as "undefined". */
function spawnEnv(extra: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(childEnv(extra)).filter((e): e is [string, string] => e[1] !== undefined));
}

/** Runs gradle tasks through the gradle slot (one gradle per tree) and captures what they print, so a failure can
 *  be placed on the commit's files. `bun tools/gate slot` is the slot's own verb, so the lock is the gate's lock. */
function gradleUnderSlot(lay: Layout, label: string, tasks: readonly string[], javaHome: string): { status: number; output: string } {
  const proc = spawnSync(process.execPath, [join(lay.repoRoot, "tools", "gate", "index.ts"), "slot", label, "--", ...tasks], {
    cwd: lay.repoRoot,
    encoding: "utf8",
    env: spawnEnv({ JAVA_HOME: javaHome }),
    maxBuffer: 1 << 28,
  });
  if (proc.error) throw proc.error;
  return { status: proc.status ?? 1, output: `${proc.stdout}${proc.stderr}` };
}

function seconds(started: number): string {
  return `${((performance.now() - started) / 1000).toFixed(1)} s`;
}

/** The pre-commit judgement of this commit. Returns the exit code. */
export function preCommit(lay: Layout): number {
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
  const gradle = gradleUnderSlot(lay, "pre-commit", tasks, jdk.javaHome);
  if (gradle.status === 0) {
    console.error(`  ✓ compile + detekt (${owning.join(" ")})`);
    console.error(`pre-commit: PASS — ${seconds(started)}`);
    return 0;
  }
  const named = namedFiles(gradle.output, lay.repoRoot);
  const mine = kotlin.filter((p) => named.has(p));
  if (mine.length === 0 && named.size > 0) {
    const others = [...named].sort();
    const shown = others.slice(0, 6).join(", ");
    console.error(`  ! compile + detekt not blocking: ${others.length} failing file(s) this commit does not change: ${shown}${others.length > 6 ? ", …" : ""}`);
    console.error(`pre-commit: PASS — ${seconds(started)}`);
    return 0;
  }
  console.error(gradle.output.split("\n").slice(-60).join("\n"));
  const who = mine.length > 0 ? mine.join(", ") : "no file it could name";
  console.error(`pre-commit: ✗ compile + detekt, failing in: ${who} — ${seconds(started)}`);
  return 1;
}

/** The pre-push judgement of the pushed tips. [stdin] is git's ref list: `<local ref> <local sha> <remote ref> <remote sha>`. */
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

  const dirty = gitText(lay.repoRoot, ["status", "--porcelain", "--untracked-files=no"]).split("\n").filter((l) => l !== "").length;
  if (dirty > 0) {
    console.error(`  ! the worktree has ${dirty} uncommitted tracked change(s); the gate tier judges them along with the tip`);
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
  const code = await runUnderSlot({
    layout: lay,
    label: "pre-push",
    args: [...PRE_PUSH_GATE_TASKS],
    env: childEnv({ JAVA_HOME: jdk.javaHome }),
  });
  console.log(code === 0 ? "PRE-PUSH: PASS" : "PRE-PUSH: FAIL");
  console.error(`pre-push: ${code === 0 ? "✓ gate tier" : `✗ gate tier (exit ${code})`} — ${seconds(started)}`);
  return code;
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
    if (verb === "pre-commit") return preCommit(lay);
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
