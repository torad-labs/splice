// Red/green proof for the git hooks (`bun tools/gate hook pre-commit|pre-push|install`).
//
// Every judgement runs in a scratch repository: a fresh `git init` in tmp, the walls, a settings file that maps one
// module, and a node_modules symlink to the real one, so the pinned ast-grep is the one the gate runs. No test copies
// the shared checkout. Gradle is a fake, `compiler`, that judges the scratch worktree's bytes, except the two root
// script tests, which run the real gradle wrapper on a scratch settings file.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join } from "node:path";
import {
  breaches,
  censusLeg,
  checksFor,
  commitGate,
  type GateRunner,
  failedTasks,
  failureLines,
  gradleModules,
  installShims,
  isCollision,
  judgedRun,
  liveGradleElsewhere,
  moduleOf,
  namedFiles,
  parseScan,
  preCommit,
  prePush,
  rerunTasks,
  SHIM_BEGIN,
  scanMirror,
  shimText,
} from "../src/commands/hook.ts";
import { resolveJdk21 } from "../src/lib/jdk.ts";
import { lockPath } from "../src/lib/slot.ts";
import type { Leg } from "../src/lib/prepush-scope.ts";
import { type Layout, layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();

// The same L3 wall fixtures the write-time hook test uses: VIOLATION holds `message_stop` outside the sole
// emitter (error severity); CLEAN is clean under every wall. The path is in scope and not the emitter.
const TARGET = "core/src/main/kotlin/splice/core/Probe.kt";
const SEAT_FILE = "core/src/main/kotlin/splice/core/Seat.kt";
const VIOLATION = 'class Probe {\n    fun endTurn(out: Writer) {\n        out.write("message_stop")\n    }\n}\n';
const CLEAN = "class Probe {\n    fun endTurn(out: Writer) {\n        out.close()\n    }\n}\n";
const MODULE_SETTINGS = 'rootProject.name = "scratch"\ninclude(":core")\nproject(":core").projectDir = file("core")\n';

// Real gradle output, copied from runs on this checkout. The app test task died reading its own results: once
// gradle's EOF, once a results file a parallel run had removed. Gradle prints no path for EOF without --stacktrace.
const EOF_TRACE = [
  "> Task :app:test FAILED",
  "",
  "FAILURE: Build failed with an exception.",
  "",
  "* What went wrong:",
  "Execution failed for task ':app:test' (registered by plugin 'org.gradle.jvm-test-suite').",
  "> java.io.EOFException",
  "",
].join("\n");
const RESULTS_TRACE = [
  "* What went wrong:",
  "Execution failed for task ':app:test' (registered by plugin 'org.gradle.jvm-test-suite').",
  `> java.nio.file.NoSuchFileException: ${repoRoot}/app/build/test-results/test/binary/in-progress-results-generic.bin`,
  "",
].join("\n");
const LOCK_TRACE = "Timeout waiting to lock file hash cache (~/.gradle/caches/9.7.1). It is currently in use by another Gradle instance.\n";
const COMPILE_RED = "> Task :core:compileKotlin FAILED\ne: file:///work/repo/core/src/A.kt:12:5 Unresolved reference 'x'\n";

const scratch: string[] = [];
const dir = (prefix: string): string => {
  const path = mkdtempSync(join(tmpdir(), prefix));
  scratch.push(path);
  return path;
};

// Fixtures read no ambient git config: not the operator's global hooks, filters or identity, and not the system's.
// Git's own switches point at an empty file and no system config for this run, and are put back after it.
const ambientGit = { GIT_CONFIG_GLOBAL: Bun.env.GIT_CONFIG_GLOBAL, GIT_CONFIG_NOSYSTEM: Bun.env.GIT_CONFIG_NOSYSTEM };
const EMPTY_GIT_CONFIG = join(dir("splice-hook-config-"), "gitconfig");
writeFileSync(EMPTY_GIT_CONFIG, "");
Bun.env.GIT_CONFIG_GLOBAL = EMPTY_GIT_CONFIG;
Bun.env.GIT_CONFIG_NOSYSTEM = "1";

afterAll(() => {
  for (const path of scratch) rmSync(path, { recursive: true, force: true });
  for (const [key, value] of Object.entries(ambientGit)) {
    if (value === undefined) delete Bun.env[key];
    else Bun.env[key] = value;
  }
});

function git(cwd: string, args: readonly string[]): void {
  const proc = Bun.spawnSync(["git", ...args], { cwd, stdout: "pipe", stderr: "pipe" });
  if (proc.exitCode !== 0) throw new Error(`git ${args.join(" ")}: ${proc.stderr.toString()}`);
}

/** A commit with the fixture's own identity and no signing. It runs the hooks the fixture has, and no others. */
function commit(root: string, subject: string): void {
  git(root, ["-c", "user.name=test", "-c", "user.email=test@test", "-c", "commit.gpgsign=false", "commit", "-q", "-m", subject]);
}

function head(root: string): string {
  const proc = Bun.spawnSync(["git", "rev-parse", "HEAD"], { cwd: root, stdout: "pipe" });
  return proc.stdout.toString().trim();
}

/** A scratch repository with the walls, one gradle module (`:core`), and the real ast-grep. Its scaffold is one base
 *  commit, and the symlinks and gradle's output are excluded, so every dirty path a test sees is one the test wrote. */
function wallsRepo(): string {
  const root = dir("splice-hook-git-");
  git(root, ["init", "-q"]);
  writeFileSync(join(root, ".git", "info", "exclude"), "node_modules\ngradlew\ngradle\n.gradle\nbuild\n");
  cpSync(join(repoRoot, "sgconfig.yml"), join(root, "sgconfig.yml"));
  cpSync(join(repoRoot, "quality", "rules"), join(root, "quality", "rules"), { recursive: true });
  symlinkSync(join(repoRoot, "node_modules"), join(root, "node_modules"));
  writeFileSync(join(root, "settings.gradle.kts"), MODULE_SETTINGS);
  git(root, ["add", "--", "sgconfig.yml", "quality", "settings.gradle.kts"]);
  commit(root, "chore(test): scratch scaffold");
  // The scaffold is what origin/main is in this repository, so a new branch's base is the scaffold.
  git(root, ["update-ref", "refs/remotes/origin/main", "HEAD"]);
  return root;
}

/** The real gradle wrapper, reachable from a scratch repository by symlink (its jar and distribution are the repo's). */
function linkWrapper(root: string): void {
  symlinkSync(join(repoRoot, "gradlew"), join(root, "gradlew"));
  symlinkSync(join(repoRoot, "gradle"), join(root, "gradle"));
}

function writeFile(root: string, path: string, content: string): void {
  mkdirSync(dirname(join(root, path)), { recursive: true });
  writeFileSync(join(root, path), content);
}

function lay(root: string): Layout {
  return { repoRoot: root, buildRoot: root };
}

/** The paths the contract refuses among [paths], in the order given. */
function refused(root: string, paths: readonly string[]): string[] {
  return breaches(root, paths).map((breach) => breach.path);
}

/** A fake gradle: judges the bytes in the worktree's `core/`, and names each file that holds the violation. */
function compiler(root: string, calls: string[][] = []): GateRunner {
  return async (tasks) => {
    calls.push([...tasks]);
    const lines: string[] = [];
    if (existsSync(join(root, "core"))) {
      for (const rel of readdirSync(join(root, "core"), { recursive: true }) as string[]) {
        const abs = join(root, "core", rel);
        if (!rel.endsWith(".kt") || !statSync(abs).isFile()) continue;
        if (readFileSync(abs, "utf8").includes("message_stop")) {
          lines.push(`e: file://${abs}:3:9 Unresolved reference 'message_stop'`);
        }
      }
    }
    if (lines.length > 0) return { status: 1, output: `> Task :core:compileKotlin FAILED\n${lines.join("\n")}\n` };
    return { status: 0, output: "BUILD SUCCESSFUL\n" };
  };
}

/** The real gradle wrapper, run through the gradle slot in a scratch repository: the root script's configuration pass is
 *  the thing under test. The slot is runUnderSlot, the function `bun tools/gate slot` calls. The verb finds its layout
 *  from its own location, so a child process runs the function on the scratch layout, and the slot's lock is the
 *  checkout's: this run queues behind every other gradle run here, as a seat's does. */
function gradleHere(root: string): GateRunner {
  return async (tasks) => {
    const jdk = resolveJdk21();
    if ("error" in jdk) throw new Error(jdk.error);
    const runner = join(dir("splice-hook-slot-"), "run-slot.ts");
    writeFileSync(
      runner,
      [
        `import { runUnderSlot } from ${JSON.stringify(join(repoRoot, "tools", "gate", "src", "lib", "slot.ts"))};`,
        `const status = await runUnderSlot({`,
        `  layout: { repoRoot: ${JSON.stringify(root)}, buildRoot: ${JSON.stringify(root)} },`,
        `  label: "root-script-test",`,
        `  args: ${JSON.stringify(tasks)},`,
        `  env: { JAVA_HOME: ${JSON.stringify(jdk.javaHome)}, GRADLE_SLOT_LOCK: ${JSON.stringify(lockPath(layout()))} },`,
        `});`,
        `process.exit(status);`,
      ].join("\n"),
    );
    const proc = Bun.spawn([process.execPath, runner], { cwd: root, stdout: "pipe", stderr: "pipe" });
    const [out, err] = await Promise.all([new Response(proc.stdout).text(), new Response(proc.stderr).text()]);
    return { status: await proc.exited, output: out + err };
  };
}

/** The console lines a call prints, with its result. */
async function captured<T>(fn: () => Promise<T>): Promise<{ result: T; text: string }> {
  const lines: string[] = [];
  const log = console.log;
  const err = console.error;
  console.log = (...args: unknown[]) => void lines.push(args.map(String).join(" "));
  console.error = (...args: unknown[]) => void lines.push(args.map(String).join(" "));
  try {
    return { result: await fn(), text: lines.join("\n") };
  } finally {
    console.log = log;
    console.error = err;
  }
}

const pushOf = (sha: string): string => `refs/heads/feat/x ${sha} refs/heads/feat/x 0000000000000000000000000000000000000000\n`;
/** The fixtures have no ladder and no law suites beyond their one module: a pre-push test that is not about either
 *  injects these empty ones. */
const NO_LEGS: Leg[] = [];
const NO_LAW_READS: Record<string, string[]> = {};

describe("pre-commit judges the bytes the commit holds", () => {
  test("RED: a staged violation blocks the commit, and gradle is never asked", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, VIOLATION);
    git(root, ["add", TARGET]);
    const calls: string[][] = [];
    expect(await preCommit(lay(root), { gate: compiler(root, calls) })).toBe(1);
    expect(calls).toEqual([]);
  });

  test("GREEN: the fixed bytes pass the walls and the module's compile and detekt", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    const calls: string[][] = [];
    expect(await preCommit(lay(root), { gate: compiler(root, calls) })).toBe(0);
    expect(calls).toEqual([[":core:compileKotlin", ":core:compileTestKotlin", ":core:detekt"]]);
  });

  test("a commit with no Kotlin in it passes without running a scan", async () => {
    const root = wallsRepo();
    writeFile(root, "README.md", "docs\n");
    git(root, ["add", "README.md"]);
    expect(await preCommit(lay(root))).toBe(0);
  });

  test("two sets of bytes are refused, naming the path: the index holds the clean form, the worktree the violation", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    writeFile(root, TARGET, VIOLATION);
    const calls: string[][] = [];
    const { result, text } = await captured(() => preCommit(lay(root), { gate: compiler(root, calls) }));
    expect(result).toBe(1);
    expect(calls).toEqual([]);
    expect(text).toContain(`${TARGET}: the index and the worktree hold different content or mode`);
  });

  test("two sets of bytes are refused for the reverse case: the index holds the violation, the worktree the clean form", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, VIOLATION);
    git(root, ["add", TARGET]);
    writeFile(root, TARGET, CLEAN);
    expect(refused(root, [TARGET])).toEqual([TARGET]);
    expect(await preCommit(lay(root), { gate: compiler(root) })).toBe(1);
  });

  test("a clean filter does not hide the difference: the index holds the filtered blob, the worktree its raw bytes", () => {
    const root = wallsRepo();
    writeFile(root, ".gitattributes", "*.kt filter=upper\n");
    git(root, ["config", "filter.upper.clean", "tr a-z A-Z"]);
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", ".gitattributes", TARGET]);
    expect(refused(root, [TARGET])).toEqual([TARGET]);
  });

  test("a staged symlink is judged by its link text, not by the file it names", () => {
    const root = wallsRepo();
    writeFile(root, "docs/README.md", "docs\n");
    symlinkSync("README.md", join(root, "docs", "LINK.md"));
    git(root, ["add", "docs/LINK.md"]);
    expect(refused(root, ["docs/LINK.md"])).toEqual([]);
  });

  test("a file whose executable bit differs from the index's mode is refused: the mode is part of the entry", () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    chmodSync(join(root, TARGET), 0o755);
    expect(refused(root, [TARGET])).toEqual([TARGET]);
  });

  test("RED: a commit that only deletes a Kotlin file runs the module the file left", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): probe");
    git(root, ["rm", "-q", TARGET]);
    const calls: string[][] = [];
    expect(await preCommit(lay(root), { gate: compiler(root, calls) })).toBe(0);
    expect(calls).toEqual([[":core:compileKotlin", ":core:compileTestKotlin", ":core:detekt"]]);
  });

  test("RED: a Kotlin file moved out of its module runs the module it left and the module it reaches", async () => {
    const root = wallsRepo();
    writeFile(root, "settings.gradle.kts", `${MODULE_SETTINGS}include(":app")\nproject(":app").projectDir = file("app")\n`);
    git(root, ["add", "settings.gradle.kts"]);
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): probe");
    mkdirSync(join(root, "app", "src", "main", "kotlin", "splice", "app"), { recursive: true });
    git(root, ["mv", TARGET, "app/src/main/kotlin/splice/app/Probe.kt"]);
    const calls: string[][] = [];
    expect(await preCommit(lay(root), { gate: compiler(root, calls) })).toBe(0);
    expect(calls.length).toBe(1);
    expect(calls[0]).toEqual(expect.arrayContaining([":core:compileKotlin", ":core:detekt", ":app:compileKotlin", ":app:detekt"]));
  });

  test("RED: a Kotlin path whose type changes from a file to a symlink is refused, and gradle is never asked", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    writeFile(root, SEAT_FILE, CLEAN);
    git(root, ["add", TARGET, SEAT_FILE]);
    commit(root, "chore(test): probe");
    rmSync(join(root, TARGET));
    symlinkSync("Seat.kt", join(root, TARGET));
    git(root, ["add", TARGET]);
    const calls: string[][] = [];
    expect(await preCommit(lay(root), { gate: compiler(root, calls) })).toBe(1);
    expect(calls).toEqual([]);
  });

  test("RED: a commit that deletes a path the worktree still holds is refused, and gradle is never asked", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): probe");
    git(root, ["rm", "-q", "--cached", TARGET]);
    const calls: string[][] = [];
    const { result, text } = await captured(() => preCommit(lay(root), { gate: compiler(root, calls) }));
    expect(result).toBe(1);
    expect(calls).toEqual([]);
    expect(text).toContain(`commit deletes ${TARGET} but the worktree still holds it`);
  });

  test("RED: a Kotlin symlink is refused: a link holds no bytes of its own for the gate to judge", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    symlinkSync("Probe.kt", join(root, SEAT_FILE.replace("Seat.kt", "Link.kt")));
    git(root, ["add", TARGET, SEAT_FILE.replace("Seat.kt", "Link.kt")]);
    const calls: string[][] = [];
    expect(await preCommit(lay(root), { gate: compiler(root, calls) })).toBe(1);
    expect(calls).toEqual([]);
  });

  test("a file with a group execute bit and no owner execute bit is a 100644 file: only the owner bit makes 100755", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    chmodSync(join(root, TARGET), 0o654);
    const calls: string[][] = [];
    expect(await preCommit(lay(root), { gate: compiler(root, calls) })).toBe(0);
    expect(calls.length).toBe(1);
  });

  test("a symlink whose target holds a byte that is not UTF-8 is judged by its raw bytes", async () => {
    const root = wallsRepo();
    writeFile(root, "docs/README.md", "docs\n");
    symlinkSync(Buffer.concat([Buffer.from("README"), Buffer.from([0xff]), Buffer.from(".md")]), join(root, "docs", "raw-link"));
    git(root, ["add", "docs/raw-link"]);
    expect(await preCommit(lay(root))).toBe(0);
  });

  test("THE CONTRACT: an entry that is neither a file nor a symlink is refused, not passed", async () => {
    const root = wallsRepo();
    writeFile(root, "sub/inner.txt", "x\n");
    git(root, ["update-index", "--add", "--cacheinfo", "160000,1111111111111111111111111111111111111111,sub"]);
    const { result, text } = await captured(() => preCommit(lay(root)));
    expect(result).toBe(1);
    expect(text).toContain("sub: index mode 160000 is not a file or a symlink");
  });

  test("a Kotlin file no check covers is refused, and gradle is never asked", async () => {
    const root = wallsRepo();
    writeFile(root, "scripts/Helper.kts", "val x = 1\n");
    git(root, ["add", "scripts/Helper.kts"]);
    const calls: string[][] = [];
    expect(await preCommit(lay(root), { gate: compiler(root, calls) })).toBe(1);
    expect(calls).toEqual([]);
  });

  test("a red in another seat's dirty file blocks the commit: nothing is waived by the file's owner, and the owner is named", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    writeFile(root, SEAT_FILE, VIOLATION);
    const lock = join(root, ".git", "seat-locks", SEAT_FILE.replaceAll("/", "%"));
    mkdirSync(lock, { recursive: true });
    writeFileSync(join(lock, "owner"), "splice-builder9\n");
    const { result, text } = await captured(() => preCommit(lay(root), { gate: compiler(root) }));
    expect(result).toBe(1);
    expect(text).toContain(`${SEAT_FILE} — seat lock: splice-builder9`);
  });
});

describe("a root script is checked by gradle's configuration pass", () => {
  test("RED: a broken settings script blocks the commit, judged by the real gradle", async () => {
    const root = wallsRepo();
    linkWrapper(root);
    writeFile(root, "settings.gradle.kts", "val broken = (\n");
    git(root, ["add", "settings.gradle.kts"]);
    const { result, text } = await captured(() => preCommit(lay(root), { gate: gradleHere(root) }));
    expect(result).toBe(1);
    expect(text).toContain("Script compilation error");
  }, 240_000);

  test("GREEN: the fixed settings script passes the same real configuration pass", async () => {
    const root = wallsRepo();
    linkWrapper(root);
    writeFile(root, "settings.gradle.kts", 'rootProject.name = "scratch"\n');
    git(root, ["add", "settings.gradle.kts"]);
    expect(await preCommit(lay(root), { gate: gradleHere(root) })).toBe(0);
  }, 240_000);
});

const CENSUS_HEADER = "source\tsha256\tdisposition\tdestination\treason";
const CENSUS_ROWS = ".dev/restructure/capabilities.tsv";
const CENSUS_SCRIPT = ".dev/restructure/census.ts";
const SEAT = "core/src/main/kotlin/splice/core/Seat.kt";
const OTHER = "core/src/main/kotlin/splice/core/Other.kt";
const NEW = "core/src/main/kotlin/splice/core/New.kt";

/** A census row claiming [path] as created. The census never reads the hash column, so its synthetic value is zeros. */
const claim = (path: string): string => `${path}\t${"0".repeat(64)}\tcreated\t\tsynthetic claim`;

/** A scratch repository holding the real census script, the rows given and the files given, in one base commit. */
function censusRepo(rows: readonly string[], files: readonly string[]): string {
  const root = dir("splice-hook-census-");
  git(root, ["init", "-q"]);
  writeFile(root, CENSUS_SCRIPT, readFileSync(join(repoRoot, CENSUS_SCRIPT), "utf8"));
  writeFile(root, CENSUS_ROWS, `${[CENSUS_HEADER, ...rows].join("\n")}\n`);
  for (const path of files) writeFile(root, path, CLEAN);
  git(root, ["add", "--", ".dev", ...files]);
  commit(root, "chore(test): scratch census base");
  return root;
}

describe("the census leg judges the commit's own bytes and refuses a finding the commit causes", () => {
  test("RED: an unclaimed staged file is refused by name, and the Kotlin judgement never runs", async () => {
    const root = censusRepo([claim(SEAT)], [SEAT]);
    writeFile(root, NEW, CLEAN);
    git(root, ["add", "--", NEW]);
    const calls: string[][] = [];
    const { result, text } = await captured(() => commitGate(lay(root), { gate: compiler(root, calls) }));
    expect(result).toBe(1);
    expect(text).toContain(`unclaimed: ${NEW}`);
    expect(calls).toEqual([]);
  });

  test("GREEN: a staged file with its row passes the census leg", async () => {
    const root = censusRepo([claim(SEAT)], [SEAT]);
    writeFile(root, NEW, CLEAN);
    writeFile(root, CENSUS_ROWS, `${[CENSUS_HEADER, claim(SEAT), claim(NEW)].join("\n")}\n`);
    git(root, ["add", "--", NEW, CENSUS_ROWS]);
    expect(await censusLeg(lay(root))).toBe(0);
  });

  test("RED: a commit that removes a row leaves its file unclaimed, and the commit is refused", async () => {
    const root = censusRepo([claim(SEAT), claim(OTHER)], [SEAT, OTHER]);
    writeFile(root, CENSUS_ROWS, `${[CENSUS_HEADER, claim(SEAT)].join("\n")}\n`);
    git(root, ["add", "--", CENSUS_ROWS]);
    const { result, text } = await captured(() => censusLeg(lay(root)));
    expect(result).toBe(1);
    expect(text).toContain(`unclaimed: ${OTHER}`);
  });

  test("RED: a commit that deletes a claimed file and keeps its row is refused", async () => {
    const root = censusRepo([claim(SEAT)], [SEAT]);
    git(root, ["rm", "-q", "--", SEAT]);
    const { result, text } = await captured(() => censusLeg(lay(root)));
    expect(result).toBe(1);
    expect(text).toContain(`not tracked: ${SEAT}`);
  });

  test("GREEN: a finding about a path the commit leaves alone is reported, not refused", async () => {
    const root = censusRepo([claim(SEAT)], [SEAT, OTHER]);
    writeFile(root, NEW, CLEAN);
    writeFile(root, CENSUS_ROWS, `${[CENSUS_HEADER, claim(SEAT), claim(NEW)].join("\n")}\n`);
    git(root, ["add", "--", NEW, CENSUS_ROWS]);
    const { result, text } = await captured(() => censusLeg(lay(root)));
    expect(result).toBe(0);
    expect(text).toContain(`unclaimed: ${OTHER}`);
  });

  test("RED: the rows are judged as the index holds them, so a claim only in the worktree claims nothing", async () => {
    const root = censusRepo([claim(SEAT)], [SEAT]);
    writeFile(root, NEW, CLEAN);
    git(root, ["add", "--", NEW]);
    writeFile(root, CENSUS_ROWS, `${[CENSUS_HEADER, claim(SEAT), claim(NEW)].join("\n")}\n`);
    const { result, text } = await captured(() => censusLeg(lay(root)));
    expect(result).toBe(1);
    expect(text).toContain(`unclaimed: ${NEW}`);
  });

  test("RED: rows the census cannot parse refuse the commit: a run with no verdict is a failure", async () => {
    const root = censusRepo([claim(SEAT)], [SEAT]);
    writeFile(root, CENSUS_ROWS, `not the header\n${claim(SEAT)}\n`);
    git(root, ["add", "--", CENSUS_ROWS]);
    const { result, text } = await captured(() => censusLeg(lay(root)));
    expect(result).toBe(1);
    expect(text).toContain("census could not judge");
  });

  test("RED: a commit whose index holds no census script is refused: the census cannot judge", async () => {
    const root = dir("splice-hook-census-");
    git(root, ["init", "-q"]);
    writeFile(root, NEW, CLEAN);
    git(root, ["add", "--", NEW]);
    const { result, text } = await captured(() => censusLeg(lay(root)));
    expect(result).toBe(1);
    expect(text).toContain("census could not judge");
  });
});

describe("the contract holds until the gate has judged the bytes it reads", () => {
  test("RED: a worktree file swapped while gradle runs is refused by name, though the gate passed", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    const gate: GateRunner = async () => {
      writeFile(root, TARGET, VIOLATION);
      return { status: 0, output: "BUILD SUCCESSFUL\n" };
    };
    const { result, text } = await captured(() => preCommit(lay(root), { gate }));
    expect(result).toBe(1);
    expect(text).toContain(`${TARGET}: the index and the worktree hold different content or mode`);
  });

  test("RED: a file changed and changed back while gradle runs is refused, since the bytes it read are not known", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    const gate: GateRunner = async () => {
      writeFile(root, TARGET, VIOLATION);
      await Bun.sleep(20);
      writeFile(root, TARGET, CLEAN);
      return { status: 0, output: "BUILD SUCCESSFUL\n" };
    };
    const { result, text } = await captured(() => preCommit(lay(root), { gate }));
    expect(result).toBe(1);
    expect(text).toContain(`${TARGET}: changed while the gate judged it`);
  });

  test("RED: a directory replaced by a file: deleting its old child is absent, not a crash", async () => {
    const root = wallsRepo();
    writeFile(root, "old/item.txt", "item\n");
    git(root, ["add", "old/item.txt"]);
    commit(root, "chore(test): base with a directory");
    git(root, ["rm", "-q", "old/item.txt"]);
    rmSync(join(root, "old"), { recursive: true, force: true });
    writeFile(root, "old", "a file now\n");
    git(root, ["add", "old"]);
    const { result } = await captured(() => preCommit(lay(root), { gate: compiler(root) }));
    expect(result).toBe(0);
  });

  test.skipIf(process.getuid?.() === 0)("an I/O error other than a missing parent is not absence: the contract throws", () => {
    const root = wallsRepo();
    writeFile(root, "locked/item.txt", "item\n");
    writeFile(root, "locked/keep.txt", "keep\n");
    git(root, ["add", "locked"]);
    commit(root, "chore(test): base with a locked directory");
    git(root, ["rm", "-q", "locked/item.txt"]);
    chmodSync(join(root, "locked"), 0o000);
    try {
      expect(() => breaches(root, ["locked/item.txt"])).toThrow();
    } finally {
      chmodSync(join(root, "locked"), 0o755);
    }
  });
});

describe("every Kotlin file maps to a check", () => {
  const modules = [{ path: ":app", dir: "app" }, { path: ":app:core", dir: "app/core" }];

  test("a module file is checked by its own compile, test compile and detekt; the longest directory wins", () => {
    expect(checksFor(modules, "app/core/X.kt")).toEqual([":app:core:compileKotlin", ":app:core:compileTestKotlin", ":app:core:detekt"]);
    expect(moduleOf(modules, "app/Y.kt")).toBe(":app");
  });

  test("build-logic is checked by its compile, and a root script by the configuration pass", () => {
    expect(checksFor(modules, "build-logic/src/main/kotlin/x.gradle.kts")).toEqual(["build-logic:compileKotlin"]);
    expect(checksFor(modules, "build.gradle.kts")).toEqual(["help"]);
  });

  test("RED: a build-logic test file is checked by the test compile, not the main compile; other build-logic paths have no check", () => {
    expect(checksFor(modules, "build-logic/src/test/kotlin/splice/X.kt")).toEqual(["build-logic:compileTestKotlin"]);
    expect(checksFor(modules, "build-logic/src/testFixtures/kotlin/splice/X.kt")).toBeUndefined();
  });

  test("a file no module, build-logic or root script owns has no check", () => {
    expect(checksFor(modules, "scripts/x.kts")).toBeUndefined();
  });
});

describe("the scanner's verdict is the match list it printed", () => {
  test("exit 0 with an empty list is a clean scan", () => {
    expect(parseScan(0, "[]", "")).toEqual([]);
  });

  test("exit 1 with a list is the findings it printed", () => {
    expect(parseScan(1, '[{"ruleId":"w","severity":"error"}]', "")).toEqual([{ ruleId: "w", severity: "error" }]);
  });

  test("exit 1 with no list is a dead scanner, not a clean scan", () => {
    expect(() => parseScan(1, "", "")).toThrow("without a match list");
  });

  test("exit 1 with an empty list is not a verdict ast-grep prints", () => {
    expect(() => parseScan(1, "[]", "")).toThrow("not a verdict");
  });

  test("a real ast-grep with no sgconfig fails the scan rather than passing it", () => {
    const root = dir("splice-hook-noconfig-");
    symlinkSync(join(repoRoot, "node_modules"), join(root, "node_modules"));
    expect(() => scanMirror(root, root, ["core/A.kt"])).toThrow("ast-grep exited");
  });
});

describe("pre-push judges the tip", () => {
  const notHead = "1111111111111111111111111111111111111111";

  test("a pushed tip that is not the worktree's HEAD is refused before any gate runs", async () => {
    expect(await prePush(layout(), pushOf(notHead))).toBe(1);
  });

  test("a push of deletions only has nothing to judge", async () => {
    const stdin = `(delete) 0000000000000000000000000000000000000000 refs/heads/gone ${head(repoRoot)}\n`;
    expect(await prePush(layout(), stdin)).toBe(0);
  });

  test("RED: a violating tip blocks the push, judged by the child gate of the worktree", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, VIOLATION);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): probe tip");
    const { result, text } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate: compiler(root), openRun: () => null, legs: NO_LEGS, lawReads: NO_LAW_READS }));
    expect(result).toBe(1);
    expect(text).toContain("PRE-PUSH: FAIL — judged the worktree, which matches the pushed sha");
  });

  test("GREEN: the fixed tip passes the same child gate", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, VIOLATION);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): probe tip");
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): fixed tip");
    const { result, text } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate: compiler(root), openRun: () => null, legs: NO_LEGS, lawReads: NO_LAW_READS }));
    expect(result).toBe(0);
    expect(text).toContain("PRE-PUSH: PASS — judged the worktree, which matches the pushed sha");
  });

  test("a red in another seat's dirty file blocks the push and says the worktree was judged", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): probe tip");
    writeFile(root, SEAT_FILE, VIOLATION);
    const { result, text } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate: compiler(root), openRun: () => null, legs: NO_LEGS, lawReads: NO_LAW_READS }));
    expect(result).toBe(1);
    expect(text).toContain("PRE-PUSH: FAIL — judged the worktree (1 uncommitted path(s)), not the pushed sha");
    expect(text).toContain(`${SEAT_FILE} — seat lock: no seat lock`);
  });
});

describe("pre-push scopes the gate to the pushed diff", () => {
  const docsCommit = (root: string) => {
    writeFile(root, "docs/NOTES.md", "notes\n");
    git(root, ["add", "docs/NOTES.md"]);
    commit(root, "docs(test): notes");
  };

  test("a one-module push runs that module's compile and check, and the public-source test", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): one module");
    const calls: string[][] = [];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate: compiler(root, calls), openRun: () => null, legs: NO_LEGS, lawReads: NO_LAW_READS }));
    expect(result).toBe(0);
    expect(calls).toEqual([[":core:compileKotlin", ":core:compileTestKotlin", ":core:check", ":app:test", "--tests=*PublicSourceNamesNoHostToolTest"]]);
    expect(text).toContain("; scope: no legs; gradle: compile of 1 module(s), check of :core; PublicSourceNamesNoHostToolTest via :app:test");
  });

  test("a Kotlin push also runs the law suite that reads every module's main sources", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): one module");
    const calls: string[][] = [];
    const lawReads = { ":quality-architecture": ["**/src/main/**/*.kt"] };
    const { result } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate: compiler(root, calls), openRun: () => null, legs: NO_LEGS, lawReads }));
    expect(result).toBe(0);
    expect(calls).toEqual([[":core:compileKotlin", ":core:compileTestKotlin", ":core:check", ":quality-architecture:check", ":app:test", "--tests=*PublicSourceNamesNoHostToolTest"]]);
  });

  test("a docs push runs no compile, only the public-source test, and says so in the verdict", async () => {
    const root = wallsRepo();
    docsCommit(root);
    const calls: string[][] = [];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate: compiler(root, calls), openRun: () => null, legs: NO_LEGS, lawReads: NO_LAW_READS }));
    expect(result).toBe(0);
    expect(calls).toEqual([[":app:test", "--tests=*PublicSourceNamesNoHostToolTest"]]);
    expect(text).toContain("PRE-PUSH: PASS — judged the worktree, which matches the pushed sha");
    expect(text).toContain("; scope: no legs; gradle: no compile, check of no module; PublicSourceNamesNoHostToolTest via :app:test");
  });

  test("a new branch is compared with its merge base with origin/main", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): new branch");
    const calls: string[][] = [];
    expect(await prePush(lay(root), pushOf(head(root)), { gate: compiler(root, calls), openRun: () => null, legs: NO_LEGS, lawReads: NO_LAW_READS })).toBe(0);
    expect(calls[0]).toContain(":core:check");
  });

  test("RED: a new branch with no origin/main to compare against is refused before any gate runs", async () => {
    const root = wallsRepo();
    git(root, ["update-ref", "-d", "refs/remotes/origin/main"]);
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): new branch");
    const calls: string[][] = [];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate: compiler(root, calls), openRun: () => null, legs: NO_LEGS, lawReads: NO_LAW_READS }));
    expect(result).toBe(1);
    expect(calls).toEqual([]);
    expect(text).toContain("pre-push: ✗ cannot scope the push:");
  });

  test("RED: a checkout with no ladder refuses the push rather than judge a guessed scope", async () => {
    const root = wallsRepo();
    docsCommit(root);
    const calls: string[][] = [];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate: compiler(root, calls), openRun: () => null }));
    expect(result).toBe(1);
    expect(calls).toEqual([]);
    expect(text).toContain("pre-push: ✗ cannot scope the push:");
    expect(text).toContain("ladder.json");
  });

  test("RED: a ladder row that declares no inputs refuses the push by name", async () => {
    const root = wallsRepo();
    docsCommit(root);
    const bare: Leg[] = [{ task: "bareRow", command: ["true"] }];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate: compiler(root), openRun: () => null, legs: bare }));
    expect(result).toBe(1);
    expect(text).toContain("ladder rows declare no inputs, so the push cannot be scoped: bareRow");
  });

  test("RED: a direct leg that exits nonzero fails the push, and the verdict names it with its scope", async () => {
    const root = wallsRepo();
    docsCommit(root);
    const red: Leg[] = [{ task: "redLeg", command: ["bun", "-e", "process.exit(3)"], inputs: ["**"] }];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate: compiler(root), openRun: () => null, legs: red }));
    expect(result).toBe(1);
    expect(text).toContain("PRE-PUSH: FAIL — judged the worktree, which matches the pushed sha ");
    expect(text).toContain("; scope: legs redLeg; gradle: no compile, check of no module; PublicSourceNamesNoHostToolTest via :app:test");
    expect(text).toContain("redLeg");
  });

  test("GREEN: a direct leg that passes lets a docs-only push through", async () => {
    const root = wallsRepo();
    docsCommit(root);
    const calls: string[][] = [];
    const green: Leg[] = [{ task: "greenLeg", command: ["bun", "-e", "process.exit(0)"], inputs: ["**"] }];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate: compiler(root, calls), openRun: () => null, legs: green }));
    expect(result).toBe(0);
    expect(calls).toEqual([[":app:test", "--tests=*PublicSourceNamesNoHostToolTest"]]);
    expect(text).toContain("; scope: legs greenLeg; gradle: no compile, check of no module; PublicSourceNamesNoHostToolTest via :app:test");
  });
});

describe("a collision reruns the tasks once, and a second collision fails", () => {
  const modulesOf = (root: string) => gradleModules(root);

  test("a collision, then a clean rerun: pre-push passes and says so", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): probe tip");
    const calls: string[][] = [];
    const gate: GateRunner = async (tasks) => {
      calls.push([...tasks]);
      return calls.length === 1 ? { status: 1, output: EOF_TRACE } : { status: 0, output: "BUILD SUCCESSFUL\n" };
    };
    const { result, text } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate, openRun: () => null, legs: NO_LEGS, lawReads: NO_LAW_READS, rivalLive: () => true }));
    expect(result).toBe(0);
    expect(calls.length).toBe(2);
    expect(text).toContain("passed on the rerun after a collision");
  });

  test("a collision, then a second collision: pre-push fails after exactly two runs", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): probe tip");
    const calls: string[][] = [];
    const gate: GateRunner = async (tasks) => {
      calls.push([...tasks]);
      return { status: 1, output: RESULTS_TRACE };
    };
    const { result, text } = await captured(() => prePush(lay(root), pushOf(head(root)), { gate, openRun: () => null, legs: NO_LEGS, lawReads: NO_LAW_READS, rivalLive: () => true }));
    expect(result).toBe(1);
    expect(calls.length).toBe(2);
    expect(text).toContain("a collision again on the rerun");
  });

  test("a red that is not a collision is not rerun", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): probe tip");
    const calls: string[][] = [];
    const gate: GateRunner = async (tasks) => {
      calls.push([...tasks]);
      return { status: 1, output: COMPILE_RED };
    };
    expect(await prePush(lay(root), pushOf(head(root)), { gate, openRun: () => null, legs: NO_LEGS, lawReads: NO_LAW_READS })).toBe(1);
    expect(calls.length).toBe(1);
  });

  test("RED: a collision reruns the tasks that failed or were never reached, not the ones that passed", async () => {
    const calls: string[][] = [];
    const output = `${LOCK_TRACE}> Task :core:compileKotlin\n> Task :core:test FAILED\n`;
    const gate: GateRunner = async (tasks) => {
      calls.push([...tasks]);
      return calls.length === 1 ? { status: 1, output } : { status: 0, output: "BUILD SUCCESSFUL\n" };
    };
    const judged = await judgedRun(gate, repoRoot, [], [":core:compileKotlin", ":core:test", ":core:check"], () => true);
    expect(calls[1]).toEqual([":core:test", ":core:check"]);
    expect(judged.reran).toBe(true);
    expect(judged.status).toBe(0);
  });

  test("a rerun that can name no task reruns them all", () => {
    expect(rerunTasks([":a", ":b"], "> Task :a\n> Task :b\n")).toEqual([":a", ":b"]);
    expect(rerunTasks(["build-logic:test"], "> Task :build-logic:test FAILED\n")).toEqual(["build-logic:test"]);
  });

  test("RED: a rerun carries each option with the task it follows, and reruns no option alone", () => {
    const requested = [":app:test", "--tests=*PublicSourceNamesNoHostToolTest", ":core:check"];
    expect(rerunTasks(requested, "> Task :app:test\n> Task :core:check FAILED\n")).toEqual([":core:check"]);
    expect(rerunTasks(requested, "> Task :app:test FAILED\n")).toEqual([":app:test", "--tests=*PublicSourceNamesNoHostToolTest", ":core:check"]);
  });

  test("the real EOF trace and the real results-file trace are collisions", () => {
    expect(isCollision(EOF_TRACE, repoRoot, modulesOf(repoRoot), () => true)).toBe(true);
    expect(isCollision(RESULTS_TRACE, repoRoot, modulesOf(repoRoot), () => true)).toBe(true);
  });

  test("a lock timeout is a collision; a compile red is not", () => {
    expect(isCollision(LOCK_TRACE, repoRoot, [])).toBe(true);
    expect(isCollision(COMPILE_RED, repoRoot, [])).toBe(false);
  });

  test("NoClassDefFoundError is a collision only for a class whose .class file is on disk", () => {
    const root = wallsRepo();
    writeFile(root, "core/build/classes/kotlin/test/splice/core/FooTest.class", "");
    const modules = gradleModules(root);
    expect(isCollision("java.lang.NoClassDefFoundError: splice/core/FooTest\n", root, modules, () => true)).toBe(true);
    expect(isCollision("java.lang.NoClassDefFoundError: splice/core/Missing\n", root, modules)).toBe(false);
  });

  test("a collision whose rerun is red for another reason reports that red, not a second collision", async () => {
    const root = wallsRepo();
    const calls: string[][] = [];
    const gate: GateRunner = async (tasks) => {
      calls.push([...tasks]);
      return calls.length === 1 ? { status: 1, output: EOF_TRACE } : { status: 1, output: COMPILE_RED };
    };
    const judged = await judgedRun(gate, root, gradleModules(root), [":app:test"], () => true);
    expect(judged.reran).toBe(true);
    expect(judged.collidedAgain).toBe(false);
    expect(judged.status).toBe(1);
  });
  test("RED: a run ended by a signal is cancelled: it is not rerun, and its exit stands", async () => {
    const root = wallsRepo();
    const calls: string[][] = [];
    const gate: GateRunner = async (tasks) => {
      calls.push([...tasks]);
      return { status: 143, output: EOF_TRACE };
    };
    const judged = await judgedRun(gate, root, gradleModules(root), [":app:test"], () => true);
    expect(calls.length).toBe(1);
    expect(judged.reran).toBe(false);
    expect(judged.status).toBe(143);
  });

  test("RED: an EOF or a missing results file with no other gradle process live is not a collision", async () => {
    const root = wallsRepo();
    expect(isCollision(EOF_TRACE, root, gradleModules(root), () => false)).toBe(false);
    expect(isCollision(RESULTS_TRACE, root, gradleModules(root), () => false)).toBe(false);
    const calls: string[][] = [];
    const gate: GateRunner = async (tasks) => {
      calls.push([...tasks]);
      return { status: 1, output: EOF_TRACE };
    };
    const judged = await judgedRun(gate, root, gradleModules(root), [":app:test"], () => false);
    expect(calls.length).toBe(1);
    expect(judged.status).toBe(1);
  });

  test("the live-gradle check sees a gradle process running on this machine", async () => {
    const gradle = Bun.spawn(["bash", "-c", "exec -a GradleWrapperMain sleep 30"]);
    try {
      expect(liveGradleElsewhere()).toBe(true);
    } finally {
      gradle.kill();
    }
  });
});

describe("the gradle output's failing tasks and named files", () => {
  test("failing tasks are read by path", () => {
    const output = "> Task :core:compileKotlin FAILED\n> Task :core:detekt FAILED\n> Task :core:test\n";
    expect(failedTasks(output)).toEqual([":core:compileKotlin", ":core:detekt"]);
  });

  test("the files a failure names are the repository's own, relative to the root", () => {
    const root = "/work/repo";
    const output = [
      "e: file:///work/repo/core/src/A.kt:12:5 Unresolved reference 'x'",
      "/work/repo/core/src/B.kt:3:1: Detekt(MaxLineLength)",
      "/elsewhere/C.kt:1:1: outside the repository",
    ].join("\n");
    expect([...namedFiles(output, root)].sort()).toEqual(["core/src/A.kt", "core/src/B.kt"]);
  });

  test("a named file's line is the seat lock's owner, or none", () => {
    const root = wallsRepo();
    expect(failureLines(root, COMPILE_RED.replace("/work/repo", root)).join("\n")).toContain("core/src/A.kt — seat lock: no seat lock");
  });

  test("the modules come from settings.gradle.kts's projectDir lines", () => {
    const root = dir("splice-settings-");
    writeFileSync(
      join(root, "settings.gradle.kts"),
      'include(":core")\nproject(":core").projectDir = file("core")\nproject(":features:turns").projectDir = file("features/turns/")\n',
    );
    expect(gradleModules(root)).toEqual([
      { path: ":core", dir: "core" },
      { path: ":features:turns", dir: "features/turns" },
    ]);
  });
});

describe("a fixture runs the hooks it has, and reads no ambient git config", () => {
  test("RED: a hook the fixture installs refuses the fixture's commit: no hook path is overridden", () => {
    const root = wallsRepo();
    writeFileSync(join(root, ".git", "hooks", "pre-commit"), "#!/bin/sh\necho fixture-hook >&2\nexit 1\n", { mode: 0o755 });
    writeFile(root, "docs/README.md", "docs\n");
    git(root, ["add", "docs/README.md"]);
    expect(() => commit(root, "chore(test): hooked")).toThrow("fixture-hook");
  });
});

describe("hook install", () => {
  test("writes both shims, executable, byte-for-byte the shim text, and a second install changes nothing", () => {
    const hooks = dir("splice-hooks-");
    writeFileSync(join(hooks, "post-commit"), "# >>> fence >>>\nfence body\n");
    const written = installShims(hooks, "/usr/bin/bun", "/repo/tools/gate/index.ts");
    expect(written.map((p) => basename(p))).toEqual(["pre-commit", "pre-push"]);
    for (const verb of ["pre-commit", "pre-push"]) {
      expect(readFileSync(join(hooks, verb), "utf8")).toBe(shimText("/usr/bin/bun", "/repo/tools/gate/index.ts", verb));
      expect(statSync(join(hooks, verb)).mode & 0o111).not.toBe(0);
    }
    installShims(hooks, "/usr/bin/bun", "/repo/tools/gate/index.ts");
    expect(readFileSync(join(hooks, "pre-commit"), "utf8")).toContain(SHIM_BEGIN);
    expect(readFileSync(join(hooks, "post-commit"), "utf8")).toBe("# >>> fence >>>\nfence body\n");
  });

  test("refuses a hook it did not write, and writes neither shim", () => {
    const hooks = dir("splice-hooks-");
    writeFileSync(join(hooks, "pre-push"), "#!/bin/sh\necho mine\n");
    expect(() => installShims(hooks, "/usr/bin/bun", "/repo/tools/gate/index.ts")).toThrow("not a splice gate shim");
    expect(existsSync(join(hooks, "pre-commit"))).toBe(false);
    expect(readFileSync(join(hooks, "pre-push"), "utf8")).toBe("#!/bin/sh\necho mine\n");
  });

  test("a bun or gate path with a single quote stays one shell word", () => {
    expect(shimText("/opt/bun's/bun", "/r/index.ts", "pre-push")).toContain(`exec '/opt/bun'\\''s/bun' '/r/index.ts' hook pre-push "$@"`);
  });
});
