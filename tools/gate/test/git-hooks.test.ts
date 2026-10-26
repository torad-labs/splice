// Red/green proof for the git hooks (`bun tools/gate hook pre-commit|pre-push|install`).
//
// Every judgement runs in a scratch repository: a fresh `git init` in tmp, the walls, a settings file that maps one
// module, and a node_modules symlink to the real one, so the pinned ast-grep is the one the gate runs. No test copies
// the shared checkout. Gradle is a fake, `compiler`, that judges the scratch worktree's bytes. The probes that need the real
// gradle wrapper check gradle, not the hooks, and live in gradle-inputs.test.ts.
import { afterAll, describe, expect, test } from "bun:test";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join } from "node:path";
import {
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
  printableRemote,
  rerunTasks,
  SHIM_BEGIN,
  scanMirror,
  shimText,
} from "../src/commands/hook.ts";
import type { LintVerdict } from "../src/lib/lint-staged.ts";
import type { Leg } from "../src/lib/prepush-scope.ts";
import { parseProfile, type PushTiming } from "../src/lib/prepush-timing.ts";
import { preparePrePushTree } from "../src/lib/prepush-tree.ts";
import { type LaneOptions, ownerOf } from "../src/lib/push-lane.ts";
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
  mkdirSync(join(root, "gradle"), { recursive: true });
  writeFileSync(join(root, "gradle", "module-law.txt"), ":core ->\n");
  git(root, ["add", "--", "sgconfig.yml", "quality", "settings.gradle.kts"]);
  git(root, ["add", "-f", "--", "gradle/module-law.txt"]);
  commit(root, "chore(test): scratch scaffold");
  // The scaffold is what origin/main is in this repository, so a new branch's base is the scaffold.
  git(root, ["update-ref", "refs/remotes/origin/main", "HEAD"]);
  return root;
}

function writeFile(root: string, path: string, content: string): void {
  mkdirSync(dirname(join(root, path)), { recursive: true });
  writeFileSync(join(root, path), content);
}

function lay(root: string): Layout {
  return { repoRoot: root, buildRoot: root };
}

/** A fake gradle: judges the bytes in the worktree's `core/`, and names each file that holds the violation. */
function compiler(fallback: string, calls: string[][] = []): GateRunner {
  return async (tasks, at) => {
    const root = at ?? fallback;
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

/** git's stdin names the LOCAL ref it read the tip from, and the hook re-reads that ref when the judgement ends, so a
 *  fixture names its own branch rather than one that does not exist. */
const pushOf = (root: string, sha: string): string =>
  `${branchRef(root)} ${sha} refs/heads/feat/x 0000000000000000000000000000000000000000\n`;

/** The fixture's own branch, whatever `git init` named it. */
function branchRef(root: string): string {
  return Bun.spawnSync(["git", "symbolic-ref", "HEAD"], { cwd: root, stdout: "pipe" }).stdout.toString().trim();
}
/** The fixtures have no ladder: a pre-push test that is not about the ladder injects this empty one. */
const NO_LEGS: Leg[] = [];

describe("pre-commit judges the bytes the commit holds", () => {
  test("RED: a staged violation blocks the commit, and gradle is never asked", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, VIOLATION);
    git(root, ["add", TARGET]);
    const calls: string[][] = [];
    expect(await preCommit(lay(root))).toBe(1);
    expect(calls).toEqual([]);
  });

  test("a commit with no Kotlin in it passes without running a scan", async () => {
    const root = wallsRepo();
    writeFile(root, "README.md", "docs\n");
    git(root, ["add", "README.md"]);
    expect(await preCommit(lay(root))).toBe(0);
  });

  test("the commit is judged by the staged bytes: a worktree that differs does not block a clean index", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    writeFile(root, TARGET, VIOLATION);
    expect(await preCommit(lay(root))).toBe(0);
  });

  test("RED: a staged violation is refused even when the worktree holds the clean form", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, VIOLATION);
    git(root, ["add", TARGET]);
    writeFile(root, TARGET, CLEAN);
    expect(await preCommit(lay(root))).toBe(1);
  });

  test("a symlink whose target holds a byte that is not UTF-8 is judged by its raw bytes", async () => {
    const root = wallsRepo();
    writeFile(root, "docs/README.md", "docs\n");
    symlinkSync(Buffer.concat([Buffer.from("README"), Buffer.from([0xff]), Buffer.from(".md")]), join(root, "docs", "raw-link"));
    git(root, ["add", "docs/raw-link"]);
    expect(await preCommit(lay(root))).toBe(0);
  });

  test("RED: a finding from the commit's linters blocks the commit, and a clean verdict lets it through", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    const finding = { findings: [{ tool: "ktlint" as const, line: `${TARGET}:1:1: nope` }] };
    const { result, text } = await captured(() => preCommit(lay(root), async () => finding));
    expect(result).toBe(1);
    expect(text).toContain(`ktlint  ${TARGET}:1:1: nope`);
    expect(await preCommit(lay(root), async () => ({ findings: [] }))).toBe(0);
  });

  test("RED: linters that could not judge refuse the commit and say why", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    const { result, text } = await captured(() => preCommit(lay(root), async () => ({ error: "no jar" })));
    expect(result).toBe(1);
    expect(text).toContain("could not judge: no jar");
  });

  test("the linters are handed the staged Kotlin only, and not asked for a commit without any", async () => {
    const root = wallsRepo();
    writeFile(root, "README.md", "docs\n");
    git(root, ["add", "README.md"]);
    let asked = false;
    expect(await preCommit(lay(root), async () => ((asked = true), { findings: [] }))).toBe(0);
    expect(asked).toBe(false);
  });

  test("a Kotlin file no check covers is refused, and gradle is never asked", async () => {
    const root = wallsRepo();
    writeFile(root, "scripts/Helper.kts", "val x = 1\n");
    git(root, ["add", "scripts/Helper.kts"]);
    const calls: string[][] = [];
    expect(await preCommit(lay(root))).toBe(1);
    expect(calls).toEqual([]);
  });

});

describe("a ladder leg the commit's path triggers refuses the commit before the Kotlin judgement", () => {
  test("RED: the failing leg is named and the Kotlin judgement never runs", async () => {
    const root = dir("splice-hook-legs-");
    git(root, ["init", "-q"]);
    writeFile(root, "core/src/main/kotlin/splice/core/Seat.kt", CLEAN);
    git(root, ["add", "--", "core"]);
    commit(root, "chore(test): scratch base");
    writeFile(root, "sgconfig.yml", "ruleDirs: []\n");
    git(root, ["add", "--", "sgconfig.yml"]);
    const calls: string[][] = [];
    const failing: Leg = { task: "gateRules", command: ["bash", "-c", "exit 1"], inputs: ["**"], commit: ["sgconfig.yml"] };
    const { result, text } = await captured(() => commitGate(lay(root), { gate: compiler(root, calls), legs: [failing] }));
    expect(result).toBe(1);
    expect(text).toContain("gateRules");
    expect(calls).toEqual([]);
  });
});

describe("the contract holds until the gate has judged the bytes it reads", () => {
});

describe("every Kotlin file maps to a check", () => {
  const modules = [{ path: ":app", dir: "app" }, { path: ":app:core", dir: "app/core" }];

  test("a module file is checked by its own compile, test compile and detekt; the longest directory wins", () => {
    expect(checksFor(modules, "app/core/X.kt")).toEqual([":app:core:compileKotlin", ":app:core:compileTestKotlin", ":app:core:detekt", ":app:core:ktlintCheck"]);
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

  test("a push of deletions only has nothing to judge", async () => {
    const stdin = `(delete) 0000000000000000000000000000000000000000 refs/heads/gone ${head(repoRoot)}\n`;
    expect(await prePush(layout(), stdin)).toBe(0);
  });

  test("RED: a violating tip blocks the push, judged by the child gate of the worktree", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, VIOLATION);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): probe tip");
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root), openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(1);
    expect(text).toContain("PRE-PUSH: FAIL — judged the pushed sha");
  });

  test("GREEN: the fixed tip passes the same child gate", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, VIOLATION);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): probe tip");
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): fixed tip");
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root), openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(0);
    expect(text).toContain("PRE-PUSH: PASS — judged the pushed sha");
  });

  test("a dirty file in the shared checkout does not redden the push: the commit is judged in the build tree", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): probe tip");
    writeFile(root, SEAT_FILE, VIOLATION);
    const trees: string[] = [];
    const gate: GateRunner = async (tasks, at) => {
      trees.push(at ?? "");
      return compiler(root)(tasks, at);
    };
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate, openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(0);
    expect(text).toContain("judged the pushed sha");
    expect(trees.length).toBe(1);
    expect(trees[0]).not.toBe(root);
  });

  test("a push whose tip is not the checked-out HEAD is judged as that commit", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, VIOLATION);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): violating tip");
    const violating = head(root);
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): fixed tip");
    const { result } = await captured(() => prePush(lay(root), pushOf(root, violating), { gate: compiler(root), openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(1);
  });

  test("RED: a peer's commit during the judgement moves the branch, and the push is refused rather than carrying it", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): the tip this push judges");
    const judged = head(root);
    // The peer commits while gradle runs, exactly as a shared checkout lets it: git reads the ref again at the end of
    // the push, so what lands is this later commit and not the one the gate judged.
    const gate: GateRunner = async (...args) => {
      writeFile(root, TARGET, CLEAN.replace("Probe", "Peer"));
      git(root, ["add", TARGET]);
      commit(root, "chore(test): a peer's commit, never judged");
      return await compiler(root)(...args);
    };
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, judged), { gate, openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(1);
    expect(text).toContain("the branch moved while the gate judged");
    expect(text).toContain(`not the judged ${judged.slice(0, 7)}`);
    // The refusal carries the command that cannot carry an unjudged commit, so nobody has to remember it.
    expect(text).toContain(`git push origin ${judged}:refs/heads/feat/x`);
  });

  test("GREEN: the explicit-sha refspec passes through a peer's commit, because a sha has no ref left to re-read", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): the tip this push names");
    const judged = head(root);
    const gate: GateRunner = async (...args) => {
      writeFile(root, TARGET, CLEAN.replace("Probe", "Peer"));
      git(root, ["add", TARGET]);
      commit(root, "chore(test): a peer's commit, which this push does not carry");
      return await compiler(root)(...args);
    };
    // What `git push origin <sha>:refs/heads/feat/x` puts on the hook's stdin: the local side is the sha itself.
    const stdin = `${judged} ${judged} refs/heads/feat/x 0000000000000000000000000000000000000000\n`;
    const { result, text } = await captured(() => prePush(lay(root), stdin, { gate, openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(0);
    expect(text).toContain("PRE-PUSH: PASS");
  });
});

describe("the moved-tip refusal", () => {
  const ZERO = "0000000000000000000000000000000000000000";

  test("a push to a URL with a token in its userinfo prints the destination without it", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): the tip this push judges");
    const judged = head(root);
    const gate: GateRunner = async (...args) => {
      writeFile(root, TARGET, CLEAN.replace("Probe", "Peer"));
      git(root, ["add", TARGET]);
      commit(root, "chore(test): a peer's commit, never judged");
      return await compiler(root)(...args);
    };
    const remote = "https://someone:ghp_SECRETTOKEN@github.com/torad-labs/splice.git?token=ALSOSECRET";
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, judged), { gate, openRun: () => null, legs: NO_LEGS }, remote));
    expect(result).toBe(1);
    expect(text).toContain(`git push https://github.com/torad-labs/splice.git ${judged}:refs/heads/feat/x`);
    for (const secret of ["ghp_SECRETTOKEN", "someone:", "ALSOSECRET", "@github.com"]) expect(text).not.toContain(secret);
  });

  test("printableRemote keeps a name, drops userinfo from a URL and from an scp-like address", () => {
    expect(printableRemote("origin")).toBe("origin");
    expect(printableRemote("ssh://git:pw@host.example:2222/org/repo.git")).toBe("ssh://host.example:2222/org/repo.git");
    expect(printableRemote("git@github.com:torad-labs/splice.git")).toBe("github.com:torad-labs/splice.git");
    expect(printableRemote("/srv/git/splice.git")).toBe("/srv/git/splice.git");
    expect(printableRemote("file:///srv/git/splice.git")).toBe("file:///srv/git/splice.git");
  });

  test("a push of two refs is refused when the FIRST moved while the second was being judged", async () => {
    const root = wallsRepo();
    git(root, ["checkout", "-q", "-b", "first"]);
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): the first tip");
    const first = head(root);
    git(root, ["checkout", "-q", "-b", "second", "origin/main"]);
    writeFile(root, TARGET, CLEAN.replace("Probe", "Second"));
    git(root, ["add", TARGET]);
    commit(root, "chore(test): the second tip");
    const second = head(root);
    let judgements = 0;
    const gate: GateRunner = async (...args) => {
      judgements += 1;
      if (judgements === 2) {
        // while the second tip is judged, a peer commits on the first branch, whose verdict was already given
        git(root, ["checkout", "-q", "first"]);
        writeFile(root, TARGET, CLEAN.replace("Probe", "Peer"));
        git(root, ["add", TARGET]);
        commit(root, "chore(test): a peer's commit on the first branch, never judged");
        git(root, ["checkout", "-q", "second"]);
      }
      return await compiler(root)(...args);
    };
    const stdin = `refs/heads/first ${first} refs/heads/first ${ZERO}\nrefs/heads/second ${second} refs/heads/second ${ZERO}\n`;
    const { result, text } = await captured(() => prePush(lay(root), stdin, { gate, openRun: () => null, legs: NO_LEGS }));
    expect(judgements).toBe(2);
    expect(result).toBe(1);
    expect(text).toContain(`refs/heads/first is now`);
    expect(text).toContain(`not the judged ${first.slice(0, 7)}`);
  });

  test("an annotated tag pushed by its own object sha is not called moved", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): the tagged tip");
    git(root, ["-c", "user.name=test", "-c", "user.email=test@test", "-c", "tag.gpgsign=false", "tag", "-a", "v9.9.9", "-m", "release"]);
    const tagObject = Bun.spawnSync(["git", "rev-parse", "refs/tags/v9.9.9"], { cwd: root, stdout: "pipe" }).stdout.toString().trim();
    expect(tagObject).not.toBe(head(root));
    const stdin = `refs/tags/v9.9.9 ${tagObject} refs/tags/v9.9.9 ${ZERO}\n`;
    const { result, text } = await captured(() => prePush(lay(root), stdin, { gate: compiler(root), openRun: () => null, legs: NO_LEGS }));
    expect(text).not.toContain("moved");
    expect(result).toBe(0);
  });
});

/** A gradle that runs until it is stopped, then ends as a run killed by SIGTERM does. */
const untilStopped: GateRunner = (_tasks, _at, cancel) =>
  new Promise((resolve) => {
    const stopped = () => resolve({ status: 143, output: "" });
    if (cancel?.aborted) stopped();
    else cancel?.addEventListener("abort", stopped, { once: true });
  });

/** A scratch repository whose one commit above the scaffold changes a file of `:core`. */
const oneModule = (): string => {
  const root = wallsRepo();
  writeFile(root, TARGET, CLEAN);
  git(root, ["add", TARGET]);
  commit(root, "chore(test): one module");
  return root;
};

describe("pre-push stops a gradle run whose push cannot pass, and records how long each push took", () => {
  test("RED: a direct leg that fails stops the gradle run at once, and the verdict says so", async () => {
    const root = oneModule();
    const red: Leg[] = [{ task: "redLeg", command: ["bun", "-e", "process.exit(3)"], inputs: ["**"] }];
    const lines: PushTiming[] = [];
    const { result, text } = await captured(() =>
      prePush(lay(root), pushOf(root, head(root)), { gate: untilStopped, openRun: () => null, legs: red, timings: (line) => void lines.push(line) }),
    );
    expect(result).toBe(1);
    expect(text).toContain("redLeg failed: stopping gradle");
    expect(text).toContain("pre-push: ✗ redLeg (gradle stopped after");
    expect(text).not.toContain("failing task(s)");
    expect(lines.map((line) => [line.verdict, line.cancelled, line.sha])).toEqual([["fail:redLeg", "redLeg failed", head(root)]]);
  });

  test("RED: a branch that moves while gradle runs stops it, and the push is refused as moved", async () => {
    const root = oneModule();
    const judged = head(root);
    const gate: GateRunner = (...args) => {
      writeFile(root, TARGET, CLEAN.replace("Probe", "Peer"));
      git(root, ["add", TARGET]);
      commit(root, "chore(test): a peer's commit while gradle runs");
      return untilStopped(...args);
    };
    const lines: PushTiming[] = [];
    const { result, text } = await captured(() =>
      prePush(lay(root), pushOf(root, judged), { gate, openRun: () => null, legs: NO_LEGS, movedPollMs: 25, timings: (line) => void lines.push(line) }),
    );
    expect(result).toBe(1);
    expect(text).toContain("the branch moved: stopping gradle");
    expect(text).toContain("the branch moved while the gate judged");
    expect(text).toContain(`git push origin ${judged}:refs/heads/feat/x`);
    expect(lines.map((line) => [line.verdict, line.cancelled])).toEqual([["fail:moved", "the branch moved"]]);
  });

  test("GREEN: a passing push leaves one line with its sha, scope, gradle seconds and verdict", async () => {
    const root = oneModule();
    const lines: PushTiming[] = [];
    const { result } = await captured(() =>
      prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root), openRun: () => null, legs: NO_LEGS, timings: (line) => void lines.push(line) }),
    );
    expect(result).toBe(0);
    expect(lines.length).toBe(1);
    expect(lines[0]).toMatchObject({ sha: head(root), verdict: "pass", cancelled: null, scope: expect.stringContaining(":core") });
    expect(typeof lines[0]?.gradleS).toBe("number");
  });

  const clean = async (): Promise<LintVerdict> => ({ findings: [] });
  const curlySpacing: LintVerdict = { findings: [{ tool: "ktlint", line: `${TARGET}:3:10: Missing spacing before "{" (standard:curly-spacing)` }] };

  test("GREEN: ktlint runs once over the Kotlin the push changes in a module, and gradle is told to skip its own", async () => {
    const root = oneModule();
    writeFile(root, "docs/NOTES.md", "notes\n");
    writeFile(root, "elsewhere/Loose.kt", "class Loose\n");
    git(root, ["add", "docs/NOTES.md", "elsewhere/Loose.kt"]);
    commit(root, "docs(test): a note and a Kotlin file outside every module");
    const { result, text } = await captured(() =>
      prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root), openRun: () => null, legs: NO_LEGS, ktlint: clean }),
    );
    expect(result).toBe(0);
    expect(text).toContain("ktlint: 1 changed Kotlin file(s) in one run; gradle skips its per-module ktlintCheck");
    expect(text).toContain("✓ ktlint (1 changed Kotlin file(s))");
  });

  test("RED: a ktlint finding in the push fails it, names the finding, and stops the gradle run", async () => {
    const root = oneModule();
    const { result, text } = await captured(() =>
      prePush(lay(root), pushOf(root, head(root)), { gate: untilStopped, openRun: () => null, legs: NO_LEGS, ktlint: async () => curlySpacing }),
    );
    expect(result).toBe(1);
    expect(text).toContain("ktlint failed: stopping gradle");
    expect(text).toContain(`${TARGET}:3:10: Missing spacing before "{" (standard:curly-spacing)`);
    expect(text).toContain("pre-push: ✗ ktlint (gradle stopped after");
  });

  test("RED: a ktlint that could not judge fails the push and says why", async () => {
    const root = oneModule();
    const { result, text } = await captured(() =>
      prePush(lay(root), pushOf(root, head(root)), { gate: untilStopped, openRun: () => null, legs: NO_LEGS, ktlint: async () => ({ error: "ktlint exited 2: boom" }) }),
    );
    expect(result).toBe(1);
    expect(text).toContain("ktlint could not judge: ktlint exited 2: boom");
  });

  test("a change to .editorconfig, the version catalog or build-logic leaves ktlint to gradle, in every module in scope", async () => {
    for (const [path, reason] of [
      [".editorconfig", ".editorconfig changed"],
      ["gradle/libs.versions.toml", "the version catalog changed"],
      ["build-logic/src/main/kotlin/Rule.kt", "build-logic changed"],
    ] as const) {
      const root = oneModule();
      writeFile(root, path, "x\n");
      git(root, ["add", "-f", path]);
      commit(root, "chore(test): a change that moves what ktlint says");
      const { result, text } = await captured(() =>
        prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root), openRun: () => null, legs: NO_LEGS, ktlint: clean }),
      );
      expect(result, path).toBe(0);
      expect(text, path).toContain(`ktlint: gradle lints every module in scope (${reason})`);
      expect(text, path).not.toContain("✓ ktlint");
    }
  });

  test("the profile report's configuration time and slowest tasks are read from gradle's markup", () => {
    const row = (task: string, time: string): string => `<tr>\n<td class="indentPath">${task}</td>\n<td class="numeric">${time}</td>\n<td></td>\n</tr>\n`;
    const html = [
      '<tr>\n<td>Configuring Projects</td>\n<td class="numeric">15.841s</td>\n</tr>\n',
      "<h2>Task Execution</h2>\n<table>\n",
      '<tr>\n<td>:app</td>\n<td class="numeric">6m0.01s</td>\n<td>(total)</td>\n</tr>\n',
      row(":app:compileKotlin", "6.765s"),
      row(":app:test", "3m26.70s"),
      row(":core:detekt", "0.4s"),
    ].join("");
    const profile = parseProfile(html);
    expect(profile.configS).toBe(15.841);
    expect(profile.tasks).toEqual([
      { task: ":app:test", s: 206.7 },
      { task: ":app:compileKotlin", s: 6.8 },
      { task: ":core:detekt", s: 0.4 },
    ]);
    expect(parseProfile("<html></html>")).toEqual({ configS: null, tasks: [] });
  });
});

// Load cut 3 (temple plan 2026-10-19-load-cuts-splice-and-torad-main.md, approved by Marcos): the four legs that read no code run
// and pass before gradle starts. Stopping a gradle run that already started also ends the warm pre-push daemon, so a red title
// or attribution must never start one.
describe("the legs that read no code run and pass before gradle starts", () => {
  const NO_CODE = ["prTitle", "attribution", "noPython", "gateRules"] as const;
  /** A ladder row that runs [script] in bun, from the tree the push is judged in. */
  const bunLeg = (task: string, script: string): Leg => ({ task, command: ["bun", "-e", script], inputs: ["**"] });

  test("RED: any of the four failing fails the push, and gradle is never asked", async () => {
    for (const task of NO_CODE) {
      const root = oneModule();
      const calls: string[][] = [];
      const lines: PushTiming[] = [];
      const { result, text } = await captured(() =>
        prePush(lay(root), pushOf(root, head(root)), {
          gate: compiler(root, calls),
          openRun: () => null,
          legs: [bunLeg(task, "process.exit(3)")],
          timings: (line) => void lines.push(line),
        }),
      );
      expect(result, task).toBe(1);
      expect(calls, task).toEqual([]);
      expect(text, task).toContain(`pre-push: ✗ ${task} (gradle not started: ${task} failed)`);
      expect(lines.map((line) => [line.verdict, line.gradleS]), task).toEqual([[`fail:${task}`, null]]);
    }
  });

  test("RED: gradle starts only once all four have passed", async () => {
    const root = oneModule();
    const marks = dir("splice-hook-marks-");
    const legs = NO_CODE.map((task) => bunLeg(task, `await Bun.sleep(300); await Bun.write(${JSON.stringify(join(marks, task))}, "done");`));
    const seen: string[][] = [];
    const gate: GateRunner = async (...args) => {
      seen.push(NO_CODE.filter((task) => existsSync(join(marks, task))));
      return await compiler(root)(...args);
    };
    const { result } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate, openRun: () => null, legs }));
    expect(result).toBe(0);
    expect(seen).toEqual([[...NO_CODE]]);
  });

  test("a leg that reads code still runs beside gradle: gradle does not wait for it", async () => {
    const root = oneModule();
    const mark = join(dir("splice-hook-marks-"), "codeLeg");
    const seen: boolean[] = [];
    const gate: GateRunner = async (...args) => {
      seen.push(existsSync(mark));
      return await compiler(root)(...args);
    };
    const legs = [bunLeg("codeLeg", `await Bun.sleep(1500); await Bun.write(${JSON.stringify(mark)}, "done");`)];
    const { result } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate, openRun: () => null, legs }));
    expect(result).toBe(0);
    expect(seen).toEqual([false]);
  });

  test("RED: a leg beside them that fails before they finish keeps gradle from starting", async () => {
    const root = oneModule();
    const calls: string[][] = [];
    const legs = [bunLeg("prTitle", "await Bun.sleep(1500);"), bunLeg("codeLeg", "process.exit(3)")];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root, calls), openRun: () => null, legs }));
    expect(result).toBe(1);
    expect(calls).toEqual([]);
    expect(text).toContain("pre-push: ✗ codeLeg (gradle not started: codeLeg failed)");
  });

  test("RED: a branch that moves while they run keeps gradle from starting, and the push is refused as moved", async () => {
    const root = oneModule();
    const judged = head(root);
    const calls: string[][] = [];
    // The leg moves the pushed branch back to the scaffold, as a peer's command in the shared checkout can while the legs run.
    const move = `GIT_CONFIG_GLOBAL=${JSON.stringify(EMPTY_GIT_CONFIG)} GIT_CONFIG_NOSYSTEM=1 git -C ${JSON.stringify(root)} update-ref ${branchRef(root)} refs/remotes/origin/main`;
    const legs: Leg[] = [{ task: "attribution", command: ["bash", "-c", move], inputs: ["**"] }];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, judged), { gate: compiler(root, calls), openRun: () => null, legs }));
    expect(result).toBe(1);
    expect(calls).toEqual([]);
    expect(text).toContain("the branch moved while the gate judged");
    expect(text).toContain("(gradle not started: the branch moved)");
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
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root, calls), openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(0);
    expect(calls).toEqual([[":core:check", "lawSuites"]]);
    expect(text).toContain("; scope: no legs; gradle: check of :core, lawSuites; PublicSourceNamesNoHostToolTest in lawSuites");
  });

  test("a Kotlin push requests lawSuites after its checks, and gradle decides which suite runs", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): one module");
    const calls: string[][] = [];
    const { result } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root, calls), openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(0);
    expect(calls).toEqual([[":core:check", "lawSuites"]]);
  });

  test("a docs push starts no gradle and says so in the verdict", async () => {
    const root = wallsRepo();
    docsCommit(root);
    const calls: string[][] = [];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root, calls), openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(0);
    expect(calls).toEqual([]);
    expect(text).toContain("PRE-PUSH: PASS — judged the pushed sha");
    expect(text).toContain("; scope: no legs; gradle: none (docs only)");
  });

  test("a new branch is compared with its merge base with origin/main", async () => {
    const root = wallsRepo();
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): new branch");
    const calls: string[][] = [];
    expect(await prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root, calls), openRun: () => null, legs: NO_LEGS })).toBe(0);
    expect(calls[0]).toContain(":core:check");
  });

  test("RED: a new branch with no origin/main to compare against is refused before any gate runs", async () => {
    const root = wallsRepo();
    git(root, ["update-ref", "-d", "refs/remotes/origin/main"]);
    writeFile(root, TARGET, CLEAN);
    git(root, ["add", TARGET]);
    commit(root, "chore(test): new branch");
    const calls: string[][] = [];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root, calls), openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(1);
    expect(calls).toEqual([]);
    expect(text).toContain("pre-push: ✗ cannot scope the push:");
  });

  test("RED: a checkout with no ladder refuses the push rather than judge a guessed scope", async () => {
    const root = wallsRepo();
    docsCommit(root);
    const calls: string[][] = [];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root, calls), openRun: () => null }));
    expect(result).toBe(1);
    expect(calls).toEqual([]);
    expect(text).toContain("pre-push: ✗ cannot scope the push:");
    expect(text).toContain("ladder.json");
  });

  test("RED: a ladder row that declares no inputs refuses the push by name", async () => {
    const root = wallsRepo();
    docsCommit(root);
    const bare: Leg[] = [{ task: "bareRow", command: ["true"] }];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root), openRun: () => null, legs: bare }));
    expect(result).toBe(1);
    expect(text).toContain("ladder rows declare no inputs, so the push cannot be scoped: bareRow");
  });

  test("RED: a direct leg that exits nonzero fails the push, and the verdict names it with its scope", async () => {
    const root = wallsRepo();
    docsCommit(root);
    const red: Leg[] = [{ task: "redLeg", command: ["bun", "-e", "process.exit(3)"], inputs: ["**"] }];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root), openRun: () => null, legs: red }));
    expect(result).toBe(1);
    expect(text).toContain("PRE-PUSH: FAIL — judged the pushed sha ");
    expect(text).toContain("; scope: legs redLeg; gradle: none (docs only)");
    expect(text).toContain("redLeg");
  });

  test("GREEN: a direct leg that passes lets a docs-only push through", async () => {
    const root = wallsRepo();
    docsCommit(root);
    const calls: string[][] = [];
    const green: Leg[] = [{ task: "greenLeg", command: ["bun", "-e", "process.exit(0)"], inputs: ["**"] }];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: compiler(root, calls), openRun: () => null, legs: green }));
    expect(result).toBe(0);
    expect(calls).toEqual([]);
    expect(text).toContain("; scope: legs greenLeg; gradle: none (docs only)");
  });
});

// Load cut 4 of the temple plan of 2026-10-19, approved by Marcos: :app:codeModePackagedTest reruns the code-mode suites against
// the fat jar, and a push whose changes cannot change what the code-mode worker loads from that jar skips it. Which paths decide
// is packaged-worker.test.ts's to pin; this pins what the push does with the answer.
describe("pre-push runs the packaged code-mode test only when the push can change what the worker loads", () => {
  const MODULES = [
    [":core", "core"],
    [":integrations-upstream", "integrations/upstream"],
    [":integrations-dialects-openai-responses", "integrations/dialects/openai-responses"],
    [":integrations-codemode", "integrations/codemode"],
    [":integrations-providers-codex", "integrations/providers/codex"],
    [":features-turns", "features/turns"],
    [":app", "app"],
  ] as const;
  const WORKER_FILE = "integrations/codemode/src/main/kotlin/splice/codemode/WorkerSession.kt";
  const FEATURE_FILE = "features/turns/src/main/kotlin/splice/head/TurnDriver.kt";
  const SKIPPED = "; :app:codeModePackagedTest skipped: nothing the push changes can change what the code-mode worker loads";

  /** A scratch repository with the worker's modules, a feature module and :app, mapped and lawed as the real ones are, and an
   *  app build that declares the packaged run. That scaffold is origin/main, so a push is judged by its own commits. */
  const workerRepo = (): string => {
    const root = wallsRepo();
    const settings = MODULES.flatMap(([path, dir]) => [`include("${path}")`, `project("${path}").projectDir = file("${dir}")`]);
    writeFile(root, "settings.gradle.kts", ['rootProject.name = "scratch"', ...settings, ""].join("\n"));
    writeFile(
      root,
      "gradle/module-law.txt",
      [
        ":core ->",
        ":integrations-upstream -> :core",
        ":integrations-dialects-openai-responses -> :core :integrations-upstream",
        ":integrations-codemode -> :core :integrations-upstream",
        ":integrations-providers-codex -> :core :integrations-upstream :integrations-dialects-openai-responses",
        ":features-turns -> :core :integrations-upstream",
        "",
      ].join("\n"),
    );
    writeFile(
      root,
      "app/build.gradle.kts",
      'val codeModePackagedTest = tasks.register<Test>("codeModePackagedTest") {\n    filter {\n        includeTestsMatching("CodeModeLanguagesTest")\n    }\n}\n',
    );
    writeFile(root, WORKER_FILE, "package splice.codemode\n\nclass WorkerSession\n");
    writeFile(root, FEATURE_FILE, "package splice.head\n\nclass TurnDriver\n");
    git(root, ["add", "--", "settings.gradle.kts", "app", "integrations", "features"]);
    git(root, ["add", "-f", "--", "gradle/module-law.txt"]);
    commit(root, "chore(test): the worker's modules");
    git(root, ["update-ref", "refs/remotes/origin/main", "HEAD"]);
    return root;
  };

  const change = (root: string, path: string, text: string): void => {
    writeFile(root, path, text);
    git(root, ["add", "--", path]);
    commit(root, "chore(test): a change");
  };

  interface Run {
    readonly tasks: readonly string[];
    readonly options: readonly string[];
  }

  /** A gradle that records each run's tasks and options, and answers with [answers] in turn, then green. */
  const recording =
    (runs: Run[], answers: { status: number; output: string }[] = []): GateRunner =>
    async (tasks, _at, _cancel, options = []) => {
      runs.push({ tasks: [...tasks], options: [...options] });
      return answers[runs.length - 1] ?? { status: 0, output: "BUILD SUCCESSFUL\n" };
    };

  test("RED: a push confined to code the worker never loads runs gradle without the packaged test, and the verdict says so", async () => {
    const root = workerRepo();
    change(root, FEATURE_FILE, "package splice.head\n\nclass TurnDriver(val turns: Int)\n");
    const runs: Run[] = [];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: recording(runs), openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(0);
    expect(runs.map((run) => run.options)).toEqual([["--exclude-task=:app:codeModePackagedTest"]]);
    expect(runs[0]?.tasks).toContain(":app:check");
    expect(text.split("\n").find((line) => line.startsWith("PRE-PUSH: PASS"))).toEndWith(SKIPPED);
  });

  test("RED: a push that changes the worker's code runs the packaged test, and says which path decided", async () => {
    const root = workerRepo();
    change(root, WORKER_FILE, "package splice.codemode\n\nclass WorkerSession(val id: Int)\n");
    const runs: Run[] = [];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: recording(runs), openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(0);
    expect(runs.map((run) => run.options)).toEqual([[]]);
    expect(text).toContain(`── :app:codeModePackagedTest runs: ${WORKER_FILE} is in :integrations-codemode`);
    expect(text).not.toContain(SKIPPED);
  });

  test("RED: a collision's rerun skips the packaged test as the first run did", async () => {
    const root = workerRepo();
    change(root, FEATURE_FILE, "package splice.head\n\nclass TurnDriver(val turns: Int)\n");
    const runs: Run[] = [];
    const gate = recording(runs, [{ status: 1, output: EOF_TRACE }]);
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate, openRun: () => null, legs: NO_LEGS, rivalLive: () => true }));
    expect(result).toBe(0);
    expect(text).toContain("passed on the rerun after a collision");
    expect(runs.map((run) => run.options)).toEqual([["--exclude-task=:app:codeModePackagedTest"], ["--exclude-task=:app:codeModePackagedTest"]]);
  });

  test("a push whose gradle has no :app:check excludes nothing, since the packaged test is not in its graph", async () => {
    const root = oneModule();
    const runs: Run[] = [];
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate: recording(runs), openRun: () => null, legs: NO_LEGS }));
    expect(result).toBe(0);
    expect(runs.map((run) => run.options)).toEqual([[]]);
    expect(text).not.toContain("codeModePackagedTest");
  });
});

// Load cut 2 of the temple plan of 2026-10-19, approved by Marcos: seats push one shared branch, so pushes queue behind the one
// being judged, and each holds the commits below it. Only the newest queued push is judged; an older one is told its commit was
// carried; when the newest fails, the next older one is judged. A push stands for its git process: it is queued while that runs.
describe("the push lane judges only the newest queued push of a branch", () => {
  const ON_ORIGIN = "refs/remotes/origin/feat";

  /** A scratch repository whose branch is on origin at the scaffold, with three commits above it, each holding the one before. */
  const laneRepo = (): { root: string; base: string; chain: [string, string, string] } => {
    const root = wallsRepo();
    const base = head(root);
    git(root, ["update-ref", ON_ORIGIN, base]);
    const chain: string[] = [];
    for (const n of [1, 2, 3]) {
      writeFile(root, TARGET, CLEAN.replace("out.close()", `out.close() // ${n}`));
      git(root, ["add", TARGET]);
      commit(root, `chore(test): lane commit ${n}`);
      chain.push(head(root));
    }
    return { root, base, chain: chain as [string, string, string] };
  };

  /** git's stdin for a push of [tip] by its sha, as the seats push, to a branch the remote holds at [base]. */
  const laneStdin = (tip: string, base: string): string => `${tip} ${tip} refs/heads/feat ${base}\n`;

  /** A push whose git process is the [n]th fake one, running while [live] holds n. */
  const pushOwner = (n: number, live: ReadonlySet<number>): LaneOptions => ({
    owner: { pid: 4_000_000 + n, start: "lane-test" },
    alive: (owner) => live.has(owner.pid - 4_000_000),
    pollMs: 10,
  });

  /** A green gradle that records which commit each run judged, red for [red], and that finishes only once [hold] settles. */
  const judging =
    (judged: string[], red: ReadonlySet<string> = new Set(), hold: Promise<void> = Promise.resolve()): GateRunner =>
    async (_tasks, at) => {
      const sha = Bun.spawnSync(["git", "rev-parse", "HEAD"], { cwd: at, stdout: "pipe" }).stdout.toString().trim();
      judged.push(sha);
      await hold;
      return red.has(sha) ? { status: 1, output: COMPILE_RED } : { status: 0, output: "BUILD SUCCESSFUL\n" };
    };

  /** [fn]'s result and every line printed meanwhile; [fn] reads the lines as they come, to wait for one. */
  async function printing<T>(fn: (lines: readonly string[]) => Promise<T>): Promise<{ result: T; text: string }> {
    const lines: string[] = [];
    const log = console.log;
    const err = console.error;
    console.log = (...args: unknown[]) => void lines.push(args.map(String).join(" "));
    console.error = (...args: unknown[]) => void lines.push(args.map(String).join(" "));
    try {
      return { result: await fn(lines), text: lines.join("\n") };
    } finally {
      console.log = log;
      console.error = err;
    }
  }

  const until = async (what: string, done: () => boolean): Promise<void> => {
    const deadline = Date.now() + 10_000;
    while (!done()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await Bun.sleep(5);
    }
  };

  const short = (sha: string): string => sha.slice(0, 9);
  const waitsFor = (lines: readonly string[], older: string, newer: string): boolean =>
    lines.some((line) => line.includes(`push lane: ${short(older)} waits; ${short(newer)}`));

  test("RED: of three queued pushes that each hold the one before, only the newest is judged, and the older two are told they were carried", async () => {
    const { root, base, chain } = laneRepo();
    const [c1, c2, c3] = chain;
    const live = new Set([1, 2, 3]);
    const judged: string[] = [];
    const held = preparePrePushTree(root, base);
    const { result, text } = await printing(async (lines) => {
      const pushes = chain.map((tip, at) =>
        prePush(lay(root), laneStdin(tip, base), { gate: judging(judged), openRun: () => null, legs: NO_LEGS, lane: pushOwner(at + 1, live) }),
      );
      try {
        await until("both older pushes to wait for the newest", () => waitsFor(lines, c1, c3) && waitsFor(lines, c2, c3));
      } finally {
        held.release();
      }
      const newest = await pushes[2];
      // git sends the newest: origin's branch moves to it, and its git process ends.
      git(root, ["update-ref", ON_ORIGIN, c3]);
      live.delete(3);
      return [await pushes[0], await pushes[1], newest];
    });
    expect(judged).toEqual([c3]);
    expect(result).toEqual([1, 1, 0]);
    for (const tip of [c1, c2]) expect(text).toContain(`PRE-PUSH: CARRIED — ${short(tip)} is on origin/feat already, inside ${short(c3)}`);
  });

  test("RED: when the newest queued push fails, the next older one is judged, and the oldest is carried by it", async () => {
    const { root, base, chain } = laneRepo();
    const [c1, c2, c3] = chain;
    const live = new Set([1, 2, 3]);
    const judged: string[] = [];
    const held = preparePrePushTree(root, base);
    const { result, text } = await printing(async (lines) => {
      const pushes = chain.map((tip, at) =>
        prePush(lay(root), laneStdin(tip, base), { gate: judging(judged, new Set([c3])), openRun: () => null, legs: NO_LEGS, lane: pushOwner(at + 1, live) }),
      );
      try {
        await until("both older pushes to wait for the newest", () => waitsFor(lines, c1, c3) && waitsFor(lines, c2, c3));
      } finally {
        held.release();
      }
      const newest = await pushes[2];
      const middle = await pushes[1];
      git(root, ["update-ref", ON_ORIGIN, c2]);
      live.delete(2);
      return [await pushes[0], middle, newest];
    });
    expect(judged).toEqual([c3, c2]);
    expect(result).toEqual([1, 0, 1]);
    expect(text).toContain(`PRE-PUSH: CARRIED — ${short(c1)} is on origin/feat already, inside ${short(c2)}`);
  });

  test("RED: a push queued behind one that lands without its commit is refused before it is judged, since the remote would refuse it", async () => {
    const root = wallsRepo();
    const base = head(root);
    git(root, ["update-ref", ON_ORIGIN, base]);
    writeFile(root, TARGET, CLEAN.replace("out.close()", "out.close() // x"));
    git(root, ["add", TARGET]);
    commit(root, "chore(test): one seat's commit");
    const x = head(root);
    git(root, ["checkout", "-q", "-b", "other", base]);
    writeFile(root, TARGET, CLEAN.replace("out.close()", "out.close() // y"));
    git(root, ["add", TARGET]);
    commit(root, "chore(test): another seat's commit, beside it");
    const y = head(root);
    const live = new Set([1, 2]);
    const judged: string[] = [];
    let finishX = (): void => {};
    const gate = judging(judged, new Set(), new Promise<void>((resolve) => (finishX = resolve)));
    try {
      const { result, text } = await printing(async (lines) => {
        const pushX = prePush(lay(root), laneStdin(x, base), { gate, openRun: () => null, legs: NO_LEGS, lane: pushOwner(1, live) });
        await until("x to be judged", () => judged.length === 1);
        const pushY = prePush(lay(root), laneStdin(y, base), { gate, openRun: () => null, legs: NO_LEGS, lane: pushOwner(2, live) });
        await until("y to wait in the lane", () => lines.some((line) => line.includes(`push lane: ${short(y)}`)));
        finishX();
        const xCode = await pushX;
        git(root, ["update-ref", ON_ORIGIN, x]);
        live.delete(1);
        return [xCode, await pushY];
      });
      expect(judged).toEqual([x]);
      expect(result).toEqual([0, 1]);
      expect(text).toContain(`PRE-PUSH: FAIL — origin/feat moved to ${short(x)} while this push waited`);
    } finally {
      finishX();
    }
  });

  test("RED: a queued push waits while the git process of a newer one runs, and is judged once it ends without landing", async () => {
    const { root, base, chain } = laneRepo();
    const [c1, c2] = chain;
    const newerGit = Bun.spawn(["sleep", "60"]);
    const newer = ownerOf(newerGit.pid);
    const self = ownerOf(process.pid);
    if (newer === undefined || self === undefined) throw new Error("no /proc to read the processes from");
    const judged: string[] = [];
    const held = preparePrePushTree(root, base);
    try {
      const { result } = await printing(async (lines) => {
        const deps = { gate: judging(judged), openRun: () => null, legs: NO_LEGS };
        const pushNewer = prePush(lay(root), laneStdin(c2, base), { ...deps, lane: { owner: newer, pollMs: 10 } });
        const pushOlder = prePush(lay(root), laneStdin(c1, base), { ...deps, lane: { owner: self, pollMs: 10 } });
        await until("the older push to wait for the newer", () => waitsFor(lines, c1, c2));
        held.release();
        const newerCode = await pushNewer;
        await Bun.sleep(100);
        const whileSending = [...judged];
        // The newer push's git ends and origin's branch stays where it was: its send failed.
        newerGit.kill();
        await newerGit.exited;
        return { whileSending, codes: [await pushOlder, newerCode] };
      });
      expect(result.whileSending).toEqual([c2]);
      expect(judged).toEqual([c2, c1]);
      expect(result.codes).toEqual([0, 0]);
    } finally {
      held.release();
      newerGit.kill();
    }
  });

  test("RED: a push that passed but whose git never finishes sending holds the lane no longer than the send bound", async () => {
    const { root, base, chain } = laneRepo();
    const [c1, c2] = chain;
    const live = new Set([1, 2]);
    const judged: string[] = [];
    const held = preparePrePushTree(root, base);
    const bounded = (n: number): LaneOptions => ({ ...pushOwner(n, live), sendWaitMs: 300 });
    const { result } = await printing(async (lines) => {
      const deps = { gate: judging(judged), openRun: () => null, legs: NO_LEGS };
      const pushNewer = prePush(lay(root), laneStdin(c2, base), { ...deps, lane: bounded(2) });
      const pushOlder = prePush(lay(root), laneStdin(c1, base), { ...deps, lane: bounded(1) });
      try {
        await until("the older push to wait for the newer", () => waitsFor(lines, c1, c2));
      } finally {
        held.release();
      }
      // The newer push passes, and its git never ends: origin's branch never moves.
      const older = await Promise.race([pushOlder, Bun.sleep(5_000).then(() => "still waiting")]);
      live.delete(2);
      return [older, await pushNewer, await pushOlder];
    });
    expect(judged).toEqual([c2, c1]);
    expect(result).toEqual([0, 0, 0]);
  });

  test("RED: a lane the hook cannot keep leaves the push on today's path, judged as before", async () => {
    const { root, base, chain } = laneRepo();
    const [c1] = chain;
    // The lane's folder is a file, so the lane cannot keep a push in it.
    mkdirSync(join(root, ".git", "splice-prepush"), { recursive: true });
    writeFileSync(join(root, ".git", "splice-prepush", "lane"), "");
    const judged: string[] = [];
    const { result } = await printing(() =>
      prePush(lay(root), laneStdin(c1, base), { gate: judging(judged), openRun: () => null, legs: NO_LEGS, lane: pushOwner(1, new Set([1])) }),
    );
    expect(result).toBe(0);
    expect(judged).toEqual([c1]);
  });

  test("RED: a remote-tracking branch that held the commit before the push queued is not taken as the push landing", async () => {
    const { root, base, chain } = laneRepo();
    const [c1, c2] = chain;
    // origin's branch went back to the scaffold after this checkout last saw it at c2: git connects and reads the scaffold,
    // while the remote-tracking branch still says c2.
    git(root, ["update-ref", ON_ORIGIN, c2]);
    const judged: string[] = [];
    const { result, text } = await printing(() =>
      prePush(lay(root), laneStdin(c1, base), { gate: judging(judged), openRun: () => null, legs: NO_LEGS, lane: pushOwner(1, new Set([1])) }),
    );
    expect(text).not.toContain("CARRIED");
    expect(judged).toEqual([c1]);
    expect(result).toBe(0);
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
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate, openRun: () => null, legs: NO_LEGS, rivalLive: () => true }));
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
    const { result, text } = await captured(() => prePush(lay(root), pushOf(root, head(root)), { gate, openRun: () => null, legs: NO_LEGS, rivalLive: () => true }));
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
    expect(await prePush(lay(root), pushOf(root, head(root)), { gate, openRun: () => null, legs: NO_LEGS })).toBe(1);
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

  test("RED: a collision after every task printed its result reruns nothing", async () => {
    const calls: string[][] = [];
    const output = `${LOCK_TRACE}> Task :core:compileKotlin\n> Task :core:check\n`;
    const gate: GateRunner = async (tasks) => {
      calls.push([...tasks]);
      return { status: 1, output };
    };
    const judged = await judgedRun(gate, repoRoot, [], [":core:compileKotlin", ":core:check"], () => true);
    expect(calls).toEqual([[":core:compileKotlin", ":core:check"]]);
    expect(judged.reran).toBe(false);
    expect(judged.status).toBe(1);
  });

  test("a run whose every task printed its result names no rerun, and one that failed names its failure", () => {
    expect(rerunTasks([":a", ":b"], "> Task :a\n> Task :b\n")).toEqual([]);
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
